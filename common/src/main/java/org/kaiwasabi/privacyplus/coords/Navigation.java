package org.kaiwasabi.privacyplus.coords;

// The way to a tracked location from where you stand: how far, and an arrow relative to where you face.
// Across the Overworld and the Nether it points at the matching spot in your dimension (1 Nether block
// is 8 Overworld blocks, as portals link them); other dimensions have no such spot, so no direction.
public final class Navigation {
    // Clockwise from straight ahead
    static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    // Shown when the spot is straight above or below, where any direction would be noise
    static final String HERE = "•";
    static final int NETHER_SCALE = 8;

    public enum Scale {
        // Same dimension: real distance
        SAME,
        // The location is in the Overworld and you're in the Nether: its coordinates divided by 8
        OVERWORLD_IN_NETHER,
        // The location is in the Nether and you're in the Overworld: its coordinates multiplied by 8
        NETHER_IN_OVERWORLD,
        // Any other pair of dimensions: no way to point there
        NONE
    }

    // distance in blocks (horizontal only when scaled, since heights don't carry over); dy is the height
    // difference (above you when positive) and 0 when scaled. Both NaN, and arrow empty, for Scale.NONE.
    public record Reading(Scale scale, double distance, double dy, String arrow) {
    }

    private Navigation() {
    }

    public static Scale scale(String locationDimension, String yourDimension) {
        if (locationDimension.equals(yourDimension)) {
            return Scale.SAME;
        }
        if (locationDimension.equals(SharedLocation.OVERWORLD) && yourDimension.equals(SharedLocation.NETHER)) {
            return Scale.OVERWORLD_IN_NETHER;
        }
        if (locationDimension.equals(SharedLocation.NETHER) && yourDimension.equals(SharedLocation.OVERWORLD)) {
            return Scale.NETHER_IN_OVERWORLD;
        }
        return Scale.NONE;
    }

    // You at (x, y, z) in yourDimension (feet position) facing yaw (Minecraft degrees: 0 south, 90 west)
    public static Reading read(SharedLocation location, String yourDimension, double x, double y, double z, float yaw) {
        Scale scale = scale(location.dimension(), yourDimension);
        // Aim for the middle of the block
        double targetX = location.x() + 0.5;
        double targetZ = location.z() + 0.5;
        switch (scale) {
            case NONE -> {
                return new Reading(scale, Double.NaN, Double.NaN, "");
            }
            case OVERWORLD_IN_NETHER -> {
                targetX /= NETHER_SCALE;
                targetZ /= NETHER_SCALE;
            }
            case NETHER_IN_OVERWORLD -> {
                targetX *= NETHER_SCALE;
                targetZ *= NETHER_SCALE;
            }
            case SAME -> {
            }
        }
        double dx = targetX - x;
        double dz = targetZ - z;
        double dy = scale == Scale.SAME ? location.y() - y : 0;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        String arrow = horizontal < 1 ? HERE : arrow(relativeAngle(dx, dz, yaw));
        return new Reading(scale, Math.sqrt(horizontal * horizontal + dy * dy), dy, arrow);
    }

    // Degrees from straight ahead to the direction (dx, dz): 0 ahead, 90 to your right, ±180 behind
    static double relativeAngle(double dx, double dz, float yaw) {
        // The yaw that would face (dx, dz): south (+z) is 0, west (-x) is 90
        double towards = Math.toDegrees(Math.atan2(-dx, dz));
        return wrapDegrees(towards - yaw);
    }

    // The arrow nearest to an angle from relativeAngle
    static String arrow(double relativeDegrees) {
        return ARROWS[Math.floorMod(Math.round(relativeDegrees / 45), ARROWS.length)];
    }

    // Into [-180, 180)
    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped >= 180) {
            wrapped -= 360;
        } else if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }
}
