import java.util.List;

/**
 * Shared board knowledge used by BOTH the server (to validate and name map calls) and the client (to draw the map).
 * The map is a simplified, original schematic of a 3-lane MOBA map on a 1000 x 1000 grid (origin top-left).
 * It is NOT the official game map and uses no game assets.
 */
public final class Board {
    public static final int SIZE = 1000;

    /** Lane / role slots on the lineup board. */
    public static final List<String> ROLES = List.of("EXP Lane", "Mid Lane", "Gold Lane", "Jungler", "Roamer");

    /** Call types a player can place on the tactical map. */
    public static final List<String> KINDS = List.of("ATTACK", "DEFEND", "GANK", "TURTLE", "LORD", "DANGER");

    // bases (blue bottom-left, red top-right), objective pits, lane widths
    public static final int BLUE_X = 0, BLUE_Y = 1000, RED_X = 1000, RED_Y = 0, BASE_R = 335;   // bases fill the corners
    public static final int TURTLE_X = 320, TURTLE_Y = 320, LORD_X = 680, LORD_Y = 680, PIT_R = 90;
    public static final int SIDE_LANE = 170;   // EXP lane = left+top edge band, Gold lane = bottom+right edge band
    public static final int MID_BAND = 80;     // mid lane = diagonal band |x + y - 1000| < 110

    private Board() {}

    public static boolean inside(int x, int y) { return x >= 0 && x <= SIZE && y >= 0 && y <= SIZE; }

    private static boolean within(int x, int y, int cx, int cy, int r) {
        long dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy < (long) r * r;
    }

    /** Human-readable name of the map area containing a point (used in callouts and hover text). */
    public static String zoneAt(int x, int y) {
        if (within(x, y, BLUE_X, BLUE_Y, BASE_R)) return "Blue Base";
        if (within(x, y, RED_X, RED_Y, BASE_R)) return "Red Base";
        if (within(x, y, TURTLE_X, TURTLE_Y, PIT_R)) return "Turtle Pit";
        if (within(x, y, LORD_X, LORD_Y, PIT_R)) return "Lord Pit";
        if (x < SIDE_LANE || y < SIDE_LANE) return "EXP Lane";
        if (y > SIZE - SIDE_LANE || x > SIZE - SIDE_LANE) return "Gold Lane";
        if (Math.abs(x + y - SIZE) < MID_BAND) return "Mid Lane";
        return x + y < SIZE ? "EXP-side Jungle" : "Gold-side Jungle";
    }
}
