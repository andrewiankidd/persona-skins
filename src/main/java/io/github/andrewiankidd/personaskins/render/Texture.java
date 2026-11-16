package io.github.andrewiankidd.personaskins.render;

/** An RGBA image as Bedrock sends a skin: width * height * 4 bytes, row by row. */
public record Texture(int width, int height, byte[] rgba) {
    public Texture {
        if (width <= 0 || height <= 0 || rgba.length < width * height * 4) {
            throw new IllegalArgumentException("skin data is " + rgba.length + " bytes for " + width + "x" + height);
        }
    }

    /** The pixel at (x, y), as ARGB. */
    public int argb(int x, int y) {
        int i = (y * width + x) * 4;
        return (rgba[i + 3] & 0xff) << 24 | (rgba[i] & 0xff) << 16 | (rgba[i + 1] & 0xff) << 8 | rgba[i + 2] & 0xff;
    }

    public static Texture fromArgb(int width, int height, int[] argb) {
        byte[] out = new byte[width * height * 4];
        for (int i = 0; i < width * height; i++) {
            int p = argb[i];
            out[i * 4] = (byte) (p >> 16);
            out[i * 4 + 1] = (byte) (p >> 8);
            out[i * 4 + 2] = (byte) p;
            out[i * 4 + 3] = (byte) (p >>> 24);
        }
        return new Texture(width, height, out);
    }
}
