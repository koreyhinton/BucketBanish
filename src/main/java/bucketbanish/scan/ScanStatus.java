package bucketbanish.scan;

import java.time.Instant;

/** Snapshot of a scan job for polling. result is set once status is DONE. */
public record ScanStatus(String id, String status, String phase, int done, int total, String error,
                         Instant startedAt, ScanResult result) {
}
