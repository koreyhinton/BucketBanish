package bucketbanish.web;

import bucketbanish.analysis.PhotoMeta;
import bucketbanish.report.RemovalReport;
import bucketbanish.report.ReportService;
import bucketbanish.scan.ScanRequest;
import bucketbanish.scan.ScanService;
import bucketbanish.scan.ScanStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** JSON API used by the browser UI. Every endpoint is read-only with respect to the buckets. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ScanService scans;
    private final ReportService reports;
    private final ThumbnailService thumbnails;

    public ApiController(ScanService scans, ReportService reports, ThumbnailService thumbnails) {
        this.scans = scans;
        this.reports = reports;
        this.thumbnails = thumbnails;
    }

    public record ReportRequest(List<String> uris) {
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of("store", scans.storeDescription(), "consoleLinks", scans.consoleLinks(), "readOnly", true);
    }

    @PostMapping("/scans")
    public ResponseEntity<ScanStatus> startScan(@RequestBody ScanRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(scans.start(request));
    }

    @GetMapping("/scans/{id}")
    public ResponseEntity<ScanStatus> scan(@PathVariable String id) {
        return ResponseEntity.of(scans.status(id));
    }

    @GetMapping("/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@RequestParam String uri, @RequestParam(defaultValue = "320") int size) {
        PhotoMeta meta = scans.known(uri).orElse(null);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }
        int maxSide = Math.clamp(size, 64, 1600);
        return thumbnails.thumbnail(meta, maxSide)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build());
    }

    @PostMapping("/report")
    public RemovalReport report(@RequestBody ReportRequest request) {
        return reports.build(request.uris());
    }

    @PostMapping(value = "/report.csv", produces = "text/csv")
    public ResponseEntity<String> reportCsv(@RequestBody ReportRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bucketbanish-removal-report.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(reports.csv(reports.build(request.uris())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("error", e.getMessage()));
    }
}
