package bucketbanish.scan;

import bucketbanish.analysis.PhotoMeta;
import bucketbanish.analysis.PhotoObject;

import java.time.Instant;
import java.time.LocalDateTime;

/** A photo as the browser sees it. {@code uri} doubles as its id. */
public record PhotoView(String uri, String bucket, String key, String folder, String fileName, long size,
                        String eTag, boolean contentEtag, Instant uploaded, LocalDateTime captureTime,
                        String camera, Integer width, Integer height) {

    public static PhotoView of(PhotoMeta m) {
        PhotoObject o = m.object();
        int slash = o.key().lastIndexOf('/');
        String folder = slash < 0 ? "" : o.key().substring(0, slash + 1);
        return new PhotoView(o.uri(), o.bucket(), o.key(), folder, o.fileName(), o.size(), o.eTag(),
                o.hasContentEtag(), o.lastModified(), m.captureTime(), m.camera(), m.width(), m.height());
    }
}
