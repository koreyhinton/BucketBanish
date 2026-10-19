package bucketbanish.analysis;

import java.time.LocalDateTime;

/**
 * Photo metadata pulled from EXIF.
 * captureTime is the camera's local DateTimeOriginal (+ sub-seconds when present); null if unknown.
 * orientation is the EXIF orientation (1 = upright, 3/6/8 = rotated); null if absent.
 */
public record PhotoMeta(PhotoObject object, LocalDateTime captureTime, String camera, Integer width, Integer height,
                        Integer orientation) {

    public boolean hasCaptureTime() {
        return captureTime != null;
    }

    public String cameraOrUnknown() {
        return camera == null ? "unknown camera" : camera;
    }
}
