package bucketbanish;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "dupes", mixinStandardHelpOptions = true,
        description = {
                "Find same-size objects and same filenames across buckets.",
                "Same-size groups are checked within and across buckets and labelled using ETags",
                "(or SHA-256 with --verify)."})
public class DupesCommand implements Callable<Integer> {

    @Mixin
    S3Options s3Options;

    @Parameters(arity = "1..*", paramLabel = "TARGET", description = "s3://bucket[/prefix] (one or more)")
    List<String> targets;

    @Option(names = "--min-size", defaultValue = "1024",
            description = "Ignore objects smaller than this many bytes (default: ${DEFAULT-VALUE})")
    long minSize;

    @Option(names = "--verify",
            description = "Download same-size objects whose ETags can't be compared and check SHA-256")
    boolean verify;

    @Option(names = "--show-all", description = "Also show same-size groups whose ETags prove different content")
    boolean showAll;

    @Option(names = "--ignore-case", description = "Treat IMG_1.JPG and img_1.jpg as the same filename")
    boolean ignoreCase;

    @Option(names = "--all-files", description = "Include non-photo files")
    boolean allFiles;

    @Option(names = "--plan", description = "Write all but one copy of each identical group to this plan file")
    Path plan;

    @Option(names = "--threads", defaultValue = "16", description = "Parallel downloads (default: ${DEFAULT-VALUE})")
    int threads;

    @Override
    public Integer call() {
        try (S3Photos s3 = new S3Photos(s3Options)) {
            List<PhotoObject> objects = Fetcher.list(s3, targets, allFiles);
            List<DuplicateFinder.SizeGroup> sizeGroups = DuplicateFinder.bySize(objects, minSize);

            Map<PhotoObject, String> sha = Map.of();
            if (verify) {
                List<PhotoObject> toHash = sizeGroups.stream()
                        .filter(g -> g.verdict() == DuplicateFinder.Verdict.UNVERIFIED)
                        .flatMap(g -> g.objects().stream()).toList();
                sha = Fetcher.sha256(s3, toHash, threads);
            }

            reportSizes(sizeGroups, sha);
            reportNames(DuplicateFinder.sameNameAcrossBuckets(objects, ignoreCase));
        }
        return 0;
    }

    private void reportSizes(List<DuplicateFinder.SizeGroup> groups, Map<PhotoObject, String> sha) {
        System.out.println("=== Same byte size ===");
        int shown = 0;
        int hidden = 0;
        long reclaimable = 0;
        List<String> planLines = new ArrayList<>();
        for (DuplicateFinder.SizeGroup g : groups) {
            String verdict = verdict(g, sha);
            if (verdict.startsWith("DIFFERENT") && !showAll) {
                hidden++;
                continue;
            }
            shown++;
            String scope = g.crossBucket() ? "across buckets " + g.buckets() : "within " + g.buckets().iterator().next();
            System.out.printf("%n%s x%d  %s  -> %s%n", Format.bytes(g.size()), g.objects().size(), scope, verdict);
            for (PhotoObject o : g.objects()) {
                String id = sha.containsKey(o) ? "sha:" + sha.get(o).substring(0, 12) : "etag:" + shortEtag(o);
                System.out.printf("    %-24s %s%n", id, o.uri());
            }
            if (verdict.startsWith("IDENTICAL")) {
                reclaimable += g.size() * (g.objects().size() - 1);
                planLines.add("# keeping " + g.objects().getFirst().uri());
                g.objects().stream().skip(1).forEach(o -> planLines.add(o.uri()));
            }
        }
        if (shown == 0) {
            System.out.println("(none)");
        }
        if (hidden > 0) {
            System.out.printf("%n(%d same-size groups hidden: ETags prove the content differs; --show-all to list)%n", hidden);
        }
        System.out.printf("%nIdentical copies take up %s that could be reclaimed.%n", Format.bytes(reclaimable));
        if (plan != null && !planLines.isEmpty()) {
            PlanFile.write(plan, "dupes", planLines);
            System.out.printf("Plan written to %s (review it, then run: bucketbanish banish %s)%n", plan, plan);
        }
    }

    private static String verdict(DuplicateFinder.SizeGroup g, Map<PhotoObject, String> sha) {
        DuplicateFinder.Verdict v = g.verdict();
        if (v == DuplicateFinder.Verdict.UNVERIFIED && g.objects().stream().allMatch(sha::containsKey)) {
            long distinct = g.objects().stream().map(sha::get).distinct().count();
            return distinct == 1 ? "IDENTICAL (SHA-256 match)" : "DIFFERENT (SHA-256 differs)";
        }
        return switch (v) {
            case IDENTICAL -> "IDENTICAL (ETag/MD5 match)";
            case DIFFERENT -> "DIFFERENT (ETags differ)";
            case UNVERIFIED -> "POSSIBLE duplicate (multipart ETag; use --verify)";
        };
    }

    private static void reportNames(List<DuplicateFinder.NameGroup> groups) {
        System.out.println("\n=== Same filename in multiple buckets ===");
        if (groups.isEmpty()) {
            System.out.println("(none)");
        }
        for (DuplicateFinder.NameGroup g : groups) {
            System.out.printf("%n%s  (%s)%n", g.name(), g.sameSize() ? "same size" : "sizes differ");
            for (PhotoObject o : g.objects()) {
                System.out.printf("    %10s  etag:%-12s %s%n", Format.bytes(o.size()), shortEtag(o), o.uri());
            }
        }
    }

    private static String shortEtag(PhotoObject o) {
        String e = o.eTag() == null ? "?" : o.eTag();
        return e.length() > 12 ? e.substring(0, 12) : e;
    }
}
