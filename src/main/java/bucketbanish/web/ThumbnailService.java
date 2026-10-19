package bucketbanish.web;

import bucketbanish.analysis.PhotoMeta;
import bucketbanish.analysis.Thumbnails;
import bucketbanish.store.PhotoStore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Renders and caches small upright JPEG previews of scanned photos. */
@Service
public class ThumbnailService {

    private static final int CACHE_ENTRIES = 400;
    private static final byte[] UNSUPPORTED = new byte[0];

    private final PhotoStore store;
    private final Map<String, byte[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > CACHE_ENTRIES;
                }
            });

    public ThumbnailService(PhotoStore store) {
        this.store = store;
    }

    /** JPEG bytes, or empty if the format can't be previewed (HEIC, RAW...). */
    public Optional<byte[]> thumbnail(PhotoMeta meta, int maxSide) {
        String cacheKey = maxSide + ":" + meta.object().uri() + ":" + meta.object().eTag();
        byte[] bytes = cache.get(cacheKey);
        if (bytes == null) {
            bytes = Thumbnails.jpeg(store.getAll(meta.object()), maxSide, meta.orientation());
            cache.put(cacheKey, bytes == null ? UNSUPPORTED : bytes);
        }
        return bytes == null || bytes == UNSUPPORTED ? Optional.empty() : Optional.of(bytes);
    }
}
