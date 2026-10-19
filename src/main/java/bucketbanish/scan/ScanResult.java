package bucketbanish.scan;

import java.time.Instant;
import java.util.List;

/** Everything a finished scan found. Purely a report: nothing here changes any bucket. */
public record ScanResult(
        List<String> targets,
        int photoCount,
        int undatedCount,
        List<BurstSet> bursts,
        List<SizeGroup> sizeGroups,
        int hiddenSizeGroups,
        List<NameGroup> nameGroups,
        long identicalBytes,
        List<String> warnings,
        Instant finishedAt) {

    /**
     * Similar photos from one burst. distances[i] is the dHash distance from photo i to photo 0
     * (null when not compared visually).
     */
    public record BurstSet(List<PhotoView> photos, List<Integer> distances, boolean visuallyCompared,
                           double spanSeconds, String camera) {
    }

    /**
     * Objects with the same byte size. verdict is IDENTICAL, DIFFERENT or UNVERIFIED; contentIds[i] is the
     * SHA-256 (with verify) or ETag used to decide.
     */
    public record SizeGroup(long size, String verdict, String verdictReason, boolean crossBucket,
                            List<String> buckets, List<PhotoView> photos, List<String> contentIds) {
    }

    public record NameGroup(String name, boolean sameSize, List<PhotoView> photos) {
    }
}
