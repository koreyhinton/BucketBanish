package bucketbanish.analysis;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Extracts capture date, camera and dimensions from image bytes using metadata-extractor. */
public final class MetadataReader {

    private static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private MetadataReader() {
    }

    /**
     * Parses whatever metadata is present. Works on a truncated prefix of the file as long as the
     * EXIF block is inside it. Returns a PhotoMeta with null fields for anything not found.
     */
    public static PhotoMeta read(PhotoObject object, byte[] bytes) {
        Metadata md;
        try {
            md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes), bytes.length);
        } catch (Exception e) {
            return new PhotoMeta(object, null, null, null, null, null);
        }

        ExifSubIFDDirectory sub = md.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        ExifIFD0Directory ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory.class);

        LocalDateTime captured = null;
        if (sub != null) {
            captured = parseExifDate(sub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL),
                    sub.getString(ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL));
        }
        if (captured == null && ifd0 != null) {
            captured = parseExifDate(ifd0.getString(ExifIFD0Directory.TAG_DATETIME), null);
        }

        String camera = null;
        if (ifd0 != null) {
            String make = trim(ifd0.getString(ExifIFD0Directory.TAG_MAKE));
            String model = trim(ifd0.getString(ExifIFD0Directory.TAG_MODEL));
            if (make != null && model != null && model.startsWith(make)) {
                camera = model;
            } else if (make != null || model != null) {
                camera = ((make == null ? "" : make) + " " + (model == null ? "" : model)).trim();
            }
        }

        Integer width = firstInt(md, JpegDirectory.class, JpegDirectory.TAG_IMAGE_WIDTH);
        Integer height = firstInt(md, JpegDirectory.class, JpegDirectory.TAG_IMAGE_HEIGHT);
        if (width == null) {
            width = firstInt(md, PngDirectory.class, PngDirectory.TAG_IMAGE_WIDTH);
            height = firstInt(md, PngDirectory.class, PngDirectory.TAG_IMAGE_HEIGHT);
        }
        if (width == null) {
            width = firstInt(md, ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH);
            height = firstInt(md, ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT);
        }
        Integer orientation = firstInt(md, ExifIFD0Directory.class, ExifIFD0Directory.TAG_ORIENTATION);
        return new PhotoMeta(object, captured, camera, width, height, orientation);
    }

    /** Parses "yyyy:MM:dd HH:mm:ss" plus optional sub-second digits ("12" -> .12s). */
    static LocalDateTime parseExifDate(String value, String subSeconds) {
        if (value == null) {
            return null;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(value.trim(), EXIF_DATE);
            String digits = subSeconds == null ? "" : subSeconds.trim().replaceAll("\\D", "");
            if (!digits.isEmpty()) {
                String nanos = (digits + "000000000").substring(0, 9);
                t = t.withNano(Integer.parseInt(nanos));
            }
            return t;
        } catch (DateTimeParseException e) {
            return null; // e.g. "0000:00:00 00:00:00" from cameras with no clock set
        }
    }

    private static Integer firstInt(Metadata md, Class<? extends Directory> type, int tag) {
        Directory d = md.getFirstDirectoryOfType(type);
        return d != null && d.containsTag(tag) ? d.getInteger(tag) : null;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
