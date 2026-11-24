import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.List;

/**
 * The shared tactical map. Draws the stylized board with Java2D in a 1000 x 1000 coordinate space
 * and reports clicks as board coordinates. It only displays what the server sends (see setCalls).
 */
public class MapPanel extends JPanel {
    public interface Listener {
        void place(int x, int y);
        void hover(String zone);   // "" when the mouse is outside the board
    }

    public record CallView(int id, String kind, int x, int y, String nick, String time, String zone) {}

    private static final Color LANE = new Color(0x476776), JUNGLE = new Color(0x2F545C), BUSH = new Color(0x03212B),
            RIVER = new Color(0x016B7B), BLUE_BASE = new Color(0x025170), RED_BASE = new Color(0x593036);
    /** The layout below was traced from a 447 x 447 reference picture; K converts its pixels to board units. */
    private static final double K = 1000 / 447.0;
    /** {x, y, team} in reference pixels: 0 = blue, 1 = red, 2 = neutral/yellow. */
    private static final int[][] TOWERS = {
        {20, 210, 0}, {22, 328, 0}, {88, 358, 0}, {125, 310, 0}, {118, 418, 0}, {200, 428, 0}, {328, 425, 0},
        {245, 15, 1}, {327, 22, 1}, {357, 90, 1}, {422, 120, 1}, {320, 140, 1}, {425, 240, 1},
        {117, 22, 2}, {17, 120, 2}, {272, 185, 2}, {172, 262, 2}, {428, 328, 2}
    };
    /** {x, y, kind} jungle camps: 0 green, 1 orange, 2 yellow, 3 purple, 4 cyan diamond. */
    private static final int[][] CAMPS = {
        {171, 81, 0}, {218, 117, 0}, {72, 193, 0}, {224, 328, 0}, {277, 364, 0}, {375, 256, 0},
        {235, 98, 1}, {207, 348, 1}, {82, 118, 2}, {361, 329, 2}, {112, 232, 3}, {337, 213, 3},
        {198, 89, 4}, {94, 199, 4}, {349, 246, 4}, {246, 357, 4}
    };
    private static final Color[] CAMP_COL = {new Color(0x00B719), new Color(0xF67F07), new Color(0xD7AA00), new Color(0x730F55), new Color(0x04B2D4)};

    private List<CallView> calls = List.of();
    private String myNick = "";
    private Listener listener;
    private double scale = 1;
    private int ox, oy;

    public MapPanel() {
        setBackground(Theme.BG);
        setPreferredSize(new Dimension(580, 580));
        setMinimumSize(new Dimension(320, 320));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!isEnabled() || listener == null || !SwingUtilities.isLeftMouseButton(e)) return;
                int[] b = toBoard(e.getPoint());
                if (b != null) listener.place(b[0], b[1]);
            }
            @Override public void mouseMoved(MouseEvent e) {
                if (listener == null) return;
                int[] b = toBoard(e.getPoint());
                listener.hover(b == null ? "" : Board.zoneAt(b[0], b[1]));
            }
            @Override public void mouseExited(MouseEvent e) { if (listener != null) listener.hover(""); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    public void setListener(Listener l) { listener = l; }

    public void setCalls(List<CallView> calls, String myNick) {
        this.calls = calls;
        this.myNick = myNick;
        repaint();
    }

    private void metrics() {
        int side = Math.max(120, Math.min(getWidth(), getHeight()) - 16);
        scale = side / (double) Board.SIZE;
        ox = (getWidth() - side) / 2;
        oy = (getHeight() - side) / 2;
    }

    private int[] toBoard(Point p) {
        metrics();
        int x = (int) Math.round((p.x - ox) / scale), y = (int) Math.round((p.y - oy) / scale);
        return Board.inside(x, y) ? new int[]{x, y} : null;
    }

    // ------------------------------------------------------------------ painting

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Theme.BG);
        g2.fillRect(0, 0, getWidth(), getHeight());
        AffineTransform screen = g2.getTransform();
        metrics();
        g2.translate(ox, oy);
        g2.scale(scale, scale);

        Shape board = new RoundRectangle2D.Double(0, 0, 1000, 1000, 40, 40);
        g2.setColor(LANE);
        g2.fill(board);
        Shape oldClip = g2.getClip();
        g2.clip(board);

        drawJungle(g2);
        drawRiver(g2);
        drawBases(g2);
        drawCamps(g2);
        drawTowers(g2);
        drawLabels(g2);
        g2.setClip(oldClip);

        g2.setColor(Theme.ACCENT2);
        g2.setStroke(new BasicStroke(6f));
        g2.draw(board);

        for (CallView c : calls) drawCall(g2, c);

        if (!isEnabled()) {
            g2.setTransform(screen);
            g2.setColor(new Color(0x14, 0x0B, 0x24, 180));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(Theme.TEXT);
            g2.setFont(new Font("SansSerif", Font.BOLD, 18));
            centerText(g2, "Connect to a server to open the meeting", getWidth() / 2.0, getHeight() / 2.0);
        }
        g2.dispose();
    }

    private void drawJungle(Graphics2D g2) {
        Path2D j = new Path2D.Double();
        j.moveTo(112, 112); j.lineTo(671, 112); j.lineTo(884, 324); j.lineTo(884, 884);
        j.lineTo(324, 884); j.lineTo(112, 671); j.closePath();
        g2.setColor(JUNGLE);
        g2.fill(j);
        g2.setStroke(new BasicStroke(36f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));   // rounds the corners
        g2.draw(j);

        Shape clip = g2.getClip();
        g2.clip(j);
        // dark bush clumps, scattered deterministically so every client draws the same map
        java.util.Random rnd = new java.util.Random(7);
        g2.setColor(BUSH);
        int placed = 0, tries = 0;
        while (placed < 55 && tries++ < 2000) {
            double x = 150 + rnd.nextDouble() * 700, y = 150 + rnd.nextDouble() * 700;
            double w = 40 + rnd.nextDouble() * 50, h = 30 + rnd.nextDouble() * 25, rot = rnd.nextDouble() * Math.PI;
            if (Math.abs(x + y - 1000) < 125) continue;                       // keep the mid lane clear
            if (Math.hypot(x - 320, y - 320) < 125 || Math.hypot(x - 680, y - 680) < 125 || Math.hypot(x - 500, y - 500) < 110) continue;
            AffineTransform t = g2.getTransform();
            g2.translate(x, y);
            g2.rotate(rot);
            g2.fill(new RoundRectangle2D.Double(-w / 2, -h / 2, w, h, h, h));
            g2.setTransform(t);
            placed++;
        }
        // mid lane: an open diagonal band across the jungle
        g2.setColor(LANE);
        g2.setStroke(new BasicStroke((float) (2 * 58), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g2.draw(new Line2D.Double(0, 1000, 1000, 0));
        g2.setClip(clip);
    }

    private void drawRiver(Graphics2D g2) {
        g2.setColor(RIVER);
        g2.setStroke(new BasicStroke(58f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Double(Board.TURTLE_X, Board.TURTLE_Y, Board.LORD_X, Board.LORD_Y));
        g2.fill(new Ellipse2D.Double(500 - 85, 500 - 85, 170, 170));
        drawPit(g2, Board.TURTLE_X, Board.TURTLE_Y, 0);
        drawPit(g2, Board.LORD_X, Board.LORD_Y, 180);
    }

    /** Big river pit with a dark crescent in it; {@code open} turns the crescent's opening (degrees). */
    private void drawPit(Graphics2D g2, int cx, int cy, int open) {
        double r = Board.PIT_R + 15;
        g2.setColor(RIVER);
        g2.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g2.setColor(BUSH);
        g2.setStroke(new BasicStroke(18f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Arc2D.Double(cx - 42, cy - 42, 84, 84, 45 + open, 270, Arc2D.OPEN));
    }

    private void drawBases(Graphics2D g2) {
        quarterBase(g2, Board.BLUE_X, Board.BLUE_Y, BLUE_BASE);
        quarterBase(g2, Board.RED_X, Board.RED_Y, RED_BASE);
        drawTowerIcon(g2, 52 * K, 385 * K, new Color(0x3FA9FF), 1.5);
        drawTowerIcon(g2, 400 * K, 52 * K, new Color(0xFF5C5C), 1.5);
    }

    private void quarterBase(Graphics2D g2, int cx, int cy, Color c) {
        g2.setColor(c);
        g2.fill(new Ellipse2D.Double(cx - Board.BASE_R, cy - Board.BASE_R, Board.BASE_R * 2, Board.BASE_R * 2));
    }

    private void drawCamps(Graphics2D g2) {
        for (int[] c : CAMPS) {
            double x = c[0] * K, y = c[1] * K, r = 17;
            g2.setColor(CAMP_COL[c[2]]);
            Shape s = c[2] == 4 ? diamond(x, y, r + 3) : new Ellipse2D.Double(x - r, y - r, r * 2, r * 2);
            g2.fill(s);
            g2.setColor(BUSH);
            g2.setStroke(new BasicStroke(5f));
            g2.draw(s);
        }
    }

    private static Shape diamond(double x, double y, double r) {
        Path2D p = new Path2D.Double();
        p.moveTo(x, y - r); p.lineTo(x + r, y); p.lineTo(x, y + r); p.lineTo(x - r, y); p.closePath();
        return p;
    }

    private void drawTowers(Graphics2D g2) {
        for (int[] t : TOWERS) {
            Color c = t[2] == 0 ? new Color(0x3FA9FF) : t[2] == 1 ? new Color(0xFF5C5C) : new Color(0xE0B44A);
            drawTowerIcon(g2, t[0] * K, t[1] * K, c, 1.0);
        }
    }

    /** Small stacked-roof tower icon, drawn from plain shapes. */
    private void drawTowerIcon(Graphics2D g2, double cx, double cy, Color c, double s) {
        Path2D p = new Path2D.Double();
        p.moveTo(cx, cy - 30 * s);                                   // top roof
        p.lineTo(cx + 13 * s, cy - 10 * s); p.lineTo(cx - 13 * s, cy - 10 * s); p.closePath();
        p.moveTo(cx, cy - 16 * s);                                   // middle roof
        p.lineTo(cx + 19 * s, cy + 6 * s); p.lineTo(cx - 19 * s, cy + 6 * s); p.closePath();
        p.moveTo(cx - 24 * s, cy + 26 * s);                          // base
        p.lineTo(cx - 14 * s, cy + 6 * s); p.lineTo(cx + 14 * s, cy + 6 * s); p.lineTo(cx + 24 * s, cy + 26 * s); p.closePath();
        g2.setColor(c);
        g2.fill(p);
    }

    private void drawLabels(Graphics2D g2) {
        g2.setFont(new Font("SansSerif", Font.BOLD, 24));
        g2.setColor(new Color(241, 233, 255, 120));
        centerText(g2, "EXP LANE", 395, 52);
        centerText(g2, "GOLD LANE", 605, 948);
        AffineTransform t = g2.getTransform();
        g2.translate(500, 500);
        g2.rotate(-Math.PI / 4);
        centerText(g2, "MID LANE", 0, 120);
        g2.setTransform(t);
        g2.setFont(new Font("SansSerif", Font.BOLD, 20));
        g2.setColor(new Color(241, 233, 255, 80));
        centerText(g2, "TURTLE", Board.TURTLE_X, Board.TURTLE_Y + 78);
        centerText(g2, "LORD", Board.LORD_X, Board.LORD_Y + 78);
    }

    private static final String[] GLYPH_KEYS = {"ATTACK", "DEFEND", "GANK", "TURTLE", "LORD", "DANGER"};
    private static final String[] GLYPHS = {"A", "D", "G", "T", "L", "!"};

    private void drawCall(Graphics2D g2, CallView c) {
        Color col = Theme.kindColor(c.kind());
        boolean mine = c.nick().equals(myNick);
        Ellipse2D dot = new Ellipse2D.Double(c.x() - 36, c.y() - 36, 72, 72);
        g2.setColor(new Color(0, 0, 0, 90));
        g2.fill(new Ellipse2D.Double(c.x() - 34, c.y() - 30, 72, 72));
        g2.setColor(col);
        g2.fill(dot);
        g2.setColor(mine ? Color.WHITE : new Color(0x140B24));
        g2.setStroke(new BasicStroke(mine ? 7f : 5f));
        g2.draw(dot);
        String glyph = "?";
        for (int i = 0; i < GLYPH_KEYS.length; i++) if (GLYPH_KEYS[i].equals(c.kind())) glyph = GLYPHS[i];
        g2.setFont(new Font("SansSerif", Font.BOLD, 40));
        g2.setColor(Theme.contrast(col));
        centerText(g2, glyph, c.x(), c.y());

        g2.setFont(new Font("SansSerif", Font.BOLD, 24));
        FontMetrics fm = g2.getFontMetrics();
        int w = fm.stringWidth(c.nick());
        g2.setColor(new Color(0x14, 0x0B, 0x24, 215));
        g2.fill(new RoundRectangle2D.Double(c.x() - w / 2.0 - 12, c.y() + 40, w + 24, 36, 16, 16));
        g2.setColor(Theme.TEXT);
        centerText(g2, c.nick(), c.x(), c.y() + 58);
    }

    private static void centerText(Graphics2D g2, String s, double cx, double cy) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
    }
}
