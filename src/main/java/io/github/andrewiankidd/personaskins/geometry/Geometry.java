package io.github.andrewiankidd.personaskins.geometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Bedrock entity geometry (the SkinGeometryData a Bedrock client sends), in
 * both the 1.12+ "minecraft:geometry" format and the older "geometry.name"
 * format, with every cube and polygon placed in model space.
 *
 * <p>Coordinates are the file's: Y up, the model facing north (-Z), and the
 * player's right side at -X.
 */
public final class Geometry {
    public final String identifier;
    public final double textureWidth;
    public final double textureHeight;
    public final List<Bone> bones;

    private Geometry(String identifier, double textureWidth, double textureHeight, List<Bone> bones) {
        this.identifier = identifier;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.bones = bones;
        resolveTransforms();
    }

    /** A cube or polygon face as the six sides of a box, named as Bedrock names them. */
    public enum Face {
        NORTH, EAST, SOUTH, WEST, UP, DOWN;

        static Face parse(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    public static final class Bone {
        public final String name;
        public final String parent;
        final double[] pivot;
        final double[] rotation;
        final boolean mirror;
        final double inflate;
        public final boolean neverRender;
        public final List<Cube> cubes = new ArrayList<>();
        public final List<Polygon> polygons = new ArrayList<>();
        /** Bone space to model space, including every parent. */
        public Mat4 transform = Mat4.identity();

        Bone(String name, String parent, double[] pivot, double[] rotation, boolean mirror, double inflate, boolean neverRender) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.rotation = rotation;
            this.mirror = mirror;
            this.inflate = inflate;
            this.neverRender = neverRender;
        }

        public boolean hasGeometry() {
            return !cubes.isEmpty() || !polygons.isEmpty();
        }
    }

    public static final class Cube {
        public final double[] min;
        public final double[] max;
        public final double[] size;
        public final boolean mirror;
        final double[] pivot;
        final double[] rotation;
        /** Box UV origin, or null when the cube has per-face UVs. */
        public final double[] boxUv;
        public final Map<Face, double[]> faceUv;
        /** The cube's own rotation, then its bone's: cube space to model space. */
        public Mat4 transform = Mat4.identity();
        public Mat4 inverse = Mat4.identity();

        Cube(double[] origin, double[] size, double inflate, boolean mirror, double[] pivot, double[] rotation,
             double[] boxUv, Map<Face, double[]> faceUv) {
            this.size = size;
            this.min = new double[] {origin[0] - inflate, origin[1] - inflate, origin[2] - inflate};
            this.max = new double[] {origin[0] + size[0] + inflate, origin[1] + size[1] + inflate, origin[2] + size[2] + inflate};
            this.mirror = mirror;
            this.pivot = pivot;
            this.rotation = rotation;
            this.boxUv = boxUv;
            this.faceUv = faceUv;
        }

        /**
         * Where a face's texture sits: {u, v, width, height} in texture units. With
         * box UV each face takes its rectangle of the standard unwrap; mirroring
         * swaps east and west and flips each face.
         */
        public double[] faceRect(Face face) {
            if (boxUv == null) {
                double[] r = faceUv.get(face);
                return r;
            }
            double w = size[0], h = size[1], d = size[2];
            double u = boxUv[0], v = boxUv[1];
            Face f = face;
            if (mirror && face == Face.EAST) {
                f = Face.WEST;
            } else if (mirror && face == Face.WEST) {
                f = Face.EAST;
            }
            double[] r = switch (f) {
                case EAST -> new double[] {u, v + d, d, h};
                case NORTH -> new double[] {u + d, v + d, w, h};
                case WEST -> new double[] {u + d + w, v + d, d, h};
                case SOUTH -> new double[] {u + d + w + d, v + d, w, h};
                case UP -> new double[] {u + d, v, w, d};
                case DOWN -> new double[] {u + d + w, v, w, d};
            };
            if (mirror) {
                r = new double[] {r[0] + r[2], r[1], -r[2], r[3]};
            }
            return r;
        }
    }

    /** A triangle of a poly_mesh, in model space, with texture coordinates in texture units. */
    public static final class Polygon {
        public final double[][] positions;
        public final double[][] uvs;

        Polygon(double[][] positions, double[][] uvs) {
            this.positions = positions;
            this.uvs = uvs;
        }
    }

    /**
     * Where on a face of a box a point at (a, b) lies: a runs left to right and b
     * top to bottom across the face's texture, both 0 to 1. Box UV unwraps the
     * sides as one strip, east, north, west, south, so neighbouring faces share
     * an edge; up and down sit above, front edge toward the north face.
     */
    public static double[] facePoint(Face face, double[] min, double[] max, double a, double b) {
        double x0 = min[0], y0 = min[1], z0 = min[2], x1 = max[0], y1 = max[1], z1 = max[2];
        double y = y1 - b * (y1 - y0);
        return switch (face) {
            case NORTH -> new double[] {x0 + a * (x1 - x0), y, z0};
            case EAST -> new double[] {x0, y, z1 - a * (z1 - z0)};
            case WEST -> new double[] {x1, y, z0 + a * (z1 - z0)};
            case SOUTH -> new double[] {x1 - a * (x1 - x0), y, z1};
            case UP -> new double[] {x0 + a * (x1 - x0), y1, z1 - b * (z1 - z0)};
            case DOWN -> new double[] {x0 + a * (x1 - x0), y0, z0 + b * (z1 - z0)};
        };
    }

    /** The inverse of {@link #facePoint}: (a, b) for a point on that face. */
    public static double[] faceCoords(Face face, double[] min, double[] max, double[] p) {
        double x0 = min[0], y0 = min[1], z0 = min[2], x1 = max[0], y1 = max[1], z1 = max[2];
        double b = (y1 - p[1]) / (y1 - y0);
        return switch (face) {
            case NORTH -> new double[] {(p[0] - x0) / (x1 - x0), b};
            case EAST -> new double[] {(z1 - p[2]) / (z1 - z0), b};
            case WEST -> new double[] {(p[2] - z0) / (z1 - z0), b};
            case SOUTH -> new double[] {(x1 - p[0]) / (x1 - x0), b};
            case UP -> new double[] {(p[0] - x0) / (x1 - x0), (z1 - p[2]) / (z1 - z0)};
            case DOWN -> new double[] {(p[0] - x0) / (x1 - x0), (p[2] - z0) / (z1 - z0)};
        };
    }

    /** The outward normal of a box face. */
    public static double[] normal(Face face) {
        return switch (face) {
            case NORTH -> new double[] {0, 0, -1};
            case SOUTH -> new double[] {0, 0, 1};
            case EAST -> new double[] {-1, 0, 0};
            case WEST -> new double[] {1, 0, 0};
            case UP -> new double[] {0, 1, 0};
            case DOWN -> new double[] {0, -1, 0};
        };
    }

    // ---- parsing ----

    /**
     * Parses a geometry file and picks the named geometry from it (the name in
     * the skin's resource patch), or the first one when the name is null or not
     * in the file.
     */
    public static Geometry parse(String json, String name) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, JsonObject> legacy = new LinkedHashMap<>();
        if (root.has("minecraft:geometry")) {
            JsonArray entries = root.getAsJsonArray("minecraft:geometry");
            JsonObject chosen = null;
            for (JsonElement e : entries) {
                JsonObject g = e.getAsJsonObject();
                String id = g.getAsJsonObject("description").get("identifier").getAsString();
                if (chosen == null || id.equals(name)) {
                    chosen = g;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException("no geometry in the file");
            }
            JsonObject d = chosen.getAsJsonObject("description");
            return new Geometry(d.get("identifier").getAsString(),
                    num(d, "texture_width", 64), num(d, "texture_height", 64),
                    bones(chosen.getAsJsonArray("bones"), false));
        }
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
                legacy.put(e.getKey(), e.getValue().getAsJsonObject());
            }
        }
        if (legacy.isEmpty()) {
            throw new IllegalArgumentException("no geometry in the file");
        }
        String key = null;
        for (String k : legacy.keySet()) {
            String own = k.contains(":") ? k.substring(0, k.indexOf(':')) : k;
            if (key == null || own.equals(name)) {
                key = k;
            }
        }
        return legacyGeometry(legacy, key);
    }

    /** An old-format geometry, with the bones of the one it inherits from ("a:b"). */
    private static Geometry legacyGeometry(Map<String, JsonObject> all, String key) {
        JsonObject g = all.get(key);
        String own = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
        List<Bone> bones = new ArrayList<>();
        double tw = num(g, "texturewidth", -1), th = num(g, "textureheight", -1);
        if (key.contains(":")) {
            String parentName = key.substring(key.indexOf(':') + 1);
            Geometry parent = null;
            for (String k : all.keySet()) {
                String kOwn = k.contains(":") ? k.substring(0, k.indexOf(':')) : k;
                if (kOwn.equals(parentName)) {
                    parent = legacyGeometry(all, k);
                }
            }
            if (parent == null) {
                parent = StandardModels.byName(parentName);
            }
            if (parent != null) {
                bones.addAll(parent.bones);
                if (tw < 0) {
                    tw = parent.textureWidth;
                    th = parent.textureHeight;
                }
            }
        }
        if (g.has("bones")) {
            for (Bone b : bones(g.getAsJsonArray("bones"), true)) {
                bones.removeIf(existing -> existing.name.equalsIgnoreCase(b.name));
                bones.add(b);
            }
        }
        return new Geometry(own, tw < 0 ? 64 : tw, th < 0 ? 64 : th, bones);
    }

    private static List<Bone> bones(JsonArray array, boolean legacy) {
        List<Bone> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (JsonElement e : array) {
            JsonObject j = e.getAsJsonObject();
            boolean mirror = bool(j, "mirror");
            double inflate = num(j, "inflate", 0);
            Bone bone = new Bone(
                    j.get("name").getAsString(),
                    j.has("parent") ? j.get("parent").getAsString() : null,
                    vec(j, "pivot"), vec(j, "rotation"), mirror, inflate,
                    bool(j, "neverRender") || bool(j, "never_render"));
            if (j.has("cubes")) {
                for (JsonElement c : j.getAsJsonArray("cubes")) {
                    bone.cubes.add(cube(c.getAsJsonObject(), mirror, inflate));
                }
            }
            if (j.has("poly_mesh")) {
                bone.polygons.addAll(polygons(j.getAsJsonObject("poly_mesh")));
            }
            out.add(bone);
        }
        return out;
    }

    private static Cube cube(JsonObject c, boolean boneMirror, double boneInflate) {
        double[] origin = vec(c, "origin");
        double[] size = vec(c, "size");
        if (origin == null || size == null) {
            throw new IllegalArgumentException("a cube without origin or size");
        }
        double inflate = c.has("inflate") ? num(c, "inflate", 0) : boneInflate;
        boolean mirror = c.has("mirror") ? bool(c, "mirror") : boneMirror;
        double[] boxUv = null;
        Map<Face, double[]> faceUv = null;
        JsonElement uv = c.get("uv");
        if (uv == null) {
            boxUv = new double[] {0, 0};
        } else if (uv.isJsonArray()) {
            boxUv = new double[] {uv.getAsJsonArray().get(0).getAsDouble(), uv.getAsJsonArray().get(1).getAsDouble()};
        } else {
            faceUv = new EnumMap<>(Face.class);
            for (Map.Entry<String, JsonElement> f : uv.getAsJsonObject().entrySet()) {
                JsonObject fo = f.getValue().getAsJsonObject();
                double[] at = vec2(fo, "uv");
                double[] sz = vec2(fo, "uv_size");
                if (at != null && sz != null) {
                    faceUv.put(Face.parse(f.getKey()), new double[] {at[0], at[1], sz[0], sz[1]});
                }
            }
        }
        return new Cube(origin, size, inflate, mirror, vec(c, "pivot"), vec(c, "rotation"), boxUv, faceUv);
    }

    private static List<Polygon> polygons(JsonObject mesh) {
        List<Polygon> out = new ArrayList<>();
        boolean normalized = bool(mesh, "normalized_uvs");
        JsonArray positions = mesh.getAsJsonArray("positions");
        JsonArray uvs = mesh.getAsJsonArray("uvs");
        JsonElement polys = mesh.get("polys");
        if (positions == null || uvs == null || polys == null) {
            return out;
        }
        List<int[][]> faces = new ArrayList<>();
        if (polys.isJsonArray()) {
            for (JsonElement p : polys.getAsJsonArray()) {
                JsonArray verts = p.getAsJsonArray();
                int[][] face = new int[verts.size()][];
                for (int i = 0; i < verts.size(); i++) {
                    JsonArray v = verts.get(i).getAsJsonArray();
                    face[i] = new int[] {v.get(0).getAsInt(), v.get(1).getAsInt(), v.get(2).getAsInt()};
                }
                faces.add(face);
            }
        } else {
            // "tri_list" or "quad_list": consecutive positions, normals and uvs.
            int n = polys.getAsString().startsWith("quad") ? 4 : 3;
            for (int i = 0; i + n <= positions.size(); i += n) {
                int[][] face = new int[n][];
                for (int k = 0; k < n; k++) {
                    face[k] = new int[] {i + k, i + k, i + k};
                }
                faces.add(face);
            }
        }
        for (int[][] face : faces) {
            double[][] p = new double[face.length][];
            double[][] t = new double[face.length][];
            for (int i = 0; i < face.length; i++) {
                JsonArray pos = positions.get(face[i][0]).getAsJsonArray();
                JsonArray uv = uvs.get(face[i][2]).getAsJsonArray();
                p[i] = new double[] {pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble()};
                // Polygon UVs count V up from the bottom, as fractions of the
                // texture when normalized_uvs is set; resolveTransforms converts.
                t[i] = new double[] {uv.get(0).getAsDouble(), uv.get(1).getAsDouble(), normalized ? 1 : 0};
            }
            for (int i = 1; i + 1 < face.length; i++) {
                out.add(new Polygon(new double[][] {p[0], p[i], p[i + 1]}, new double[][] {t[0], t[i], t[i + 1]}));
            }
        }
        return out;
    }

    /** Places every bone, cube and polygon in model space. */
    private void resolveTransforms() {
        Map<String, Bone> byName = new LinkedHashMap<>();
        for (Bone b : bones) {
            byName.put(b.name.toLowerCase(Locale.ROOT), b);
        }
        Map<Bone, Mat4> done = new LinkedHashMap<>();
        for (Bone b : bones) {
            b.transform = boneTransform(b, byName, done, 0);
            for (Cube c : b.cubes) {
                Mat4 own = c.rotation == null ? Mat4.identity() : Mat4.about(c.pivot, c.rotation);
                c.transform = b.transform.mul(own);
                c.inverse = c.transform.inverseRigid();
            }
            for (Polygon p : b.polygons) {
                for (int i = 0; i < p.positions.length; i++) {
                    p.positions[i] = b.transform.point(p.positions[i]);
                    double[] uv = p.uvs[i];
                    double u = uv[2] == 1 ? uv[0] * textureWidth : uv[0];
                    double v = uv[2] == 1 ? uv[1] * textureHeight : uv[1];
                    p.uvs[i] = new double[] {u, textureHeight - v};
                }
            }
        }
    }

    private static Mat4 boneTransform(Bone b, Map<String, Bone> byName, Map<Bone, Mat4> done, int depth) {
        Mat4 known = done.get(b);
        if (known != null) {
            return known;
        }
        Mat4 parent = Mat4.identity();
        if (b.parent != null && depth < 64) {
            Bone p = byName.get(b.parent.toLowerCase(Locale.ROOT));
            if (p != null && p != b) {
                parent = boneTransform(p, byName, done, depth + 1);
            }
        }
        Mat4 t = parent.mul(Mat4.about(b.pivot, b.rotation));
        done.put(b, t);
        return t;
    }

    private static double num(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static double[] vec(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static double[] vec2(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 2) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()};
    }
}
