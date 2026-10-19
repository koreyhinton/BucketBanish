package bucketbanish;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

/**
 * 64-bit difference hash (dHash). Visually similar images have hashes with a small Hamming
 * distance: 0 = practically identical, under ~10 = very similar, over ~20 = different pictures.
 */
public final class ImageHash {

    private ImageHash() {
    }

    /** Decodes and hashes the image, or returns null if Java can't decode it (e.g. HEIC, RAW, CMYK JPEG). */
    public static Long dHash(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                // Subsample during decode so a 12MP photo is read at ~256px instead of full size.
                int shortSide = Math.min(reader.getWidth(0), reader.getHeight(0));
                int step = Math.max(1, shortSide / 256);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return dHash(reader.read(0, param));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static long dHash(BufferedImage img) {
        double[][] gray = shrinkToGray(img, 9, 8);
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (gray[y][x] > gray[y][x + 1]) {
                    hash |= 1;
                }
            }
        }
        return hash;
    }

    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** Box-averages the image down to w x h luminance cells (avoids aliasing from naive scaling). */
    private static double[][] shrinkToGray(BufferedImage img, int w, int h) {
        int iw = img.getWidth();
        int ih = img.getHeight();
        double[][] sum = new double[h][w];
        int[][] count = new int[h][w];
        for (int y = 0; y < ih; y++) {
            int cy = Math.min(h - 1, y * h / ih);
            for (int x = 0; x < iw; x++) {
                int cx = Math.min(w - 1, x * w / iw);
                int rgb = img.getRGB(x, y);
                double lum = 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                sum[cy][cx] += lum;
                count[cy][cx]++;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                sum[y][x] = count[y][x] == 0 ? 0 : sum[y][x] / count[y][x];
            }
        }
        return sum;
    }
}
