package bucketbanish.analysis;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

/** Decodes images with Java ImageIO, subsampling during decode so large photos stay cheap. */
public final class ImageDecoder {

    private ImageDecoder() {
    }

    /**
     * Decodes at roughly {@code targetLongSide} pixels on the long side (never smaller), or returns
     * null if ImageIO can't read the format (e.g. HEIC, RAW, CMYK JPEG).
     */
    public static BufferedImage decode(byte[] bytes, int targetLongSide) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int longSide = Math.max(reader.getWidth(0), reader.getHeight(0));
                int step = Math.max(1, longSide / targetLongSide);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Applies the EXIF orientation rotation (3 = 180°, 6 = 90° clockwise, 8 = 90° counter-clockwise). */
    public static BufferedImage applyOrientation(BufferedImage img, Integer orientation) {
        if (orientation == null || (orientation != 3 && orientation != 6 && orientation != 8)) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        int outW = w;
        int outH = h;
        switch (orientation) {
            case 3 -> {
                t.translate(w, h);
                t.rotate(Math.PI);
            }
            case 6 -> {
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
                outW = h;
                outH = w;
            }
            default -> {
                t.translate(0, w);
                t.rotate(-Math.PI / 2);
                outW = h;
                outH = w;
            }
        }
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR).filter(toRgb(img), out);
        return out;
    }

    static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(img, 0, 0, null);
        return rgb;
    }
}
