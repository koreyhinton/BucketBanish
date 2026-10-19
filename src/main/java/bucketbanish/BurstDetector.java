package bucketbanish;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Finds bursts: runs of photos from the same camera taken within a short gap of each other. */
public final class BurstDetector {

    private BurstDetector() {
    }

    public record Burst(List<PhotoMeta> photos) {
        public Duration span() {
            return Duration.between(photos.getFirst().captureTime(), photos.getLast().captureTime());
        }
    }

    /**
     * Sorts photos by capture time and splits wherever consecutive shots are more than maxGap apart.
     * Photos without a capture time are ignored. Only runs of 2+ photos are returned.
     */
    public static List<Burst> groupByTime(List<PhotoMeta> photos, Duration maxGap, boolean sameCameraOnly) {
        Map<String, List<PhotoMeta>> byCamera = photos.stream()
                .filter(PhotoMeta::hasCaptureTime)
                .collect(Collectors.groupingBy(p -> sameCameraOnly ? p.cameraOrUnknown() : "",
                        LinkedHashMap::new, Collectors.toList()));

        List<Burst> bursts = new ArrayList<>();
        for (List<PhotoMeta> group : byCamera.values()) {
            group.sort(Comparator.comparing(PhotoMeta::captureTime).thenComparing(p -> p.object().uri()));
            List<PhotoMeta> run = new ArrayList<>();
            for (PhotoMeta p : group) {
                if (!run.isEmpty()
                        && Duration.between(run.getLast().captureTime(), p.captureTime()).compareTo(maxGap) > 0) {
                    addIfBurst(bursts, run);
                    run = new ArrayList<>();
                }
                run.add(p);
            }
            addIfBurst(bursts, run);
        }
        bursts.sort(Comparator.comparing(b -> b.photos().getFirst().captureTime()));
        return bursts;
    }

    /**
     * Within a burst, links photos whose dHash distance is at most maxDistance (single-linkage)
     * and returns the clusters of 2+ similar photos. Photos with no hash are left out.
     */
    public static List<List<PhotoMeta>> similarClusters(Burst burst, Map<PhotoObject, Long> hashes, int maxDistance) {
        List<PhotoMeta> hashed = burst.photos().stream()
                .filter(p -> hashes.get(p.object()) != null)
                .toList();
        int n = hashed.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                long a = hashes.get(hashed.get(i).object());
                long b = hashes.get(hashed.get(j).object());
                if (ImageHash.distance(a, b) <= maxDistance) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, List<PhotoMeta>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(hashed.get(i));
        }
        return clusters.values().stream().filter(c -> c.size() > 1).toList();
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void addIfBurst(List<Burst> out, List<PhotoMeta> run) {
        if (run.size() > 1) {
            out.add(new Burst(List.copyOf(run)));
        }
    }

    static boolean anyUnhashed(Burst burst, Map<PhotoObject, Long> hashes) {
        return burst.photos().stream().map(PhotoMeta::object).map(hashes::get).anyMatch(Objects::isNull);
    }
}
