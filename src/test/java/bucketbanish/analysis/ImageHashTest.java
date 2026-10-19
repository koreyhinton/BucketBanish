package bucketbanish.analysis;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageHashTest {

    static BufferedImage scene(int ballX, boolean flipped) {
        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, flipped ? Color.BLACK : Color.WHITE,
                640, 480, flipped ? Color.WHITE : Color.BLACK));
        g.fillRect(0, 0, 640, 480);
        g.setColor(Color.RED);
        g.fillOval(ballX, 200, 60, 60);
        g.dispose();
        return img;
    }

    static byte[] jpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void burstFramesAreClose_differentScenesAreFar() throws IOException {
        long a = ImageHash.dHash(jpeg(scene(100, false)));
        long b = ImageHash.dHash(jpeg(scene(110, false)));
        long c = ImageHash.dHash(jpeg(scene(100, true)));
        assertTrue(ImageHash.distance(a, b) <= 10, "burst frames distance " + ImageHash.distance(a, b));
        assertTrue(ImageHash.distance(a, c) > 20, "different scene distance " + ImageHash.distance(a, c));
    }

    @Test
    void sameImageReencodedHashesIdentically() throws IOException {
        BufferedImage img = scene(300, false);
        assertEquals(ImageHash.dHash(img), ImageHash.dHash(jpeg(img)));
    }

    @Test
    void undecodableBytesGiveNull() {
        assertNull(ImageHash.dHash("not an image".getBytes()));
        assertNotNull(ImageHash.dHash(scene(0, false)));
    }
}
