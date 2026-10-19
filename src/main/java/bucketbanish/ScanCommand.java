package bucketbanish;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "scan", mixinStandardHelpOptions = true,
        description = "List photos with their original capture date, camera and dimensions.")
public class ScanCommand implements Callable<Integer> {

    @Mixin
    S3Options s3Options;

    @Parameters(arity = "1..*", paramLabel = "TARGET", description = "s3://bucket[/prefix] (one or more)")
    List<String> targets;

    @Option(names = "--csv", description = "Print CSV instead of a table")
    boolean csv;

    @Option(names = "--threads", defaultValue = "16", description = "Parallel downloads (default: ${DEFAULT-VALUE})")
    int threads;

    @Override
    public Integer call() {
        try (S3Photos s3 = new S3Photos(s3Options)) {
            List<PhotoMeta> metas = Fetcher.metadata(s3, Fetcher.list(s3, targets, false), threads).stream()
                    .sorted(Comparator.comparing(PhotoMeta::captureTime, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            if (csv) {
                System.out.println("uri,size,capture_time,camera,width,height,etag");
                for (PhotoMeta m : metas) {
                    System.out.printf("%s,%d,%s,%s,%s,%s,%s%n", csvField(m.object().uri()), m.object().size(),
                            m.captureTime() == null ? "" : m.captureTime(), csvField(m.camera()),
                            nullToEmpty(m.width()), nullToEmpty(m.height()), m.object().eTag());
                }
            } else {
                for (PhotoMeta m : metas) {
                    String dims = m.width() == null ? "" : m.width() + "x" + m.height();
                    System.out.printf("%s  %10s  %-11s  %-24s  %s%n", Format.time(m.captureTime()),
                            Format.bytes(m.object().size()), dims, m.cameraOrUnknown(), m.object().uri());
                }
                long undated = metas.stream().filter(m -> !m.hasCaptureTime()).count();
                System.out.printf("%n%d photos, %d without a capture date%n", metas.size(), undated);
            }
        }
        return 0;
    }

    private static String nullToEmpty(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String csvField(String s) {
        if (s == null) {
            return "";
        }
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
