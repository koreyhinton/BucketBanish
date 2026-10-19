package bucketbanish.scan;

import bucketbanish.BucketBanishProperties;
import bucketbanish.analysis.BurstDetector;
import bucketbanish.analysis.DuplicateFinder;
import bucketbanish.analysis.ImageHash;
import bucketbanish.analysis.MetadataReader;
import bucketbanish.analysis.PhotoMeta;
import bucketbanish.analysis.PhotoObject;
import bucketbanish.store.PhotoStore;
import bucketbanish.store.StoreErrors;
import bucketbanish.store.Target;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Runs scans in the background: list, read EXIF, detect bursts, group duplicates. Read-only. */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    /** Bytes fetched for metadata; EXIF lives at the front of JPEG/HEIC/TIFF files. */
    static final long METADATA_HEAD_BYTES = 256 * 1024;
    private static final int MAX_JOBS = 10;
    private static final int MAX_WARNINGS = 25;

    private final PhotoStore store;
    private final int threads;
    private final ExecutorService jobRunner = Executors.newFixedThreadPool(2);
    private final Map<String, ScanJob> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Every photo seen by any scan; the thumbnail and report endpoints only serve these. */
    private final Map<String, PhotoMeta> known = new ConcurrentHashMap<>();

    public ScanService(PhotoStore store, BucketBanishProperties props) {
        this.store = store;
        this.threads = props.threadCount();
    }

    public ScanStatus start(ScanRequest request) {
        List<Target> targets = request.validate();
        ScanJob job = new ScanJob(UUID.randomUUID().toString(), request);
        synchronized (jobs) {
            while (jobs.size() >= MAX_JOBS) {
                jobs.remove(jobs.keySet().iterator().next());
            }
            jobs.put(job.id, job);
        }
        jobRunner.submit(() -> {
            try {
                job.finish(run(job, targets));
            } catch (Exception e) {
                log.warn("Scan {} failed", job.id, e);
                job.fail(StoreErrors.explain(e));
            }
        });
        return job.snapshot();
    }

    public Optional<ScanStatus> status(String id) {
        return Optional.ofNullable(jobs.get(id)).map(ScanJob::snapshot);
    }

    /** Metadata for a photo that appeared in a scan, or empty if it never did. */
    public Optional<PhotoMeta> known(String uri) {
        return Optional.ofNullable(known.get(uri));
    }

    public String storeDescription() {
        return store.describe();
    }

    public boolean consoleLinks() {
        return store.consoleLinks();
    }

    private ScanResult run(ScanJob job, List<Target> targets) {
        ScanRequest req = job.request;
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());

        job.phase("listing", targets.size());
        List<PhotoObject> objects = new ArrayList<>();
        int listed = 0;
        for (Target t : targets) {
            objects.addAll(store.listPhotos(t.bucket(), t.prefix()));
            job.progress(++listed);
        }

        job.phase("reading metadata", objects.size());
        Map<PhotoObject, PhotoMeta> metaMap = parallel(job, objects, warnings, o -> {
            PhotoMeta meta = MetadataReader.read(o, store.getHead(o, METADATA_HEAD_BYTES));
            if (!meta.hasCaptureTime() && o.size() > METADATA_HEAD_BYTES) {
                meta = MetadataReader.read(o, store.getAll(o)); // EXIF wasn't in the first chunk
            }
            return meta;
        });
        List<PhotoMeta> metas = objects.stream().map(metaMap::get).filter(Objects::nonNull).toList();
        metas.forEach(m -> known.put(m.object().uri(), m));

        List<ScanResult.BurstSet> bursts = bursts(job, metas, warnings);
        List<ScanResult.SizeGroup> sizeGroups = new ArrayList<>();
        int hidden = sizeGroups(job, metas, metaMap, warnings, sizeGroups);
        long identicalBytes = sizeGroups.stream().filter(g -> g.verdict().equals("IDENTICAL"))
                .mapToLong(g -> g.size() * (g.photos().size() - 1)).sum();

        List<ScanResult.NameGroup> nameGroups = DuplicateFinder.sameNameAcrossBuckets(objects, req.ignoreCase())
                .stream()
                .map(g -> new ScanResult.NameGroup(g.name(), g.sameSize(), views(g.objects(), metaMap)))
                .toList();

        int undated = (int) metas.stream().filter(m -> !m.hasCaptureTime()).count();
        List<String> shownWarnings = warnings.size() > MAX_WARNINGS
                ? List.copyOf(warnings.subList(0, MAX_WARNINGS)) : List.copyOf(warnings);
        return new ScanResult(req.targets(), metas.size(), undated, bursts, sizeGroups, hidden, nameGroups,
                identicalBytes, shownWarnings, Instant.now());
    }

    private List<ScanResult.BurstSet> bursts(ScanJob job, List<PhotoMeta> metas, List<String> warnings) {
        ScanRequest req = job.request;
        Duration gap = Duration.ofMillis(Math.round(req.gapSeconds() * 1000));
        List<BurstDetector.Burst> bursts = BurstDetector.groupByTime(metas, gap, !req.anyCamera());

        Map<PhotoObject, Long> hashes = Map.of();
        if (req.visual() && !bursts.isEmpty()) {
            List<PhotoObject> inBursts = bursts.stream()
                    .flatMap(b -> b.photos().stream()).map(PhotoMeta::object).toList();
            job.phase("comparing burst photos", inBursts.size());
            hashes = parallel(job, inBursts, warnings, o -> ImageHash.dHash(store.getAll(o)));
        }

        List<ScanResult.BurstSet> sets = new ArrayList<>();
        for (BurstDetector.Burst b : bursts) {
            if (!req.visual() || BurstDetector.anyUnhashed(b, hashes)) {
                sets.add(burstSet(b.photos(), Map.of(), false)); // e.g. HEIC/RAW: Java can't decode, time only
            } else {
                for (List<PhotoMeta> cluster : BurstDetector.similarClusters(b, hashes, req.threshold())) {
                    sets.add(burstSet(cluster, hashes, true));
                }
            }
        }
        return sets;
    }

    private static ScanResult.BurstSet burstSet(List<PhotoMeta> photos, Map<PhotoObject, Long> hashes,
                                                boolean visual) {
        Long first = hashes.get(photos.getFirst().object());
        List<Integer> distances = photos.stream().map(p -> {
            Long h = hashes.get(p.object());
            return first == null || h == null ? null : ImageHash.distance(first, h);
        }).toList();
        Duration span = Duration.between(photos.getFirst().captureTime(), photos.getLast().captureTime());
        return new ScanResult.BurstSet(photos.stream().map(PhotoView::of).toList(), distances, visual,
                span.toMillis() / 1000.0, photos.getFirst().cameraOrUnknown());
    }

    /** Fills {@code out} with same-size groups worth showing; returns how many were hidden as proven different. */
    private int sizeGroups(ScanJob job, List<PhotoMeta> metas, Map<PhotoObject, PhotoMeta> metaMap,
                           List<String> warnings, List<ScanResult.SizeGroup> out) {
        ScanRequest req = job.request;
        List<DuplicateFinder.SizeGroup> groups = DuplicateFinder.bySize(
                metas.stream().map(PhotoMeta::object).toList(), req.minSize());

        Map<PhotoObject, String> sha = Map.of();
        if (req.verify()) {
            List<PhotoObject> toHash = groups.stream()
                    .filter(g -> g.verdict() == DuplicateFinder.Verdict.UNVERIFIED)
                    .flatMap(g -> g.objects().stream()).toList();
            job.phase("verifying with SHA-256", toHash.size());
            sha = parallel(job, toHash, warnings, o -> sha256(store.getAll(o)));
        }

        int hidden = 0;
        for (DuplicateFinder.SizeGroup g : groups) {
            String verdict = g.verdict().name();
            String reason = switch (g.verdict()) {
                case IDENTICAL -> "ETags (MD5) match";
                case DIFFERENT -> "ETags differ";
                case UNVERIFIED -> "multipart ETag; enable SHA-256 verify to confirm";
            };
            List<String> contentIds = new ArrayList<>(g.objects().stream().map(PhotoObject::eTag).toList());
            if (g.verdict() == DuplicateFinder.Verdict.UNVERIFIED && g.objects().stream().allMatch(sha::containsKey)) {
                boolean same = g.objects().stream().map(sha::get).distinct().count() == 1;
                verdict = same ? "IDENTICAL" : "DIFFERENT";
                reason = same ? "SHA-256 match" : "SHA-256 differs";
                Map<PhotoObject, String> shaFinal = sha;
                contentIds = g.objects().stream().map(o -> "sha256:" + shaFinal.get(o)).toList();
            }
            if (verdict.equals("DIFFERENT")) {
                hidden++;
                continue;
            }
            out.add(new ScanResult.SizeGroup(g.size(), verdict, reason, g.crossBucket(), List.copyOf(g.buckets()),
                    views(g.objects(), metaMap), List.copyOf(contentIds)));
        }
        return hidden;
    }

    private static List<PhotoView> views(List<PhotoObject> objects, Map<PhotoObject, PhotoMeta> metaMap) {
        return objects.stream()
                .map(o -> metaMap.getOrDefault(o, new PhotoMeta(o, null, null, null, null, null)))
                .map(PhotoView::of)
                .toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Applies work to each object on a bounded pool; failures become warnings, nulls are skipped. */
    private <V> Map<PhotoObject, V> parallel(ScanJob job, List<PhotoObject> objects, List<String> warnings,
                                             Function<PhotoObject, V> work) {
        Map<PhotoObject, V> out = new ConcurrentHashMap<>();
        if (objects.isEmpty()) {
            return out;
        }
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (PhotoObject o : objects) {
                futures.add(pool.submit(() -> {
                    try {
                        V v = work.apply(o);
                        if (v != null) {
                            out.put(o, v);
                        }
                    } catch (RuntimeException e) {
                        warnings.add(o.uri() + ": " + StoreErrors.explain(e));
                    }
                    job.progress(done.incrementAndGet());
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scan interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    @PreDestroy
    void shutdown() {
        jobRunner.shutdownNow();
    }
}
