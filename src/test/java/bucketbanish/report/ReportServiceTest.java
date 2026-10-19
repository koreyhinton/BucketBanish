package bucketbanish.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportServiceTest {

    @Test
    void folderAndFileName() {
        assertEquals("2024/july/", ReportService.folderOf("2024/july/IMG_1.jpg"));
        assertEquals("", ReportService.folderOf("IMG_1.jpg"));
        assertEquals("IMG_1.jpg", ReportService.fileName("2024/july/IMG_1.jpg"));
    }

    @Test
    void consoleLinksEncodeKeys() {
        assertEquals("https://s3.console.aws.amazon.com/s3/object/photos?region=us-west-2&prefix=2024%2Fcopy%20of%20a%2Bb.jpg",
                ReportService.objectUrl("photos", "us-west-2", "2024/copy of a+b.jpg"));
        assertEquals("https://s3.console.aws.amazon.com/s3/buckets/photos?region=us-west-2&prefix=2024%2F",
                ReportService.bucketUrl("photos", "us-west-2", "2024/"));
        assertEquals("https://s3.console.aws.amazon.com/s3/buckets/photos?region=us-west-2",
                ReportService.bucketUrl("photos", "us-west-2", ""));
    }

    @Test
    void csvFieldsAreQuotedAndFormulaSafe() {
        assertEquals("plain", ReportService.csvField("plain"));
        assertEquals("\"a,b\"", ReportService.csvField("a,b"));
        assertEquals("'=HYPERLINK(1)", ReportService.csvField("=HYPERLINK(1)"));
        assertEquals("", ReportService.csvField(null));
    }
}
