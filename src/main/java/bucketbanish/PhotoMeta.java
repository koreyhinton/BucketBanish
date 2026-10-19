package bucketbanish;

import java.time.LocalDateTime;

/**
 * Photo metadata pulled from EXIF.
 * captureTime is the camera's local DateTimeOriginal (+ sub-seconds when present); null if unknown.
 */
public record PhotoMeta(PhotoObject object, LocalDateTime captureTime, String camera, Integer width, Integer height) {

    public boolean hasCaptureTime() {
        return captureTime != null;
    }

    public String cameraOrUnknown() {
        return camera == null ? "unknown camera" : camera;
    }
}
