package bucketbanish.scan;

import java.time.Instant;

/** Mutable state of one running scan, updated by the worker thread and read by pollers. */
final class ScanJob {

    final String id;
    final ScanRequest request;
    final Instant startedAt = Instant.now();
    private volatile String status = "RUNNING";
    private volatile String phase = "starting";
    private volatile int done;
    private volatile int total;
    private volatile String error;
    private volatile ScanResult result;

    ScanJob(String id, ScanRequest request) {
        this.id = id;
        this.request = request;
    }

    void phase(String phase, int total) {
        this.phase = phase;
        this.total = total;
        this.done = 0;
    }

    void progress(int done) {
        this.done = done;
    }

    void finish(ScanResult result) {
        this.result = result;
        this.phase = "done";
        this.status = "DONE";
    }

    void fail(String error) {
        this.error = error;
        this.status = "FAILED";
    }

    ScanStatus snapshot() {
        return new ScanStatus(id, status, phase, done, total, error, startedAt, result);
    }
}
