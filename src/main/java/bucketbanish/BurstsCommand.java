package bucketbanish;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;

@Command(name = "bursts", mixinStandardHelpOptions = true,
        description = {
                "Find burst shots and pick near-duplicates to drop.",
                "Groups photos from the same camera taken within --gap seconds of each other, then",
                "compares them visually (dHash) so only genuinely similar shots are shown."})
public class BurstsCommand implements Callable<Integer> {

    @Mixin
    S3Options s3Options;

    @Parameters(arity = "1..*", paramLabel = "TARGET", description = "s3://bucket[/prefix] (one or more)")
    List<String> targets;

    @Option(names = "--gap", defaultValue = "2.0",
            description = "Max seconds between consecutive shots in a burst (default: ${DEFAULT-VALUE})")
    double gapSeconds;

    @Option(names = "--threshold", defaultValue = "10",
            description = "Max dHash distance (0-64) to call two shots similar (default: ${DEFAULT-VALUE})")
    int threshold;

    @Option(names = "--any-camera", description = "Group shots from different cameras together")
    boolean anyCamera;

    @Option(names = "--no-visual", description = "Skip downloading/hashing; group by capture time only")
    boolean noVisual;

    @Option(names = {"-i", "--interactive"}, description = "Review each burst and pick photos to drop")
    boolean interactive;

    @Option(names = "--plan", defaultValue = "banish-plan.txt",
            description = "Where -i writes the chosen photos (default: ${DEFAULT-VALUE})")
    Path plan;

    @Option(names = "--threads", defaultValue = "16", description = "Parallel downloads (default: ${DEFAULT-VALUE})")
    int threads;

    /** A set of photos shown to the user together. */
    private record ReviewSet(List<PhotoMeta> photos, boolean visuallyCompared) {
    }

    @Override
    public Integer call() throws IOException {
        try (S3Photos s3 = new S3Photos(s3Options)) {
            List<PhotoMeta> metas = Fetcher.metadata(s3, Fetcher.list(s3, targets, false), threads);
            long undated = metas.stream().filter(m -> !m.hasCaptureTime()).count();
            if (undated > 0) {
                System.err.printf("Skipping %d photos with no EXIF capture date%n", undated);
            }

            Duration gap = Duration.ofMillis(Math.round(gapSeconds * 1000));
            List<BurstDetector.Burst> bursts = BurstDetector.groupByTime(metas, gap, !anyCamera);
            System.err.printf("Found %d time-based bursts%n", bursts.size());

            Map<PhotoObject, Long> hashes = Map.of();
            if (!noVisual && !bursts.isEmpty()) {
                List<PhotoObject> inBursts = bursts.stream()
                        .flatMap(b -> b.photos().stream()).map(PhotoMeta::object).toList();
                hashes = Fetcher.hashes(s3, inBursts, threads);
            }

            List<ReviewSet> sets = reviewSets(bursts, hashes);
            if (sets.isEmpty()) {
                System.out.println("No similar burst photos found.");
                return 0;
            }
            return interactive ? review(sets, hashes) : report(sets, hashes);
        }
    }

    private List<ReviewSet> reviewSets(List<BurstDetector.Burst> bursts, Map<PhotoObject, Long> hashes) {
        List<ReviewSet> sets = new ArrayList<>();
        for (BurstDetector.Burst b : bursts) {
            if (noVisual || BurstDetector.anyUnhashed(b, hashes)) {
                sets.add(new ReviewSet(b.photos(), false)); // e.g. HEIC/RAW: Java can't decode, time only
            } else {
                BurstDetector.similarClusters(b, hashes, threshold)
                        .forEach(c -> sets.add(new ReviewSet(c, true)));
            }
        }
        return sets;
    }

    private int report(List<ReviewSet> sets, Map<PhotoObject, Long> hashes) {
        long reclaimable = 0;
        for (int i = 0; i < sets.size(); i++) {
            print(i + 1, sets.size(), sets.get(i), hashes);
            PhotoMeta keep = suggestedKeep(sets.get(i));
            System.out.printf("  suggestion: keep [%d] (largest file)%n%n", sets.get(i).photos().indexOf(keep) + 1);
            reclaimable += sets.get(i).photos().stream().filter(p -> p != keep).mapToLong(p -> p.object().size()).sum();
        }
        System.out.printf("%d similar sets; keeping one of each would free %s. Re-run with -i to choose.%n",
                sets.size(), Format.bytes(reclaimable));
        return 0;
    }

    private int review(List<ReviewSet> sets, Map<PhotoObject, Long> hashes) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        List<String> planLines = new ArrayList<>();
        long freed = 0;
        int dropped = 0;
        System.out.println("For each set: Enter = keep all, 2,4 or 2-4 = drop those, k3 = keep only #3,");
        System.out.println("              s = keep largest & drop rest, q = stop reviewing (plan is still saved)");
        outer:
        for (int i = 0; i < sets.size(); i++) {
            ReviewSet set = sets.get(i);
            print(i + 1, sets.size(), set, hashes);
            while (true) {
                System.out.print("  drop> ");
                System.out.flush();
                String line = in.readLine();
                if (line == null || line.strip().equalsIgnoreCase("q")) {
                    break outer;
                }
                TreeSet<Integer> drops;
                try {
                    drops = parseChoice(line.strip(), set);
                } catch (IllegalArgumentException e) {
                    System.out.println("  " + e.getMessage());
                    continue;
                }
                if (!drops.isEmpty()) {
                    planLines.add("# set " + (i + 1) + ": keeping " + set.photos().stream()
                            .filter(p -> !drops.contains(set.photos().indexOf(p) + 1))
                            .map(p -> p.object().uri()).toList());
                }
                for (int d : drops) {
                    PhotoObject o = set.photos().get(d - 1).object();
                    planLines.add(o.uri());
                    freed += o.size();
                    dropped++;
                }
                System.out.println();
                break;
            }
        }
        if (dropped == 0) {
            System.out.println("Nothing marked to banish.");
            return 0;
        }
        PlanFile.write(plan, "bursts", planLines);
        System.out.printf("%nMarked %d photos (%s). Plan written to %s%n", dropped, Format.bytes(freed), plan);
        System.out.printf("Run 'bucketbanish banish %s' for the locations to delete them from your S3 console.%n", plan);
        return 0;
    }

    /** Parses the user's answer into 1-based indices to drop. */
    static TreeSet<Integer> parseChoice(String answer, ReviewSet set) {
        int n = set.photos().size();
        TreeSet<Integer> drops = new TreeSet<>();
        if (answer.isEmpty()) {
            return drops;
        }
        if (answer.equalsIgnoreCase("s")) {
            answer = "k" + (set.photos().indexOf(suggestedKeep(set)) + 1);
        }
        if (answer.toLowerCase().startsWith("k")) {
            int keep = parseIndex(answer.substring(1).strip(), n);
            for (int i = 1; i <= n; i++) {
                if (i != keep) {
                    drops.add(i);
                }
            }
            return drops;
        }
        for (String part : answer.split("[,\\s]+")) {
            if (part.contains("-")) {
                String[] ends = part.split("-", 2);
                int from = parseIndex(ends[0], n);
                int to = parseIndex(ends[1], n);
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) {
                    drops.add(i);
                }
            } else {
                drops.add(parseIndex(part, n));
            }
        }
        if (drops.size() == n) {
            throw new IllegalArgumentException("That drops every photo in the set; use k<N> to keep one.");
        }
        return drops;
    }

    private static int parseIndex(String s, int n) {
        try {
            int i = Integer.parseInt(s.strip());
            if (i >= 1 && i <= n) {
                return i;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new IllegalArgumentException("Expected a number from 1 to " + n + ", got '" + s + "'");
    }

    private static PhotoMeta suggestedKeep(ReviewSet set) {
        return set.photos().stream().max(Comparator.comparingLong(p -> p.object().size())).orElseThrow();
    }

    private static void print(int number, int total, ReviewSet set, Map<PhotoObject, Long> hashes) {
        List<PhotoMeta> photos = set.photos();
        Duration span = Duration.between(photos.getFirst().captureTime(), photos.getLast().captureTime());
        System.out.printf("Set %d/%d  %d photos over %s  %s%s%n", number, total, photos.size(),
                Format.seconds(span), photos.getFirst().cameraOrUnknown(),
                set.visuallyCompared() ? "" : "  (time-based only, not compared visually)");
        Long first = hashes.get(photos.getFirst().object());
        for (int i = 0; i < photos.size(); i++) {
            PhotoMeta p = photos.get(i);
            Long h = hashes.get(p.object());
            String similarity = first == null || h == null ? "" : "diff " + ImageHash.distance(first, h);
            System.out.printf("  [%d] %s  %10s  %-8s  %s%n", i + 1, Format.time(p.captureTime()),
                    Format.bytes(p.object().size()), similarity, p.object().uri());
        }
    }
}
