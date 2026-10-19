package bucketbanish.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
