package bucketbanish.store;

import bucketbanish.BucketBanishProperties;
import bucketbanish.analysis.PhotoObject;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S3 access via the AWS SDK (standard credential chain).
 * Read-only by design: deletion is left to the user in their S3 provider's web console.
 */
public class S3PhotoStore implements PhotoStore {

    private final S3Client s3;
    private final boolean customEndpoint;
    private final String description;

    public S3PhotoStore(BucketBanishProperties props) {
        Region region = resolveRegion(props.region());
        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .crossRegionAccessEnabled(true);
        String credentials;
        if (props.hasStaticCredentials()) {
            // Explicit keys from config win, so the connection never depends on whoever's ~/.aws is active.
            builder.credentialsProvider(StaticCredentialsProvider.create(props.sessionToken() == null
                    ? AwsBasicCredentials.create(props.accessKeyId(), props.secretAccessKey())
                    : AwsSessionCredentials.create(props.accessKeyId(), props.secretAccessKey(), props.sessionToken())));
            credentials = "access key " + props.maskedAccessKeyId() + " from BucketBanish config";
        } else if (props.profile() != null) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(props.profile()));
            credentials = "AWS profile '" + props.profile() + "'";
        } else {
            credentials = "default AWS credential chain (env vars / ~/.aws)";
        }
        customEndpoint = props.endpoint() != null;
        if (customEndpoint) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        if (props.pathStyle() || customEndpoint) {
            builder.forcePathStyle(true);
        }
        this.s3 = builder.build();
        String where = customEndpoint ? URI.create(props.endpoint()).getHost() : "AWS S3";
        this.description = where + " (" + region.id() + "), " + credentials;
    }

    private static Region resolveRegion(String region) {
        if (region != null) {
            return Region.of(region);
        }
        try {
            return new DefaultAwsRegionProviderChain().getRegion();
        } catch (RuntimeException e) {
            return Region.US_EAST_1;
        }
    }

    @Override
    public List<PhotoObject> listPhotos(String bucket, String prefix) {
        List<PhotoObject> out = new ArrayList<>();
        for (S3Object o : s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents()) {
            if (o.key().endsWith("/") || !PhotoStore.isPhoto(o.key())) {
                continue;
            }
            out.add(new PhotoObject(bucket, o.key(), o.size(), stripQuotes(o.eTag()), o.lastModified()));
        }
        return out;
    }

    @Override
    public byte[] getHead(PhotoObject p, long length) {
        if (p.size() <= length) {
            return getAll(p);
        }
        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                r -> r.bucket(p.bucket()).key(p.key()).range("bytes=0-" + (length - 1)));
        return bytes.asByteArray();
    }

    @Override
    public byte[] getAll(PhotoObject p) {
        return s3.getObjectAsBytes(r -> r.bucket(p.bucket()).key(p.key())).asByteArray();
    }

    @Override
    public Optional<PhotoObject> head(String bucket, String key) {
        try {
            HeadObjectResponse h = s3.headObject(r -> r.bucket(bucket).key(key));
            return Optional.of(new PhotoObject(bucket, key, h.contentLength(), stripQuotes(h.eTag()), h.lastModified()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public String bucketRegion(String bucket) {
        try {
            String loc = s3.getBucketLocation(r -> r.bucket(bucket)).locationConstraintAsString();
            if (loc == null || loc.isEmpty()) {
                return "us-east-1"; // S3 reports the original region as an empty constraint
            }
            return loc.equals("EU") ? "eu-west-1" : loc;
        } catch (S3Exception e) {
            return null; // e.g. missing s3:GetBucketLocation permission
        }
    }

    private static String stripQuotes(String s) {
        return s == null ? null : s.replace("\"", "");
    }

    @Override
    public boolean consoleLinks() {
        return !customEndpoint;
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public void close() {
        s3.close();
    }
}
