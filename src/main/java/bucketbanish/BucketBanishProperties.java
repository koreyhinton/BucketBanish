package bucketbanish;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Settings under {@code bucketbanish.*}. Put connection details in the gitignored
 * {@code bucketbanish.local.properties} (see the .example file), on the command line, or in env vars
 * like BUCKETBANISH_ENDPOINT.
 *
 * @param region          region (default: from AWS config/env, else us-east-1)
 * @param endpoint        custom S3-compatible endpoint URL (default: AWS); console links are AWS-only
 * @param pathStyle       force path-style addressing (implied by endpoint)
 * @param accessKeyId     access key for this connection; when set, ~/.aws credentials are not used
 * @param secretAccessKey secret for accessKeyId
 * @param sessionToken    optional session token for temporary credentials
 * @param profile         credentials profile from ~/.aws/credentials (ignored when accessKeyId is set)
 * @param localRoot       read photos from this folder instead of S3; each subfolder acts as a bucket
 * @param threads         parallel downloads per scan
 */
@ConfigurationProperties(prefix = "bucketbanish")
public record BucketBanishProperties(String region, String endpoint, boolean pathStyle,
                                     String accessKeyId, String secretAccessKey, String sessionToken,
                                     String profile, Path localRoot, Integer threads) {

    public BucketBanishProperties {
        region = blankToNull(region);
        endpoint = normalizeEndpoint(blankToNull(endpoint));
        accessKeyId = blankToNull(accessKeyId);
        secretAccessKey = blankToNull(secretAccessKey);
        sessionToken = blankToNull(sessionToken);
        profile = blankToNull(profile);
        if ((accessKeyId == null) != (secretAccessKey == null)) {
            throw new IllegalArgumentException(
                    "Set both bucketbanish.access-key-id and bucketbanish.secret-access-key, or neither");
        }
    }

    public int threadCount() {
        return threads == null || threads < 1 ? 16 : threads;
    }

    public boolean hasStaticCredentials() {
        return accessKeyId != null;
    }

    /** Key id with the middle hidden, e.g. "GL9T…1H", safe for logs and the UI. */
    public String maskedAccessKeyId() {
        if (accessKeyId == null) {
            return null;
        }
        return accessKeyId.length() <= 6 ? "…" : accessKeyId.substring(0, 4) + "…" + accessKeyId.substring(accessKeyId.length() - 2);
    }

    /** Never prints the secret or session token. */
    @Override
    public String toString() {
        return "BucketBanishProperties[region=" + region + ", endpoint=" + endpoint + ", pathStyle=" + pathStyle
                + ", accessKeyId=" + maskedAccessKeyId() + ", secretAccessKey=" + (secretAccessKey == null ? null : "****")
                + ", sessionToken=" + (sessionToken == null ? null : "****") + ", profile=" + profile
                + ", localRoot=" + localRoot + ", threads=" + threads + "]";
    }

    /** Accepts a bare host like "atl1.digitaloceanspaces.com" by assuming https://; rejects anything unusable. */
    static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String url = endpoint.contains("://") ? endpoint : "https://" + endpoint;
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getHost() == null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http"))) {
                throw new IllegalArgumentException("no host");
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bucketbanish.endpoint must be a URL like "
                    + "https://nyc3.digitaloceanspaces.com, got: " + endpoint);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
