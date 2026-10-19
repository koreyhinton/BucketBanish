package bucketbanish.store;

import bucketbanish.analysis.PhotoObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Treats each subfolder of a local directory as a bucket, for demos and development without S3.
 * ETags are the file's MD5, matching what S3 reports for single-part uploads.
 */
public class LocalPhotoStore implements PhotoStore {

    private final Path root;

    public LocalPhotoStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("bucketbanish.local-root is not a directory: " + this.root);
        }
    }

    @Override
    public List<PhotoObject> listPhotos(String bucket, String prefix) {
        Path dir = bucketDir(bucket);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("No such bucket folder: " + bucket);
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> dir.relativize(f).toString().replace('\\', '/'))
                    .filter(key -> key.startsWith(prefix) && PhotoStore.isPhoto(key))
                    .sorted()
                    .map(key -> describe(bucket, key, dir.resolve(key)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] getHead(PhotoObject p, long length) {
        try (InputStream in = Files.newInputStream(file(p.bucket(), p.key()))) {
            return in.readNBytes((int) Math.min(length, Integer.MAX_VALUE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] getAll(PhotoObject p) {
        try {
            return Files.readAllBytes(file(p.bucket(), p.key()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<PhotoObject> head(String bucket, String key) {
        Path f = file(bucket, key);
        return Files.isRegularFile(f) ? Optional.of(describe(bucket, key, f)) : Optional.empty();
    }

    @Override
    public String bucketRegion(String bucket) {
        return null;
    }

    @Override
    public boolean consoleLinks() {
        return false;
    }

    @Override
    public String describe() {
        return "local folder " + root;
    }

    @Override
    public void close() {
    }

    private PhotoObject describe(String bucket, String key, Path f) {
        try {
            return new PhotoObject(bucket, key, Files.size(f), md5(f), Files.getLastModifiedTime(f).toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path bucketDir(String bucket) {
        Path dir = root.resolve(bucket).normalize();
        if (!dir.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid bucket: " + bucket);
        }
        return dir;
    }

    /** Resolves a key inside its bucket folder, refusing anything that escapes it (e.g. "../"). */
    private Path file(String bucket, String key) {
        Path dir = bucketDir(bucket);
        Path f = dir.resolve(key).normalize();
        if (!f.startsWith(dir)) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
        return f;
    }

    private static String md5(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
