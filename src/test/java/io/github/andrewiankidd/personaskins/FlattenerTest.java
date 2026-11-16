package io.github.andrewiankidd.personaskins;

import io.github.andrewiankidd.personaskins.geometry.Geometry;
import io.github.andrewiankidd.personaskins.geometry.Geometry.Bone;
import io.github.andrewiankidd.personaskins.geometry.Geometry.Cube;
import io.github.andrewiankidd.personaskins.geometry.Geometry.Face;
import io.github.andrewiankidd.personaskins.geometry.StandardModels;
import io.github.andrewiankidd.personaskins.render.Flattener;
import io.github.andrewiankidd.personaskins.render.Texture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlattenerTest {
    private static final int RED = 0xffff0000;

    /** Every pixel a different opaque colour. */
    private static int[] unique() {
        int[] px = new int[64 * 64];
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xff000000 | (int) (i * 2654435761L & 0xffffff) | 1;
        }
        return px;
    }

    /** Which pixels of a 64x64 skin the standard model uses. */
    private static boolean[] used(Geometry g) {
        boolean[] used = new boolean[64 * 64];
        for (Bone b : g.bones) {
            for (Cube c : b.cubes) {
                for (Face f : Face.values()) {
                    double[] r = c.faceRect(f);
                    for (int y = (int) r[1]; y < r[1] + r[3]; y++) {
                        for (int x = (int) r[0]; x < r[0] + r[2]; x++) {
                            used[y * 64 + x] = true;
                        }
                    }
                }
            }
        }
        return used;
    }

    @Test
    void aStandardSkinComesOutUnchanged() throws IOException {
        for (boolean slim : new boolean[] {false, true}) {
            Geometry g = slim ? StandardModels.slim() : StandardModels.classic();
            int[] in = unique();
            int[] out = Flattener.flatten(g, Texture.fromArgb(64, 64, in), slim, 0xff808080);
            save(slim ? "identity-slim" : "identity-classic", out);
            boolean[] used = used(g);
            StringBuilder wrong = new StringBuilder();
            for (int i = 0; i < in.length; i++) {
                if (used[i] && in[i] != out[i]) {
                    wrong.append(' ').append(i % 64).append(',').append(i / 64);
                }
            }
            assertEquals("", wrong.toString(), "pixels that moved, slim=" + slim);
        }
    }

    @Test
    void hairAboveTheHeadGoesOnTheHatLayer() throws IOException {
        // The standard model plus a bone of hair: a box over the top half of
        // the head, a little larger than it, textured red at (0, 64) on a
        // 64x128 texture.
        String json = """
            {"format_version": "1.12.0", "minecraft:geometry": [{
              "description": {"identifier": "geometry.test", "texture_width": 64, "texture_height": 128},
              "bones": [
                {"name": "body", "pivot": [0, 24, 0], "cubes": [{"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16]}]},
                {"name": "head", "parent": "body", "pivot": [0, 24, 0], "cubes": [{"origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [0, 0]}]},
                {"name": "hair", "parent": "head", "pivot": [0, 24, 0], "cubes": [{"origin": [-4.5, 28, -4.5], "size": [9, 4.5, 9], "uv": [0, 64]}]}
              ]
            }]}
            """;
        Geometry g = Geometry.parse(json, "geometry.test");
        int[] tex = new int[64 * 128];
        int[] face = unique();
        System.arraycopy(face, 0, tex, 0, 64 * 64);
        for (int i = 64 * 64; i < tex.length; i++) {
            tex[i] = RED;
        }
        int[] out = Flattener.flatten(g, Texture.fromArgb(64, 128, tex), false, 0xff808080);
        save("hair", out);

        // The hat layer's top (40, 0) is all hair.
        for (int y = 0; y < 8; y++) {
            for (int x = 40; x < 48; x++) {
                assertEquals(RED, out[y * 64 + x], "hat top at " + x + "," + y);
            }
        }
        // Its front (40, 8): hair over the top four rows, nothing below.
        for (int y = 8; y < 16; y++) {
            for (int x = 40; x < 48; x++) {
                if (y < 12) {
                    assertEquals(RED, out[y * 64 + x], "hat front at " + x + "," + y);
                } else {
                    assertEquals(0, out[y * 64 + x], "hat front at " + x + "," + y);
                }
            }
        }
        // The face under it is untouched.
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                assertEquals(face[y * 64 + x], out[y * 64 + x], "face at " + x + "," + y);
            }
        }
    }

    @Test
    void theOldFormatInheritsTheStandardModel() {
        String json = """
            {"format_version": "1.8.0", "geometry.test:geometry.humanoid.custom": {
              "bones": [{"name": "hair", "parent": "head", "pivot": [0, 24, 0],
                         "cubes": [{"origin": [-4.5, 28, -4.5], "size": [9, 4.5, 9], "uv": [0, 0]}]}]
            }}
            """;
        Geometry g = Geometry.parse(json, "geometry.test");
        assertTrue(g.bones.stream().anyMatch(b -> b.name.equals("head")));
        assertTrue(g.bones.stream().anyMatch(b -> b.name.equals("hair")));
        assertEquals(64, g.textureWidth);
    }

    @Test
    void theStandardModelIsStandard() {
        assertTrue(Flattener.isStandard(StandardModels.classic()));
        assertTrue(Flattener.isStandard(StandardModels.slim()));
        String hair = """
            {"format_version": "1.8.0", "geometry.test:geometry.humanoid.custom": {
              "bones": [{"name": "hat", "parent": "head", "pivot": [0, 24, 0],
                         "cubes": [{"origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [32, 0], "inflate": 0.5},
                                   {"origin": [-5, 30, -5], "size": [10, 4, 10], "uv": [0, 0]}]}]
            }}
            """;
        assertTrue(!Flattener.isStandard(Geometry.parse(hair, "geometry.test")));
    }

    /**
     * Renders a skin saved by /personaskins dump, when -Ppersona.dump=<dir> is
     * given: for checking real skins, which are not ours to commit.
     */
    @Test
    void renderADump() throws IOException {
        String dir = System.getProperty("persona.dump");
        if (dir == null || dir.isEmpty()) {
            return;
        }
        Path d = Path.of(dir);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(d.resolve("bedrock.png").toFile());
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        String json = Files.readString(d.resolve("geometry.json"));
        Geometry g = Geometry.parse(json, null);
        boolean slim = Files.readString(d.resolve("info.txt")).contains("slim: true");
        save("dump", Flattener.flatten(g, Texture.fromArgb(img.getWidth(), img.getHeight(), px), slim, 0xff808080));
    }

    static void save(String name, int[] argb) throws IOException {
        String dir = System.getProperty("persona.out");
        if (dir == null) {
            return;
        }
        Path p = Path.of(dir);
        Files.createDirectories(p);
        Files.write(p.resolve(name + ".png"), Png.encode(argb, 64, 64));
    }
}
