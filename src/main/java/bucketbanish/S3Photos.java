package bucketbanish;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Thin wrapper around the S3 client with the handful of operations BucketBanish needs.
 * Read-only by design: deletion is left to the user in their S3 provider's web console.
 */
public class S3Photos implements AutoCloseable {

    public static final Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "heic", "heif", "webp",
            "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2");

    private final S3Client s3;

    public S3Photos(S3Options opts) {
        S3ClientBuilder builder = S3Client.builder()
                .region(resolveRegion(opts.region))
                .crossRegionAccessEnabled(true);
        if (opts.profile != null) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(opts.profile));
        }
        if (opts.endpoint != null) {
            builder.endpointOverride(URI.create(opts.endpoint));
        }
        if (opts.pathStyle || opts.endpoint != null) {
            builder.forcePathStyle(true);
        }
        this.s3 = builder.build();
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

    public static boolean isPhoto(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 && PHOTO_EXTENSIONS.contains(key.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Lists every photo-looking object under the prefix (handles pagination). */
    public List<PhotoObject> listPhotos(String bucket, String prefix, boolean allFiles) {
        List<PhotoObject> out = new ArrayList<>();
        for (S3Object o : s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents()) {
            if (o.key().endsWith("/") || (!allFiles && !isPhoto(o.key()))) {
                continue;
            }
            out.add(new PhotoObject(bucket, o.key(), o.size(), stripQuotes(o.eTag()), o.lastModified()));
        }
        return out;
    }

    /** Downloads the first {@code length} bytes; enough for EXIF in nearly all JPEGs. */
    public byte[] getHead(PhotoObject p, long length) {
        if (p.size() <= length) {
            return getAll(p);
        }
        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                r -> r.bucket(p.bucket()).key(p.key()).range("bytes=0-" + (length - 1)));
        return bytes.asByteArray();
    }

    public byte[] getAll(PhotoObject p) {
        return s3.getObjectAsBytes(r -> r.bucket(p.bucket()).key(p.key())).asByteArray();
    }

    /** Current details of one object, or empty if it no longer exists. */
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

    /** The bucket's region (needed for console links), or null if it can't be determined. */
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
    public void close() {
        s3.close();
    }
}
