package org.kaiwasabi.privacyplus.coords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.kaiwasabi.privacyplus.coords.Navigation.Reading;
import org.kaiwasabi.privacyplus.coords.Navigation.Scale;
import org.junit.jupiter.api.Test;

class NavigationTest {
    private static final double DELTA = 1e-6;
    // Minecraft yaw: 0 faces south (+z), 90 west (-x), 180 north (-z), -90 east (+x)
    private static final float SOUTH = 0;
    private static final float WEST = 90;
    private static final float NORTH = 180;
    private static final float EAST = -90;

    @Test
    void anglesAreRelativeToWhereYouFace() {
        assertEquals(0, Navigation.relativeAngle(0, 10, SOUTH), DELTA);
        assertEquals(180, Math.abs(Navigation.relativeAngle(0, -10, SOUTH)), DELTA);
        // Facing south, west is on your right
        assertEquals(90, Navigation.relativeAngle(-10, 0, SOUTH), DELTA);
        assertEquals(-90, Navigation.relativeAngle(10, 0, SOUTH), DELTA);
        // Facing north, east is on your right
        assertEquals(90, Navigation.relativeAngle(10, 0, NORTH), DELTA);
        assertEquals(0, Navigation.relativeAngle(-10, 0, WEST), DELTA);
        assertEquals(0, Navigation.relativeAngle(10, 0, EAST), DELTA);
        // Yaw isn't kept in range by the game
        assertEquals(90, Navigation.relativeAngle(-10, 0, SOUTH + 720), DELTA);
        assertEquals(90, Navigation.relativeAngle(-10, 0, SOUTH - 360), DELTA);
    }

    @Test
    void picksTheNearestOfEightArrows() {
        assertEquals("↑", Navigation.arrow(0));
        assertEquals("↑", Navigation.arrow(22));
        assertEquals("↗", Navigation.arrow(23));
        assertEquals("↗", Navigation.arrow(45));
        assertEquals("→", Navigation.arrow(90));
        assertEquals("↘", Navigation.arrow(135));
        assertEquals("↓", Navigation.arrow(180));
        assertEquals("↓", Navigation.arrow(-180));
        assertEquals("↙", Navigation.arrow(-135));
        assertEquals("←", Navigation.arrow(-90));
        assertEquals("↖", Navigation.arrow(-45));
        assertEquals("↖", Navigation.arrow(-23));
        assertEquals("↑", Navigation.arrow(Double.NaN));
    }

    @Test
    void wrapsDegrees() {
        assertEquals(0, Navigation.wrapDegrees(360), DELTA);
        assertEquals(-180, Navigation.wrapDegrees(180), DELTA);
        assertEquals(-180, Navigation.wrapDegrees(540), DELTA);
        assertEquals(170, Navigation.wrapDegrees(-190), DELTA);
        assertEquals(-10, Navigation.wrapDegrees(-730), DELTA);
    }

    @Test
    void measuresDistanceToTheMiddleOfTheBlock() {
        SharedLocation location = new SharedLocation(3, 68, 4, SharedLocation.OVERWORLD, null);
        Reading reading = Navigation.read(location, SharedLocation.OVERWORLD, 0.5, 64, 0.5, SOUTH);
        assertEquals(Scale.SAME, reading.scale());
        assertEquals(Math.sqrt(3 * 3 + 4 * 4 + 4 * 4), reading.distance(), DELTA);
        assertEquals(4, reading.dy(), DELTA);
        // Mostly south (ahead), a bit east (to the left)
        assertEquals("↖", reading.arrow());
    }

    @Test
    void pointsNowhereWhenStraightAboveOrBelow() {
        SharedLocation location = new SharedLocation(10, -20, 10, SharedLocation.OVERWORLD, null);
        Reading reading = Navigation.read(location, SharedLocation.OVERWORLD, 10.5, 70, 10.7, NORTH);
        assertEquals(Navigation.HERE, reading.arrow());
        assertEquals(90, reading.distance(), 0.1);
        assertEquals(-90, reading.dy(), DELTA);
    }

    @Test
    void scalesOverworldLocationsDownInTheNether() {
        SharedLocation location = new SharedLocation(800, 64, -1600, SharedLocation.OVERWORLD, null);
        Reading reading = Navigation.read(location, SharedLocation.NETHER, 0, 120, 0, NORTH);
        assertEquals(Scale.OVERWORLD_IN_NETHER, reading.scale());
        // The matching Nether spot is (800.5 / 8, -1599.5 / 8); heights don't count across dimensions
        assertEquals(Math.hypot(800.5 / 8, -1599.5 / 8), reading.distance(), DELTA);
        assertEquals(0, reading.dy(), DELTA);
        // North and a bit east, facing north
        assertEquals("↗", reading.arrow());
    }

    @Test
    void scalesNetherLocationsUpInTheOverworld() {
        SharedLocation location = new SharedLocation(10, 70, -20, SharedLocation.NETHER, null);
        Reading reading = Navigation.read(location, SharedLocation.OVERWORLD, 0, 64, 0, EAST);
        assertEquals(Scale.NETHER_IN_OVERWORLD, reading.scale());
        assertEquals(Math.hypot(10.5 * 8, -19.5 * 8), reading.distance(), DELTA);
        assertEquals(Navigation.NETHER_SCALE, 8);
    }

    @Test
    void givesNoDirectionToOtherDimensions() {
        SharedLocation end = new SharedLocation(0, 64, 0, SharedLocation.END, null);
        for (String you : new String[] {SharedLocation.OVERWORLD, SharedLocation.NETHER, "aether:the_aether"}) {
            Reading reading = Navigation.read(end, you, 0, 64, 0, SOUTH);
            assertEquals(Scale.NONE, reading.scale());
            assertTrue(Double.isNaN(reading.distance()));
            assertEquals("", reading.arrow());
        }
        assertEquals(Scale.NONE, Navigation.scale(SharedLocation.OVERWORLD, SharedLocation.END));
        assertEquals(Scale.NONE, Navigation.scale("aether:the_aether", SharedLocation.NETHER));
        assertEquals(Scale.SAME, Navigation.scale("aether:the_aether", "aether:the_aether"));
    }

    @Test
    void survivesHostileCoordinates() {
        SharedLocation far = new SharedLocation(30_000_000, 4096, -30_000_000, SharedLocation.NETHER, null);
        Reading reading = Navigation.read(far, SharedLocation.OVERWORLD, -30_000_000, -64, 30_000_000, Float.NaN);
        assertTrue(Double.isFinite(reading.distance()));
        assertEquals(1, reading.arrow().length());
    }
}
