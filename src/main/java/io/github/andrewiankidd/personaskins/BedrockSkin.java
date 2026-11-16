package io.github.andrewiankidd.personaskins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.andrewiankidd.personaskins.geometry.Geometry;
import io.github.andrewiankidd.personaskins.render.Flattener;
import io.github.andrewiankidd.personaskins.render.Texture;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The skin a Bedrock client sent when it joined: its texture, geometry and
 * the resource patch naming which geometry to use.
 */
public record BedrockSkin(
        Texture texture,
        String skinId,
        String geometryJson,
        String resourcePatch,
        String geometryName,
        boolean persona,
        boolean slim,
        int skinColour) {

    /**
     * Reads the skin from a Geyser connection. Geyser keeps it in the session's
     * client data, which its API does not expose, so this goes through the
     * getters by name: GeyserSession.getClientData(), then BedrockClientData's
     * getSkinData(), getGeometryData() and the rest.
     */
    public static BedrockSkin read(Object connection) throws ReflectiveOperationException {
        Object data = call(connection, "getClientData");
        byte[] image = (byte[]) call(data, "getSkinData");
        int width = (int) call(data, "getSkinImageWidth");
        int height = (int) call(data, "getSkinImageHeight");
        byte[] geometry = (byte[]) call(data, "getGeometryData");
        byte[] patch = (byte[]) call(data, "getGeometryName");
        boolean persona = (boolean) call(data, "isPersonaSkin");
        String armSize = (String) call(data, "getArmSize");
        String colour = (String) call(data, "getSkinColor");

        String skinId = (String) call(data, "getSkinId");
        String geometryJson = geometry == null ? "" : new String(geometry, StandardCharsets.UTF_8).trim();
        String patchJson = patch == null ? "" : new String(patch, StandardCharsets.UTF_8);
        String name = geometryName(patch);
        boolean slim = "slim".equalsIgnoreCase(armSize)
                || (name != null && name.toLowerCase(Locale.ROOT).endsWith("slim"));
        return new BedrockSkin(new Texture(width, height, image), skinId == null ? "" : skinId, geometryJson, patchJson,
                name, persona, slim, parseColour(colour));
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** The default geometry's name from a resource patch: {"geometry": {"default": "..."}}. */
    static String geometryName(byte[] patch) {
        if (patch == null || patch.length == 0) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(new String(patch, StandardCharsets.UTF_8));
            JsonObject g = root.getAsJsonObject().getAsJsonObject("geometry");
            return g == null || !g.has("default") ? null : g.get("default").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "#RRGGBB" or "#AARRGGBB" as opaque ARGB, or mid grey. */
    static int parseColour(String s) {
        if (s == null) {
            return 0xff808080;
        }
        String hex = s.startsWith("#") ? s.substring(1) : s;
        try {
            long v = Long.parseLong(hex, 16);
            return 0xff000000 | (int) (v & 0xffffff);
        } catch (NumberFormatException e) {
            return 0xff808080;
        }
    }

    public Geometry geometry() {
        return Geometry.parse(geometryJson, geometryName);
    }

    /**
     * Whether Floodgate's own conversion loses something: a persona skin, or a
     * geometry that is not box for box the standard player model.
     */
    public boolean hasCustomGeometry() {
        if (persona) {
            return true;
        }
        if (geometryJson.isEmpty()) {
            return false;
        }
        try {
            return !Flattener.isStandard(geometry());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What identifies this skin: its texture, geometry and which geometry it uses. */
    public String key() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(texture.rgba());
            sha.update(geometryJson.getBytes(StandardCharsets.UTF_8));
            sha.update(String.valueOf(geometryName).getBytes(StandardCharsets.UTF_8));
            sha.update((byte) (slim ? 1 : 0));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Renders the Java skin: 64x64 ARGB. */
    public int[] render() {
        return Flattener.flatten(geometry(), texture, slim, skinColour);
    }
}
