package bucketbanish.report;

import bucketbanish.analysis.PhotoObject;
import bucketbanish.scan.ScanService;
import bucketbanish.store.PhotoStore;
import bucketbanish.store.Target;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Builds the removal report: current details and console locations for each chosen photo. Never deletes. */
@Service
public class ReportService {

    private static final int MAX_FILES = 5000;

    private final PhotoStore store;
    private final ScanService scans;

    public ReportService(PhotoStore store, ScanService scans) {
        this.store = store;
        this.scans = scans;
    }

    public RemovalReport build(List<String> uris) {
        List<String> distinct = uris == null ? List.of() : uris.stream().distinct().toList();
        if (distinct.size() > MAX_FILES) {
            throw new IllegalArgumentException("At most " + MAX_FILES + " files per report");
        }
        for (String uri : distinct) {
            if (scans.known(uri).isEmpty()) {
                throw new IllegalArgumentException("Not part of any scan: " + uri);
            }
        }
        // bucket -> folder -> keys, sorted so the report follows the console's folder navigation
        Map<String, Map<String, List<String>>> byBucket = distinct.stream()
                .map(Target::parseObjectUri)
                .collect(Collectors.groupingBy(Target::bucket, TreeMap::new,
                        Collectors.groupingBy(t -> folderOf(t.prefix()), TreeMap::new,
                                Collectors.mapping(Target::prefix, Collectors.toList()))));

        boolean links = store.consoleLinks();
        int found = 0;
        int missing = 0;
        long bytes = 0;
        List<RemovalReport.BucketEntry> buckets = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> b : byBucket.entrySet()) {
            String bucket = b.getKey();
            String region = store.bucketRegion(bucket);
            boolean bucketLinks = links && region != null;
            List<RemovalReport.FolderEntry> folders = new ArrayList<>();
            for (Map.Entry<String, List<String>> f : b.getValue().entrySet()) {
                List<RemovalReport.FileEntry> files = new ArrayList<>();
                for (String key : f.getValue().stream().sorted().toList()) {
                    Optional<PhotoObject> obj = store.head(bucket, key);
                    String url = bucketLinks ? objectUrl(bucket, region, key) : null;
                    if (obj.isPresent()) {
                        found++;
                        bytes += obj.get().size();
                    } else {
                        missing++;
                    }
                    files.add(new RemovalReport.FileEntry(fileName(key), key, "s3://" + bucket + "/" + key,
                            obj.isPresent(), obj.map(PhotoObject::size).orElse(null),
                            obj.map(PhotoObject::lastModified).orElse(null),
                            obj.map(PhotoObject::eTag).orElse(null), url));
                }
                String folderUrl = bucketLinks ? bucketUrl(bucket, region, f.getKey()) : null;
                folders.add(new RemovalReport.FolderEntry(f.getKey(), folderUrl, files));
            }
            buckets.add(new RemovalReport.BucketEntry(bucket, region,
                    bucketLinks ? bucketUrl(bucket, region, "") : null, folders));
        }
        return new RemovalReport(buckets, found, missing, bytes, store.describe(), Instant.now());
    }

    /** Spreadsheet checklist with one row per file. */
    public String csv(RemovalReport report) {
        StringBuilder sb = new StringBuilder("bucket,region,folder,filename,size_bytes,uploaded,etag,s3_uri,console_url\n");
        for (RemovalReport.BucketEntry b : report.buckets()) {
            for (RemovalReport.FolderEntry f : b.folders()) {
                for (RemovalReport.FileEntry file : f.files()) {
                    sb.append(String.join(",", csvField(b.bucket()), csvField(b.region()), csvField(f.folder()),
                            csvField(file.fileName()), file.size() == null ? "" : file.size().toString(),
                            file.found() ? file.uploaded().toString() : "NOT FOUND",
                            csvField(file.eTag()), csvField(file.uri()), csvField(file.consoleUrl())));
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
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

    /** Quotes when needed, and defuses values a spreadsheet would treat as formulas. */
    static String csvField(String s) {
        if (s == null) {
            return "";
        }
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
