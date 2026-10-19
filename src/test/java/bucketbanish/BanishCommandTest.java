package bucketbanish;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BanishCommandTest {

    @Test
    void folderAndFileName() {
        assertEquals("2024/july/", BanishCommand.folderOf("2024/july/IMG_1.jpg"));
        assertEquals("", BanishCommand.folderOf("IMG_1.jpg"));
        assertEquals("IMG_1.jpg", BanishCommand.fileName("2024/july/IMG_1.jpg"));
    }

    @Test
    void consoleLinksEncodeKeys() {
        assertEquals("https://s3.console.aws.amazon.com/s3/object/photos?region=us-west-2&prefix=2024%2Fcopy%20of%20a%2Bb.jpg",
                BanishCommand.objectUrl("photos", "us-west-2", "2024/copy of a+b.jpg"));
        assertEquals("https://s3.console.aws.amazon.com/s3/buckets/photos?region=us-west-2&prefix=2024%2F",
                BanishCommand.bucketUrl("photos", "us-west-2", "2024/"));
        assertEquals("https://s3.console.aws.amazon.com/s3/buckets/photos?region=us-west-2",
                BanishCommand.bucketUrl("photos", "us-west-2", ""));
    }
}
