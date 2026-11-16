package io.github.andrewiankidd.personaskins.render;

import io.github.andrewiankidd.personaskins.geometry.Geometry;
import io.github.andrewiankidd.personaskins.geometry.Geometry.Bone;
import io.github.andrewiankidd.personaskins.geometry.Geometry.Cube;
import io.github.andrewiankidd.personaskins.geometry.Geometry.Face;
import io.github.andrewiankidd.personaskins.geometry.Geometry.Polygon;
import io.github.andrewiankidd.personaskins.geometry.StandardModels;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Draws a Bedrock skin, whatever its geometry, onto the two layers of a 64x64
 * Java skin.
 *
 * <p>Each texel of the Java model is a point on one face of one of its boxes.
 * From there a ray is cast inward, along the face's normal, through the
 * Bedrock model: the first opaque surface it meets outside the box is that
 * texel of the outer layer (hair, hats, hoods, collars), and the first at or
 * inside the box is the texel of the base layer. Seen from straight on, from
 * each of the six sides, the result looks as the Bedrock model does, with
 * everything that stood out pressed flat onto the outer layer.
 */
public final class Flattener {
    public static final int SIZE = 64;

    /** How far outside a face a surface may be and still count as on it. */
    private static final double ON_SURFACE = 0.01;
    private static final double REACH = 64;

    /** The six parts of a Java player, each a base box and the outer box over it. */
    public enum Part {
        HEAD("head", "hat"),
        BODY("body", "jacket"),
        RIGHT_ARM("rightArm", "rightSleeve"),
        LEFT_ARM("leftArm", "leftSleeve"),
        RIGHT_LEG("rightLeg", "rightPants"),
        LEFT_LEG("leftLeg", "leftPants");

        final String base;
        final String outer;

        Part(String base, String outer) {
            this.base = base;
            this.outer = outer;
        }
    }

    /** Bone names of the standard model, and which part each belongs to. */
    private static final Map<String, Part> STANDARD = new HashMap<>();

    /** Bones that are never part of the body: what is held, worn as armour, or a cape. */
    private static final Set<String> NOT_BODY = Set.of(
            "cape", "elytra", "rightitem", "leftitem");

    static {
        for (Part p : Part.values()) {
            STANDARD.put(p.base.toLowerCase(Locale.ROOT), p);
            STANDARD.put(p.outer.toLowerCase(Locale.ROOT), p);
        }
    }

    private Flattener() {
    }

    /**
     * Whether a geometry is just the standard player model, box for box, which
     * Floodgate's own conversion handles. Marketplace skins keep the standard
     * bone names but hang their own boxes from them (and from the armour
     * bones), so this compares the boxes, not the names.
     */
    public static boolean isStandard(Geometry g) {
        Map<String, Bone> classic = bonesByName(StandardModels.classic());
        Map<String, Bone> slim = bonesByName(StandardModels.slim());
        for (Bone b : g.bones) {
            if (b.neverRender || !b.hasGeometry()) {
                continue;
            }
            String n = b.name.toLowerCase(Locale.ROOT);
            if (NOT_BODY.contains(n)) {
                continue;
            }
            if (!b.polygons.isEmpty() || !(sameBoxes(b, classic.get(n)) || sameBoxes(b, slim.get(n)))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Bone> bonesByName(Geometry g) {
        Map<String, Bone> out = new HashMap<>();
        for (Bone b : g.bones) {
            out.put(b.name.toLowerCase(Locale.ROOT), b);
        }
        return out;
    }

    private static boolean sameBoxes(Bone b, Bone standard) {
        if (standard == null || b.cubes.size() != standard.cubes.size()) {
            return false;
        }
        for (int i = 0; i < b.cubes.size(); i++) {
            Cube c = b.cubes.get(i), s = standard.cubes.get(i);
            if (c.boxUv == null || !close(c.min, s.min) || !close(c.max, s.max) || !close(c.boxUv, s.boxUv)
                    || !c.transform.isIdentity()) {
                return false;
            }
        }
        return true;
    }

    private static boolean close(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 0.01) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders the skin.
     *
     * @param fallback the colour for base-layer texels the model leaves empty,
     *                 such as the skin colour; ARGB
     * @return 64x64 ARGB pixels
     */
    public static int[] flatten(Geometry geometry, Texture texture, boolean slim, int fallback) {
        Geometry target = slim ? StandardModels.slim() : StandardModels.classic();
        Map<Part, List<Primitive>> parts = assign(geometry, texture, target);
        int[] out = new int[SIZE * SIZE];
        for (Part part : Part.values()) {
            Cube base = cubeOf(target, part.base);
            Cube outer = cubeOf(target, part.outer);
            double[][] box = sourceBox(geometry, part, base);
            for (Face face : Face.values()) {
                List<Primitive> seen = new ArrayList<>(parts.get(part));
                // Long hair hangs over the back and shoulders; the head's
                // underside is hidden by the neck, so not from above.
                if (part == Part.BODY && face != Face.UP) {
                    seen.addAll(parts.get(Part.HEAD));
                }
                drawFace(out, base, box, face, seen, true, fallback);
                drawFace(out, outer, box, face, seen, false, fallback);
            }
        }
        return out;
    }

    /**
     * The box a part's rays start from: the Bedrock model's own head, body, arm
     * or leg, which need not be where or the size the Java model's is (a
     * marketplace character may have a lower head or a wider chest). It is the
     * bounds of the boxes in the part's base bone, the body's taking in the
     * waist's; the Java box when there are none.
     */
    private static double[][] sourceBox(Geometry g, Part part, Cube fallback) {
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        boolean any = false;
        for (Bone b : g.bones) {
            String n = b.name.toLowerCase(Locale.ROOT);
            boolean core = n.equals(part.base.toLowerCase(Locale.ROOT)) || (part == Part.BODY && n.equals("waist"));
            if (!core || b.neverRender) {
                continue;
            }
            for (Cube c : b.cubes) {
                for (int corner = 0; corner < 8; corner++) {
                    double[] p = c.transform.point(new double[] {
                        (corner & 1) == 0 ? c.min[0] : c.max[0],
                        (corner & 2) == 0 ? c.min[1] : c.max[1],
                        (corner & 4) == 0 ? c.min[2] : c.max[2]});
                    for (int i = 0; i < 3; i++) {
                        min[i] = Math.min(min[i], p[i]);
                        max[i] = Math.max(max[i], p[i]);
                    }
                }
                any = true;
            }
        }
        if (!any || max[0] - min[0] < 0.5 || max[1] - min[1] < 0.5 || max[2] - min[2] < 0.5) {
            return new double[][] {fallback.min.clone(), fallback.max.clone()};
        }
        return new double[][] {min, max};
    }

    private static void drawFace(int[] out, Cube target, double[][] box, Face face, List<Primitive> seen,
                                 boolean baseLayer, int fallback) {
        double[] rect = target.faceRect(face);
        int x0 = (int) Math.round(rect[0]), y0 = (int) Math.round(rect[1]);
        int w = (int) Math.round(rect[2]), h = (int) Math.round(rect[3]);
        // Both layers' texels stand for the same places on the part, so both
        // cast from the surface of the Bedrock model's own box for it.
        double[] min = box[0], max = box[1];
        double[] n = Geometry.normal(face);
        double[] dir = {-n[0], -n[1], -n[2]};
        for (int ty = 0; ty < h; ty++) {
            for (int tx = 0; tx < w; tx++) {
                double[] p = Geometry.facePoint(face, min, max, (tx + 0.5) / w, (ty + 0.5) / h);
                double[] origin = {p[0] + n[0] * REACH, p[1] + n[1] * REACH, p[2] + n[2] * REACH};
                List<double[]> hits = new ArrayList<>();
                for (Primitive prim : seen) {
                    prim.hits(origin, dir, hits);
                }
                hits.sort((a, b) -> Double.compare(a[0], b[0]));
                int colour = 0;
                if (baseLayer) {
                    // The first surface at or under this face; failing that,
                    // whatever covers it, so nothing shows through; failing
                    // that, the fallback. The base layer is always opaque.
                    for (double[] hit : hits) {
                        if (REACH - hit[0] <= ON_SURFACE) {
                            colour = (int) hit[1];
                            break;
                        }
                    }
                    if (colour == 0 && !hits.isEmpty()) {
                        colour = (int) hits.get(0)[1];
                    }
                    if (colour == 0) {
                        colour = fallback;
                    }
                    colour |= 0xff000000;
                } else if (!hits.isEmpty() && REACH - hits.get(0)[0] > ON_SURFACE) {
                    // The nearest surface stands out from the box: it goes on
                    // the outer layer.
                    colour = (int) hits.get(0)[1];
                }
                int x = x0 + (w < 0 ? -tx - 1 : tx), y = y0 + ty;
                if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) {
                    out[y * SIZE + x] = colour;
                }
            }
        }
    }

    private static Cube cubeOf(Geometry g, String bone) {
        for (Bone b : g.bones) {
            if (b.name.equals(bone)) {
                return b.cubes.get(0);
            }
        }
        throw new IllegalStateException("standard model has no " + bone);
    }

    /**
     * Sorts the model's surfaces by part: a bone belongs to the part of its
     * nearest standard ancestor (persona hair hangs from the head, sleeves from
     * the arms); a bone with none goes to the part nearest each of its boxes.
     */
    private static Map<Part, List<Primitive>> assign(Geometry g, Texture tex, Geometry target) {
        Map<Part, List<Primitive>> out = new EnumMap<>(Part.class);
        for (Part p : Part.values()) {
            out.put(p, new ArrayList<>());
        }
        Map<String, Bone> byName = new HashMap<>();
        for (Bone b : g.bones) {
            byName.put(b.name.toLowerCase(Locale.ROOT), b);
        }
        double sx = tex.width() / g.textureWidth, sy = tex.height() / g.textureHeight;
        for (Bone b : g.bones) {
            if (b.neverRender || !b.hasGeometry() || NOT_BODY.contains(b.name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Part part = partOf(b, byName);
            for (Cube c : b.cubes) {
                Primitive prim = new CubePrimitive(c, tex, sx, sy);
                out.get(part != null ? part : nearest(target, prim.centre())).add(prim);
            }
            for (Polygon poly : b.polygons) {
                Primitive prim = new PolygonPrimitive(poly, tex, sx, sy);
                out.get(part != null ? part : nearest(target, prim.centre())).add(prim);
            }
        }
        return out;
    }

    private static Part partOf(Bone b, Map<String, Bone> byName) {
        for (int i = 0; b != null && i < 64; i++) {
            String n = b.name.toLowerCase(Locale.ROOT);
            if (NOT_BODY.contains(n)) {
                return null;
            }
            Part p = STANDARD.get(n);
            if (p != null) {
                return p;
            }
            b = b.parent == null ? null : byName.get(b.parent.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static Part nearest(Geometry target, double[] c) {
        Part best = Part.BODY;
        double bestD = Double.MAX_VALUE;
        for (Part p : Part.values()) {
            Cube box = cubeOf(target, p.base);
            double d = 0;
            for (int i = 0; i < 3; i++) {
                double v = Math.max(box.min[i] - c[i], Math.max(0, c[i] - box.max[i]));
                d += v * v;
            }
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // ---- surfaces ----

    private interface Primitive {
        /** Adds {distance, ARGB} for each opaque surface the ray crosses. */
        void hits(double[] origin, double[] dir, List<double[]> out);

        double[] centre();
    }

    private static int sample(Texture tex, double u, double v, double sx, double sy) {
        int x = (int) Math.floor(u * sx), y = (int) Math.floor(v * sy);
        if (x < 0 || y < 0 || x >= tex.width() || y >= tex.height()) {
            return 0;
        }
        return tex.argb(x, y);
    }

    private record CubePrimitive(Cube cube, Texture tex, double sx, double sy) implements Primitive {
        @Override
        public void hits(double[] origin, double[] dir, List<double[]> out) {
            double[] o = cube.inverse.point(origin);
            double[] d = cube.inverse.vector(dir);
            double enter = Double.NEGATIVE_INFINITY, exit = Double.POSITIVE_INFINITY;
            Face face = null;
            for (int i = 0; i < 3; i++) {
                if (Math.abs(d[i]) < 1e-12) {
                    // A ray along a box's edge grazes it rather than hitting it.
                    if (o[i] <= cube.min[i] || o[i] >= cube.max[i]) {
                        return;
                    }
                    continue;
                }
                double t1 = (cube.min[i] - o[i]) / d[i], t2 = (cube.max[i] - o[i]) / d[i];
                double near = Math.min(t1, t2), far = Math.max(t1, t2);
                if (near > enter) {
                    enter = near;
                    face = switch (i) {
                        case 0 -> d[i] > 0 ? Face.EAST : Face.WEST;
                        case 1 -> d[i] > 0 ? Face.DOWN : Face.UP;
                        default -> d[i] > 0 ? Face.NORTH : Face.SOUTH;
                    };
                }
                exit = Math.min(exit, far);
            }
            if (face == null || enter > exit || enter < 0) {
                return;
            }
            double[] rect = cube.faceRect(face);
            if (rect == null) {
                return;
            }
            double[] p = {o[0] + d[0] * enter, o[1] + d[1] * enter, o[2] + d[2] * enter};
            double[] ab = Geometry.faceCoords(face, cube.min, cube.max, p);
            double a = clamp(ab[0]), b = clamp(ab[1]);
            int argb = sample(tex, rect[0] + a * rect[2], rect[1] + b * rect[3], sx, sy);
            if ((argb >>> 24) != 0) {
                out.add(new double[] {enter, argb});
            }
        }

        @Override
        public double[] centre() {
            return cube.transform.point(new double[] {
                (cube.min[0] + cube.max[0]) / 2, (cube.min[1] + cube.max[1]) / 2, (cube.min[2] + cube.max[2]) / 2});
        }

        private static double clamp(double v) {
            return Math.min(Math.max(v, 0), 0.999999);
        }
    }

    private record PolygonPrimitive(Polygon poly, Texture tex, double sx, double sy) implements Primitive {
        @Override
        public void hits(double[] origin, double[] dir, List<double[]> out) {
            double[] p0 = poly.positions[0], p1 = poly.positions[1], p2 = poly.positions[2];
            double[] e1 = sub(p1, p0), e2 = sub(p2, p0);
            double[] pv = cross(dir, e2);
            double det = dot(e1, pv);
            if (Math.abs(det) < 1e-12) {
                return;
            }
            double inv = 1 / det;
            double[] tv = sub(origin, p0);
            double u = dot(tv, pv) * inv;
            if (u < 0 || u > 1) {
                return;
            }
            double[] qv = cross(tv, e1);
            double v = dot(dir, qv) * inv;
            if (v < 0 || u + v > 1) {
                return;
            }
            double t = dot(e2, qv) * inv;
            if (t < 0) {
                return;
            }
            double w = 1 - u - v;
            double tu = w * poly.uvs[0][0] + u * poly.uvs[1][0] + v * poly.uvs[2][0];
            double tvv = w * poly.uvs[0][1] + u * poly.uvs[1][1] + v * poly.uvs[2][1];
            int argb = sample(tex, tu, tvv, sx, sy);
            if ((argb >>> 24) != 0) {
                out.add(new double[] {t, argb});
            }
        }

        @Override
        public double[] centre() {
            double[] c = new double[3];
            for (double[] p : poly.positions) {
                for (int i = 0; i < 3; i++) {
                    c[i] += p[i] / poly.positions.length;
                }
            }
            return c;
        }

        private static double[] sub(double[] a, double[] b) {
            return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
        }

        private static double[] cross(double[] a, double[] b) {
            return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }

        private static double dot(double[] a, double[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        }
    }
}
