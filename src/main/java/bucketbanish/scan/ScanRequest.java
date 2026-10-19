package bucketbanish.scan;

import bucketbanish.store.Target;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What to scan and how. Null fields take defaults.
 *
 * @param targets    "s3://bucket[/prefix]" entries
 * @param gapSeconds max seconds between consecutive shots in a burst
 * @param threshold  max dHash distance (0-64) to call two shots similar
 * @param anyCamera  group shots from different cameras into the same burst
 * @param visual     download burst members and compare them visually
 * @param minSize    ignore objects smaller than this many bytes for same-size groups
 * @param verify     SHA-256 same-size objects whose ETags can't be compared
 * @param ignoreCase treat IMG_1.JPG and img_1.jpg as the same filename
 */
public record ScanRequest(List<String> targets, Double gapSeconds, Integer threshold, Boolean anyCamera,
                          Boolean visual, Long minSize, Boolean verify, Boolean ignoreCase) {

    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

    public ScanRequest {
        targets = targets == null ? List.of()
                : targets.stream().map(String::strip).filter(t -> !t.isEmpty()).distinct().toList();
        gapSeconds = gapSeconds == null ? 2.0 : gapSeconds;
        threshold = threshold == null ? 10 : threshold;
        anyCamera = anyCamera != null && anyCamera;
        visual = visual == null || visual;
        minSize = minSize == null ? 1024L : minSize;
        verify = verify != null && verify;
        ignoreCase = ignoreCase != null && ignoreCase;
    }

    /** Throws IllegalArgumentException with a user-facing message if the request can't run. */
    public List<Target> validate() {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one bucket, e.g. s3://my-photos");
        }
        if (targets.size() > 20) {
            throw new IllegalArgumentException("At most 20 buckets/prefixes per scan");
        }
        if (gapSeconds <= 0 || gapSeconds > 3600) {
            throw new IllegalArgumentException("Burst gap must be between 0 and 3600 seconds");
        }
        if (threshold < 0 || threshold > 64) {
            throw new IllegalArgumentException("Similarity threshold must be 0-64");
        }
        if (minSize < 0) {
            throw new IllegalArgumentException("Minimum size can't be negative");
        }
        return targets.stream().map(t -> {
            Target target = Target.parse(t);
            if (!BUCKET.matcher(target.bucket()).matches() || target.prefix().contains("..")) {
                throw new IllegalArgumentException("Not a valid bucket/prefix: " + t);
            }
            return target;
        }).toList();
    }
}
