package bucketbanish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Runs S3 downloads in parallel with a progress counter on stderr. */
public final class Fetcher {

    /** Bytes fetched for metadata; EXIF lives at the front of JPEG/HEIC/TIFF files. */
    static final long METADATA_HEAD_BYTES = 256 * 1024;

    private Fetcher() {
    }

    public static List<PhotoObject> list(S3Photos s3, List<String> targets, boolean allFiles) {
        List<PhotoObject> all = new ArrayList<>();
        for (String t : targets) {
            Target target = Target.parse(t);
            List<PhotoObject> found = s3.listPhotos(target.bucket(), target.prefix(), allFiles);
            System.err.printf("Listed %d %s in s3://%s/%s%n", found.size(), allFiles ? "objects" : "photos",
                    target.bucket(), target.prefix());
            all.addAll(found);
        }
        return all;
    }

    public static List<PhotoMeta> metadata(S3Photos s3, List<PhotoObject> objects, int threads) {
        Map<PhotoObject, PhotoMeta> result = parallel("metadata", objects, threads, o -> {
            PhotoMeta meta = MetadataReader.read(o, s3.getHead(o, METADATA_HEAD_BYTES));
            if (!meta.hasCaptureTime() && o.size() > METADATA_HEAD_BYTES) {
                meta = MetadataReader.read(o, s3.getAll(o)); // EXIF wasn't in the first chunk
            }
            return meta;
        });
        return objects.stream().map(result::get).filter(java.util.Objects::nonNull).toList();
    }

    /** dHash for each object; objects that can't be decoded are absent from the map. */
    public static Map<PhotoObject, Long> hashes(S3Photos s3, List<PhotoObject> objects, int threads) {
        return parallel("hashing", objects, threads, o -> ImageHash.dHash(s3.getAll(o)));
    }

    public static Map<PhotoObject, String> sha256(S3Photos s3, List<PhotoObject> objects, int threads) {
        return parallel("sha-256", objects, threads, o -> {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(s3.getAll(o));
                return java.util.HexFormat.of().formatHex(digest);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static <V> Map<PhotoObject, V> parallel(String label, List<PhotoObject> objects, int threads,
                                                    Function<PhotoObject, V> work) {
        Map<PhotoObject, V> out = new ConcurrentHashMap<>();
        if (objects.isEmpty()) {
            return out;
        }
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
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
                        System.err.printf("%n  %s failed for %s: %s%n", label, o.uri(), e.getMessage());
                    }
                    int n = done.incrementAndGet();
                    if (n % 10 == 0 || n == objects.size()) {
                        System.err.printf("\r  %s %d/%d", label, n, objects.size());
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            System.err.println();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
        return out;
    }
}
