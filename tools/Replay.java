import fr.externalscreen.beastfingerdiagnostic.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Replays a beast-diag-*.zip (recorded by the app) through FrameProcessor. */
public class Replay {
    public static void main(String[] a) throws Exception {
        ZipFile z = new ZipFile(a[0]);
        String meta = new String(z.getInputStream(z.getEntry("meta.txt")).readAllBytes(), "UTF-8");
        String[] lines = meta.split("\n");
        String[] head = lines[0].split(" ");
        String[] scr = head[Arrays.asList(head).indexOf("screen") + 1].split("x");
        int sw = Integer.parseInt(scr[0]), sh = Integer.parseInt(scr[1]);
        FrameProcessor fp = new FrameProcessor();
        fp.setFrameMode(true);
        for (int i = 1; i < lines.length; i++) {
            String name = lines[i].split(" ")[0];
            BufferedImage im = ImageIO.read(z.getInputStream(z.getEntry(name)));
            int w = im.getWidth(), h = im.getHeight();
            int[] argb = im.getRGB(0, 0, w, h, null, 0, w);
            List<float[]> hands = new ArrayList<>();
            for (String part : lines[i].split("\\[")) {
                if (!part.contains("]")) continue;
                String[] v = part.substring(0, part.indexOf(']')).split(",");
                if (v.length < 42) continue;
                float[] lm = new float[v.length];
                for (int k = 0; k < v.length; k++) lm[k] = Float.parseFloat(v[k]);
                hands.add(lm);
            }
            FrameProcessor.Result r = fp.process(argb, w, h, hands, i * 33_333_333L, sw, sh, i * 33_333_333L);
            System.out.println(name + " " + r.diag);
        }
    }
}
