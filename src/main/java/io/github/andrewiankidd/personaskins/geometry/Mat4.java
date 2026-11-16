package io.github.andrewiankidd.personaskins.geometry;

/** A 4x4 affine transform, row-major. */
public final class Mat4 {
    final double[] m;

    private Mat4(double[] m) {
        this.m = m;
    }

    public static Mat4 identity() {
        return new Mat4(new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    public static Mat4 translate(double x, double y, double z) {
        return new Mat4(new double[] {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1});
    }

    /**
     * A rotation as Bedrock geometry writes it: degrees about X, Y and Z, in the
     * file's coordinates, where X runs the opposite way to a right-handed renderer.
     * Blockbench, which writes these files, negates X and the X and Y angles on
     * export and applies Euler order ZYX; this undoes that.
     */
    public static Mat4 bedrockRotation(double[] degrees) {
        if (degrees == null || (degrees[0] == 0 && degrees[1] == 0 && degrees[2] == 0)) {
            return identity();
        }
        double ax = Math.toRadians(-degrees[0]);
        double ay = Math.toRadians(-degrees[1]);
        double az = Math.toRadians(degrees[2]);
        Mat4 inner = rz(az).mul(ry(ay)).mul(rx(ax));
        Mat4 flip = new Mat4(new double[] {-1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        return flip.mul(inner).mul(flip);
    }

    /** Rotation about a pivot point, in file coordinates. */
    public static Mat4 about(double[] pivot, double[] degrees) {
        if (pivot == null) {
            pivot = new double[3];
        }
        return translate(pivot[0], pivot[1], pivot[2])
                .mul(bedrockRotation(degrees))
                .mul(translate(-pivot[0], -pivot[1], -pivot[2]));
    }

    private static Mat4 rx(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Mat4(new double[] {1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1});
    }

    private static Mat4 ry(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Mat4(new double[] {c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1});
    }

    private static Mat4 rz(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Mat4(new double[] {c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    public Mat4 mul(Mat4 o) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += m[i * 4 + k] * o.m[k * 4 + j];
                }
                r[i * 4 + j] = sum;
            }
        }
        return new Mat4(r);
    }

    public double[] point(double[] p) {
        return new double[] {
            m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3],
            m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7],
            m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11],
        };
    }

    public double[] vector(double[] v) {
        return new double[] {
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[4] * v[0] + m[5] * v[1] + m[6] * v[2],
            m[8] * v[0] + m[9] * v[1] + m[10] * v[2],
        };
    }

    /** Inverse of a rotation-and-translation transform. */
    public Mat4 inverseRigid() {
        double[] r = new double[16];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 4 + j] = m[j * 4 + i];
            }
        }
        for (int i = 0; i < 3; i++) {
            r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11]);
        }
        r[15] = 1;
        return new Mat4(r);
    }

    public boolean isIdentity() {
        double[] id = identity().m;
        for (int i = 0; i < 16; i++) {
            if (Math.abs(m[i] - id[i]) > 1e-9) {
                return false;
            }
        }
        return true;
    }
}
