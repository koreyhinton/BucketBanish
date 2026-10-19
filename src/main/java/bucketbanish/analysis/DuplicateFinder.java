package bucketbanish.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Groups objects by identical byte size and by identical filename across buckets. */
public final class DuplicateFinder {

    private DuplicateFinder() {
    }

    public enum Verdict {
        /** Every member has a single-part ETag (= MD5) and they all match. */
        IDENTICAL,
        /** Single-part ETags prove the content differs. */
        DIFFERENT,
        /** Some ETags are multipart, so content can't be compared without downloading. */
        UNVERIFIED
    }

    public record SizeGroup(long size, List<PhotoObject> objects) {
        public Set<String> buckets() {
            return objects.stream().map(PhotoObject::bucket).collect(Collectors.toCollection(java.util.TreeSet::new));
        }

        public boolean crossBucket() {
            return buckets().size() > 1;
        }

        public Verdict verdict() {
            if (!objects.stream().allMatch(PhotoObject::hasContentEtag)) {
                return Verdict.UNVERIFIED;
            }
            long distinct = objects.stream().map(PhotoObject::eTag).distinct().count();
            return distinct == 1 ? Verdict.IDENTICAL : Verdict.DIFFERENT;
        }
    }

    public record NameGroup(String name, List<PhotoObject> objects) {
        public boolean sameSize() {
            return objects.stream().map(PhotoObject::size).distinct().count() == 1;
        }
    }

    /** Groups of 2+ objects with exactly the same byte size (objects smaller than minSize are skipped). */
    public static List<SizeGroup> bySize(List<PhotoObject> objects, long minSize) {
        Map<Long, List<PhotoObject>> grouped = objects.stream()
                .filter(o -> o.size() >= minSize)
                .collect(Collectors.groupingBy(PhotoObject::size, TreeMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> new SizeGroup(e.getKey(), sortByUri(e.getValue())))
                .sorted(Comparator.comparingLong(SizeGroup::size).reversed())
                .toList();
    }

    /** Filenames (ignoring folder) that appear in more than one bucket. */
    public static List<NameGroup> sameNameAcrossBuckets(List<PhotoObject> objects, boolean ignoreCase) {
        Map<String, List<PhotoObject>> grouped = objects.stream()
                .collect(Collectors.groupingBy(
                        o -> ignoreCase ? o.fileName().toLowerCase(Locale.ROOT) : o.fileName(),
                        TreeMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .filter(e -> e.getValue().stream().map(PhotoObject::bucket).distinct().count() > 1)
                .map(e -> new NameGroup(e.getKey(), sortByUri(e.getValue())))
                .toList();
    }

    private static List<PhotoObject> sortByUri(List<PhotoObject> list) {
        return list.stream().sorted(Comparator.comparing(PhotoObject::uri)).toList();
    }
}
