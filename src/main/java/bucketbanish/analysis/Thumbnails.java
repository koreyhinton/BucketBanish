package bucketbanish.analysis;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Renders upright JPEG previews for the browser (which can't read most S3 photos directly). */
public final class Thumbnails {

    private Thumbnails() {
    }

    /** JPEG bytes no larger than maxSide on either side, or null if the image can't be decoded. */
    public static byte[] jpeg(byte[] source, int maxSide, Integer orientation) {
        BufferedImage img = ImageDecoder.decode(source, maxSide);
        if (img == null) {
            return null;
        }
        img = ImageDecoder.applyOrientation(img, orientation);
        double scale = Math.min(1.0, (double) maxSide / Math.max(img.getWidth(), img.getHeight()));
        int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
