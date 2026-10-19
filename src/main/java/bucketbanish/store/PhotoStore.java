package bucketbanish.store;

import bucketbanish.analysis.PhotoObject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Read-only access to buckets of photos. There is intentionally no delete operation. */
public interface PhotoStore extends AutoCloseable {

    Set<String> PHOTO_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff", "heic", "heif", "webp",
            "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2");

    static boolean isPhoto(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 && PHOTO_EXTENSIONS.contains(key.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Every photo-looking object under the prefix. */
    List<PhotoObject> listPhotos(String bucket, String prefix);

    /** The first {@code length} bytes (or the whole object if smaller). */
    byte[] getHead(PhotoObject p, long length);

    byte[] getAll(PhotoObject p);

    /** Current details of one object, or empty if it no longer exists. */
    Optional<PhotoObject> head(String bucket, String key);

    /** The bucket's region, or null if unknown / not applicable. */
    String bucketRegion(String bucket);

    /** Whether AWS console links can be generated for this store. */
    boolean consoleLinks();

    /** Short human description, e.g. "AWS S3" or "local folder /photos". */
    String describe();

    @Override
    void close();
}
