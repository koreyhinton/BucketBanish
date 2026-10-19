package bucketbanish.report;

import java.time.Instant;
import java.util.List;

/**
 * Where to find each photo the user chose to remove, so they can delete it themselves in their
 * S3 provider's web console. Console URLs are null when not on AWS.
 */
public record RemovalReport(List<BucketEntry> buckets, int fileCount, int missingCount, long totalBytes,
                            String store, Instant generatedAt) {

    public record BucketEntry(String bucket, String region, String consoleUrl, List<FolderEntry> folders) {
    }

    public record FolderEntry(String folder, String consoleUrl, List<FileEntry> files) {
    }

    /** found=false means the object is already gone (size/uploaded/eTag are then null). */
    public record FileEntry(String fileName, String key, String uri, boolean found, Long size, Instant uploaded,
                            String eTag, String consoleUrl) {
    }
}
