package bucketbanish.analysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateFinderTest {

    static PhotoObject obj(String bucket, String key, long size, String etag) {
        return new PhotoObject(bucket, key, size, etag, Instant.EPOCH);
    }

    @Test
    void sizeGroupsWithVerdicts() {
        List<PhotoObject> objs = List.of(
                obj("a", "1.jpg", 5000, "aaa"), obj("b", "copy/1.jpg", 5000, "aaa"),
                obj("a", "2.jpg", 7000, "bbb"), obj("a", "3.jpg", 7000, "ccc"),
                obj("a", "big.jpg", 9000, "ddd-2"), obj("b", "big.jpg", 9000, "eee"),
                obj("a", "unique.jpg", 1234, "fff"), obj("a", "tiny.jpg", 10, "t"), obj("b", "tiny.jpg", 10, "t"));

        List<DuplicateFinder.SizeGroup> groups = DuplicateFinder.bySize(objs, 1024);
        assertEquals(List.of(9000L, 7000L, 5000L), groups.stream().map(DuplicateFinder.SizeGroup::size).toList());
        assertEquals(DuplicateFinder.Verdict.UNVERIFIED, groups.get(0).verdict());
        assertEquals(DuplicateFinder.Verdict.DIFFERENT, groups.get(1).verdict());
        assertFalse(groups.get(1).crossBucket());
        assertEquals(DuplicateFinder.Verdict.IDENTICAL, groups.get(2).verdict());
        assertTrue(groups.get(2).crossBucket());
    }

    @Test
    void namesAcrossBucketsOnly() {
        List<PhotoObject> objs = List.of(
                obj("a", "x/IMG_1.JPG", 1, "1"), obj("b", "IMG_1.JPG", 2, "2"),
                obj("a", "IMG_2.JPG", 1, "1"), obj("a", "other/IMG_2.JPG", 1, "1"),
                obj("b", "img_2.jpg", 1, "1"));
        List<DuplicateFinder.NameGroup> exact = DuplicateFinder.sameNameAcrossBuckets(objs, false);
        assertEquals(List.of("IMG_1.JPG"), exact.stream().map(DuplicateFinder.NameGroup::name).toList());
        assertFalse(exact.getFirst().sameSize());

        List<DuplicateFinder.NameGroup> loose = DuplicateFinder.sameNameAcrossBuckets(objs, true);
        assertEquals(2, loose.size());
        assertEquals(3, loose.get(1).objects().size());
    }
}
