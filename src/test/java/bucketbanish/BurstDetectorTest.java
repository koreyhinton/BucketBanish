package bucketbanish;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BurstDetectorTest {

    static final LocalDateTime T0 = LocalDateTime.of(2024, 7, 4, 12, 0, 0);

    static PhotoMeta photo(String key, double secondsAfter, String camera) {
        PhotoObject o = new PhotoObject("b", key, 100, "e" + key, Instant.EPOCH);
        return new PhotoMeta(o, T0.plusNanos((long) (secondsAfter * 1e9)), camera, null, null);
    }

    @Test
    void splitsOnGapsAndCamera() {
        List<PhotoMeta> photos = List.of(
                photo("a1", 0.0, "iPhone"), photo("a2", 0.3, "iPhone"), photo("a3", 0.6, "iPhone"),
                photo("lonely", 30, "iPhone"),
                photo("other", 0.1, "Pixel"),
                photo("b1", 60, "iPhone"), photo("b2", 61.5, "iPhone"),
                new PhotoMeta(new PhotoObject("b", "nodate", 1, "x", Instant.EPOCH), null, null, null, null));

        List<BurstDetector.Burst> bursts = BurstDetector.groupByTime(photos, Duration.ofSeconds(2), true);
        assertEquals(2, bursts.size());
        assertEquals(List.of("a1", "a2", "a3"), keys(bursts.get(0).photos()));
        assertEquals(List.of("b1", "b2"), keys(bursts.get(1).photos()));

        List<BurstDetector.Burst> anyCam = BurstDetector.groupByTime(photos, Duration.ofSeconds(2), false);
        assertEquals(List.of("a1", "other", "a2", "a3"), keys(anyCam.get(0).photos()));
    }

    @Test
    void clustersBySimilarity() {
        List<PhotoMeta> photos = List.of(photo("x1", 0, "c"), photo("x2", 0.2, "c"),
                photo("x3", 0.4, "c"), photo("x4", 0.6, "c"));
        BurstDetector.Burst burst = new BurstDetector.Burst(photos);
        Map<PhotoObject, Long> hashes = Map.of(
                photos.get(0).object(), 0b0000L,
                photos.get(1).object(), 0b0001L,     // 1 bit from x1
                photos.get(2).object(), -1L,         // 64 bits from everything
                photos.get(3).object(), 0b0011L);    // 1 bit from x2 -> chained into x1's cluster
        List<List<PhotoMeta>> clusters = BurstDetector.similarClusters(burst, hashes, 1);
        assertEquals(1, clusters.size());
        assertEquals(List.of("x1", "x2", "x4"), keys(clusters.getFirst()));
    }

    static List<String> keys(List<PhotoMeta> photos) {
        return photos.stream().map(p -> p.object().key()).toList();
    }
}
