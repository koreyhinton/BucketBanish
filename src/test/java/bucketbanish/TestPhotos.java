package bucketbanish;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/** Builds JPEGs with a real EXIF block (camera + DateTimeOriginal + sub-seconds) for tests. */
public final class TestPhotos {

    private TestPhotos() {
    }

    /** A gradient with a red ball; nearby ballX values look like burst frames, flipped looks different. */
    public static BufferedImage scene(int ballX, boolean flipped) {
        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, flipped ? Color.BLACK : Color.WHITE,
                640, 480, flipped ? Color.WHITE : Color.BLACK));
        g.fillRect(0, 0, 640, 480);
        g.setColor(Color.RED);
        g.fillOval(ballX, 200, 60, 60);
        g.dispose();
        return img;
    }

    public static byte[] jpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    public static byte[] jpegWithExif(BufferedImage img, String make, String model, LocalDateTime taken)
            throws IOException {
        byte[] jpeg = jpeg(img);
        byte[] tiff = exif(make, model, taken.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")),
                String.format("%03d", taken.getNano() / 1_000_000));
        ByteBuffer app1 = ByteBuffer.allocate(4 + 6 + tiff.length);
        app1.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + tiff.length));
        app1.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(tiff);
        // Insert right after SOI (FFD8).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(app1.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** Big-endian TIFF block: IFD0 (Make, Model, ExifIFD pointer) and ExifIFD (DateTimeOriginal, SubSec). */
    private static byte[] exif(String make, String model, String dateTime, String subSec) {
        byte[] mk = ascii(make);
        byte[] md = ascii(model);
        byte[] dt = ascii(dateTime);
        byte[] ss = ascii(subSec);
        int ifd0 = 8;
        int exifIfd = ifd0 + 2 + 3 * 12 + 4;
        int data = exifIfd + 2 + 2 * 12 + 4;
        ByteBuffer b = ByteBuffer.allocate(data + mk.length + md.length + dt.length + ss.length);
        b.put("MM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 42).putInt(ifd0);

        int offset = data;
        b.putShort((short) 3);
        offset = asciiEntry(b, 0x010F, mk, offset);
        offset = asciiEntry(b, 0x0110, md, offset);
        b.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(exifIfd);
        b.putInt(0);

        b.putShort((short) 2);
        offset = asciiEntry(b, 0x9003, dt, offset);
        asciiEntry(b, 0x9291, ss, offset);
        b.putInt(0);

        for (byte[] v : new byte[][] {mk, md, dt, ss}) {
            if (v.length > 4) {
                b.put(v); // same order as the offsets handed out above
            }
        }
        return Arrays.copyOf(b.array(), b.position());
    }

    /** Writes an ASCII IFD entry; values of 4 bytes or fewer are stored inline per the TIFF spec. */
    private static int asciiEntry(ByteBuffer b, int tag, byte[] value, int dataOffset) {
        b.putShort((short) tag).putShort((short) 2).putInt(value.length);
        if (value.length <= 4) {
            b.put(Arrays.copyOf(value, 4));
            return dataOffset;
        }
        b.putInt(dataOffset);
        return dataOffset + value.length;
    }

    private static byte[] ascii(String s) {
        return (s + "\0").getBytes(StandardCharsets.US_ASCII);
    }
}
