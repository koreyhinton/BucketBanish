package bucketbanish.analysis;

import java.time.Instant;

/** One object in S3, as returned by ListObjectsV2. */
public record PhotoObject(String bucket, String key, long size, String eTag, Instant lastModified) {

    public String uri() {
        return "s3://" + bucket + "/" + key;
    }

    /** Filename portion of the key (after the last '/'). */
    public String fileName() {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /**
     * S3 ETags are the MD5 of the content only for single-part uploads.
     * Multipart ETags look like "abc123...-5" and can't be compared across differently-chunked uploads.
     */
    public boolean hasContentEtag() {
        return eTag != null && !eTag.contains("-");
    }
}
