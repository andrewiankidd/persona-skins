package io.github.andrewiankidd.personaskins.geometry;

import java.util.Locale;

/**
 * The standard player models: what a Java skin is drawn on, and what older
 * Bedrock geometries inherit from. Positions and UVs are the ones the game uses
 * for a 64x64 skin; the second layer of each part is the same box, inflated.
 */
public final class StandardModels {
    private StandardModels() {
    }

    public static Geometry classic() {
        return Geometry.parse(json(false), "geometry.humanoid.custom");
    }

    public static Geometry slim() {
        return Geometry.parse(json(true), "geometry.humanoid.customSlim");
    }

    /** A standard geometry an older-format skin may inherit from, or null. */
    public static Geometry byName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("geometry.humanoid.customslim")) {
            return slim();
        }
        if (n.startsWith("geometry.humanoid")) {
            return classic();
        }
        return null;
    }

    private static String json(boolean slim) {
        String id = slim ? "geometry.humanoid.customSlim" : "geometry.humanoid.custom";
        int arm = slim ? 3 : 4;
        int rightArmX = slim ? -7 : -8;
        return """
            {"format_version": "1.12.0", "minecraft:geometry": [{
              "description": {"identifier": "%s", "texture_width": 64, "texture_height": 64},
              "bones": [
                {"name": "body", "pivot": [0, 24, 0], "cubes": [{"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16]}]},
                {"name": "jacket", "parent": "body", "pivot": [0, 24, 0], "cubes": [{"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 32], "inflate": 0.25}]},
                {"name": "head", "parent": "body", "pivot": [0, 24, 0], "cubes": [{"origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [0, 0]}]},
                {"name": "hat", "parent": "head", "pivot": [0, 24, 0], "cubes": [{"origin": [-4, 24, -4], "size": [8, 8, 8], "uv": [32, 0], "inflate": 0.5}]},
                {"name": "rightArm", "parent": "body", "pivot": [-5, 22, 0], "cubes": [{"origin": [%d, 12, -2], "size": [%d, 12, 4], "uv": [40, 16]}]},
                {"name": "rightSleeve", "parent": "rightArm", "pivot": [-5, 22, 0], "cubes": [{"origin": [%d, 12, -2], "size": [%d, 12, 4], "uv": [40, 32], "inflate": 0.25}]},
                {"name": "leftArm", "parent": "body", "pivot": [5, 22, 0], "cubes": [{"origin": [4, 12, -2], "size": [%d, 12, 4], "uv": [32, 48]}]},
                {"name": "leftSleeve", "parent": "leftArm", "pivot": [5, 22, 0], "cubes": [{"origin": [4, 12, -2], "size": [%d, 12, 4], "uv": [48, 48], "inflate": 0.25}]},
                {"name": "rightLeg", "parent": "body", "pivot": [-1.9, 12, 0], "cubes": [{"origin": [-3.9, 0, -2], "size": [4, 12, 4], "uv": [0, 16]}]},
                {"name": "rightPants", "parent": "rightLeg", "pivot": [-1.9, 12, 0], "cubes": [{"origin": [-3.9, 0, -2], "size": [4, 12, 4], "uv": [0, 32], "inflate": 0.25}]},
                {"name": "leftLeg", "parent": "body", "pivot": [1.9, 12, 0], "cubes": [{"origin": [-0.1, 0, -2], "size": [4, 12, 4], "uv": [16, 48]}]},
                {"name": "leftPants", "parent": "leftLeg", "pivot": [1.9, 12, 0], "cubes": [{"origin": [-0.1, 0, -2], "size": [4, 12, 4], "uv": [0, 48], "inflate": 0.25}]}
              ]
            }]}
            """.formatted(id, rightArmX, arm, rightArmX, arm, arm, arm);
    }
}
