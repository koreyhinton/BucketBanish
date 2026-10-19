package bucketbanish.store;

import bucketbanish.analysis.PhotoObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    @Test
    void targets() {
        assertEquals(new Target("photos", ""), Target.parse("s3://photos"));
        assertEquals(new Target("photos", "2024/trip/"), Target.parse("s3://photos/2024/trip/"));
        assertEquals(new Target("photos", "a b.jpg"), Target.parseObjectUri("s3://photos/a b.jpg"));
        assertThrows(IllegalArgumentException.class, () -> Target.parseObjectUri("s3://photos"));
    }

    @Test
    void photoExtensions() {
        assertTrue(PhotoStore.isPhoto("x/IMG_1.HEIC"));
        assertFalse(PhotoStore.isPhoto("notes.txt"));
        assertFalse(PhotoStore.isPhoto("jpg"));
    }

    @Test
    void localStoreListsPhotosWithMd5EtagsAndRefusesTraversal(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("bucket/sub"));
        Files.writeString(root.resolve("bucket/sub/a.jpg"), "hello");
        Files.writeString(root.resolve("bucket/notes.txt"), "x");
        Files.writeString(root.resolve("secret.jpg"), "outside any bucket");

        try (LocalPhotoStore store = new LocalPhotoStore(root)) {
            List<PhotoObject> photos = store.listPhotos("bucket", "");
            assertEquals(List.of("sub/a.jpg"), photos.stream().map(PhotoObject::key).toList());
            assertEquals("5d41402abc4b2a76b9719d911017c592", photos.getFirst().eTag()); // md5("hello")
            assertTrue(store.head("bucket", "sub/a.jpg").isPresent());
            assertTrue(store.head("bucket", "gone.jpg").isEmpty());

            assertThrows(IllegalArgumentException.class, () -> store.head("bucket", "../secret.jpg"));
            assertThrows(IllegalArgumentException.class, () -> store.listPhotos("..", ""));
            assertFalse(store.consoleLinks());
        }
    }
}
