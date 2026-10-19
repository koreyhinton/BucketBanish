package bucketbanish;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParsingTest {

    @Test
    void exifDates() {
        assertEquals(LocalDateTime.of(2023, 5, 1, 14, 3, 9, 120_000_000),
                MetadataReader.parseExifDate("2023:05:01 14:03:09", "12"));
        assertEquals(LocalDateTime.of(2023, 5, 1, 14, 3, 9),
                MetadataReader.parseExifDate("2023:05:01 14:03:09", null));
        assertNull(MetadataReader.parseExifDate("0000:00:00 00:00:00", null));
        assertNull(MetadataReader.parseExifDate(null, null));
    }

    @Test
    void targets() {
        assertEquals(new Target("photos", ""), Target.parse("s3://photos"));
        assertEquals(new Target("photos", "2024/trip/"), Target.parse("s3://photos/2024/trip/"));
        assertEquals(new Target("photos", "a b.jpg"), Target.parseObjectUri("s3://photos/a b.jpg"));
        assertThrows(IllegalArgumentException.class, () -> Target.parseObjectUri("s3://photos"));
    }

    @Test
    void photoExtensions() {
        assertEquals(true, S3Photos.isPhoto("x/IMG_1.HEIC"));
        assertEquals(false, S3Photos.isPhoto("notes.txt"));
        assertEquals(false, S3Photos.isPhoto("jpg"));
    }
}
