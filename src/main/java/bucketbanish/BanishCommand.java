package bucketbanish;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Reports where each object in a plan lives so the user can delete it themselves in their
 * S3 provider's web console. BucketBanish never deletes anything.
 */
@Command(name = "banish", mixinStandardHelpOptions = true,
        description = {
                "Report where to find each planned file so you can delete it in your S3 web console.",
                "Shows bucket, region, folder, filename, size, upload date, ETag and (AWS) console links.",
                "Nothing is deleted by this tool."})
public class BanishCommand implements Callable<Integer> {

    private static final DateTimeFormatter UPLOADED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    @Mixin
    S3Options s3Options;

    @Parameters(index = "0", paramLabel = "PLAN", description = "Plan file from 'bursts -i' or 'dupes --plan'")
    Path plan;

    @Option(names = "--csv", description = "Print a CSV checklist instead (bucket,region,folder,filename,...)")
    boolean csv;

    @Override
    public Integer call() {
        // bucket -> folder -> keys, sorted so the report follows the console's folder navigation
        Map<String, Map<String, List<String>>> byBucket = PlanFile.read(plan).stream()
                .collect(Collectors.groupingBy(Target::bucket, TreeMap::new,
                        Collectors.groupingBy(t -> folderOf(t.prefix()), TreeMap::new,
                                Collectors.mapping(Target::prefix, Collectors.toList()))));
        if (byBucket.isEmpty()) {
            System.out.println("Plan is empty.");
            return 0;
        }

        boolean aws = s3Options.endpoint == null;
        int found = 0;
        int missing = 0;
        long bytes = 0;
        if (csv) {
            System.out.println("bucket,region,folder,filename,size_bytes,uploaded,etag,s3_uri,console_url");
        }
        try (S3Photos s3 = new S3Photos(s3Options)) {
            for (Map.Entry<String, Map<String, List<String>>> b : byBucket.entrySet()) {
                String bucket = b.getKey();
                String region = s3.bucketRegion(bucket);
                if (!csv) {
                    System.out.printf("%nBucket: %s   (region: %s)%n", bucket, region == null ? "unknown" : region);
                    if (aws && region != null) {
                        System.out.println("  Open:   " + bucketUrl(bucket, region, ""));
                    }
                }
                for (Map.Entry<String, List<String>> f : b.getValue().entrySet()) {
                    String folder = f.getKey();
                    if (!csv) {
                        System.out.printf("%n  Folder: %s%n", folder.isEmpty() ? "(bucket root)" : folder);
                        if (aws && region != null && !folder.isEmpty()) {
                            System.out.println("  Open:   " + bucketUrl(bucket, region, folder));
                        }
                    }
                    for (String key : f.getValue().stream().sorted().toList()) {
                        Optional<PhotoObject> obj = s3.head(bucket, key);
                        String link = aws && region != null ? objectUrl(bucket, region, key) : "";
                        if (obj.isEmpty()) {
                            missing++;
                        } else {
                            found++;
                            bytes += obj.get().size();
                        }
                        if (csv) {
                            System.out.println(String.join(",", csvField(bucket), csvField(region), csvField(folder),
                                    csvField(fileName(key)),
                                    obj.map(o -> Long.toString(o.size())).orElse(""),
                                    obj.map(o -> UPLOADED.format(o.lastModified())).orElse("NOT FOUND"),
                                    obj.map(PhotoObject::eTag).orElse(""),
                                    csvField("s3://" + bucket + "/" + key), csvField(link)));
                        } else if (obj.isEmpty()) {
                            System.out.printf("    - %s   NOT FOUND (already removed?)%n", fileName(key));
                        } else {
                            PhotoObject o = obj.get();
                            System.out.printf("    - %s   %s   uploaded %s   etag %s%n", fileName(key),
                                    Format.bytes(o.size()), UPLOADED.format(o.lastModified()), o.eTag());
                            if (!link.isEmpty()) {
                                System.out.println("      " + link);
                            }
                        }
                    }
                }
            }
        }

        if (!csv) {
            System.out.printf("%n%d files to remove (%s) across %d bucket(s)%s.%n", found, Format.bytes(bytes),
                    byBucket.size(), missing > 0 ? "; " + missing + " not found" : "");
            System.out.println("To delete: open each folder link, tick the listed files, choose Delete.");
            System.out.println("Tip: the console search box filters a folder by filename prefix.");
            System.out.println("Note: on versioned buckets, deleting adds a delete marker; old versions remain"
                    + " until removed under 'Show versions' or by a lifecycle rule.");
        }
        return 0;
    }

    static String folderOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? "" : key.substring(0, slash + 1);
    }

    static String fileName(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    static String bucketUrl(String bucket, String region, String folder) {
        return "https://s3.console.aws.amazon.com/s3/buckets/" + bucket + "?region=" + region
                + (folder.isEmpty() ? "" : "&prefix=" + encode(folder));
    }

    static String objectUrl(String bucket, String region, String key) {
        return "https://s3.console.aws.amazon.com/s3/object/" + bucket + "?region=" + region
                + "&prefix=" + encode(key);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String csvField(String s) {
        if (s == null) {
            return "";
        }
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
