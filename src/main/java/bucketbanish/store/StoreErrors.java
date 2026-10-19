package bucketbanish.store;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.UnknownHostException;

/** Turns S3/SDK failures into messages that say what to fix, keeping the provider's own text at the end. */
public final class StoreErrors {

    private static final String CONFIG = "bucketbanish.local.properties";

    private StoreErrors() {
    }

    public static String explain(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof S3Exception s3) {
                return explainS3(s3);
            }
            if (c instanceof UnknownHostException) {
                return "Can't reach the storage endpoint (unknown host). Check bucketbanish.endpoint in " + CONFIG
                        + ". (" + c.getMessage() + ")";
            }
            if (c instanceof SdkClientException sdk && String.valueOf(sdk.getMessage()).contains("Unable to load credentials")) {
                return "No credentials found. Set bucketbanish.access-key-id and bucketbanish.secret-access-key in "
                        + CONFIG + " (or configure ~/.aws/credentials).";
            }
        }
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static String explainS3(S3Exception e) {
        String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
        String original = e.awsErrorDetails() != null && e.awsErrorDetails().errorMessage() != null
                ? e.awsErrorDetails().errorMessage() : e.getMessage();
        String hint = switch (code == null ? "" : code) {
            case "InvalidAccessKeyId" -> "The storage provider doesn't recognise this access key. If the key is from a "
                    + "non-AWS provider (Wasabi, Backblaze, DigitalOcean, R2...), set bucketbanish.endpoint to that "
                    + "provider's S3 endpoint; otherwise check bucketbanish.access-key-id in " + CONFIG + ".";
            case "SignatureDoesNotMatch" -> "The secret key doesn't match the access key (or the region/endpoint is wrong). "
                    + "Check bucketbanish.secret-access-key and bucketbanish.region in " + CONFIG + ".";
            case "AccessDenied", "AllAccessDisabled" -> "These credentials aren't allowed to read this bucket. "
                    + "They need s3:ListBucket and s3:GetObject.";
            case "NoSuchBucket" -> "That bucket doesn't exist for this endpoint/account. Check the bucket name and "
                    + "bucketbanish.endpoint.";
            case "PermanentRedirect", "AuthorizationHeaderMalformed", "IllegalLocationConstraintException" ->
                    "The bucket is in a different region than configured. Set bucketbanish.region (and, for non-AWS "
                            + "providers, the matching regional bucketbanish.endpoint).";
            case "ExpiredToken", "TokenRefreshRequired" -> "The session token has expired. Update "
                    + "bucketbanish.session-token, or remove it if you're using long-term keys.";
            default -> null;
        };
        return hint == null ? original : hint + " (" + code + ": " + original + ")";
    }
}
