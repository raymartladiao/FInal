import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Collections;

/** Purple look & feel shared by every window: colours, UIManager defaults and two custom-painted button types. */
public final class Theme {
    public static final Color BG = new Color(0x140B24), PANEL = new Color(0x1F1235), FIELD = new Color(0x2B1B4A),
            TEXT = new Color(0xF1E9FF), MUTED = new Color(0xA394C7),
            ACCENT = new Color(0xB36BFF), ACCENT2 = new Color(0x7C3AED), GOLD = new Color(0xFFD84D), GOOD = new Color(0x3DDC97);

    private Theme() {}

    public static Color kindColor(String kind) {
        return switch (kind) {
            case "ATTACK" -> new Color(0xFF5C6C);
            case "DEFEND" -> new Color(0x4DA3FF);
            case "GANK" -> new Color(0xFFA24D);
            case "TURTLE" -> new Color(0x3DDC97);
            case "LORD" -> new Color(0xFFD84D);
            default -> new Color(0xE9DDFF);   // DANGER
        };
    }

    static Color contrast(Color c) {
        double lum = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
        return lum > 0.6 ? Color.BLACK : Color.WHITE;
    }

    /** Rounded purple push button (painted by hand so it looks the same on every Look and Feel). */
    public static class Btn extends JButton {
        public Btn(String text) {
            super(text);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setForeground(Color.WHITE);
            setBorder(new EmptyBorder(7, 14, 7, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = !isEnabled() ? FIELD : getModel().isPressed() ? ACCENT : getModel().isRollover() ? new Color(0x9156F2) : ACCENT2;
            g2.setColor(base);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Rounded toggle "chip": filled with its own colour when selected, outlined when not. */
    public static class Chip extends JToggleButton {
        private final Color on;

        public Chip(String text, Color on) {
            super(text);
            this.on = on;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setBorder(new EmptyBorder(6, 13, 6, 13));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(TEXT);
            addItemListener(e -> setForeground(isSelected() ? contrast(on) : TEXT));
        }

        @Override public void setSelected(boolean b) {
            super.setSelected(b);
            setForeground(b ? contrast(on) : TEXT);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected()) {
                g2.setColor(on);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            } else {
                g2.setColor(FIELD);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g2.setColor(on);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Applies the purple theme to Swing defaults; must run before any component is created. */
    public static void install() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource f)
                UIManager.put(key, new javax.swing.plaf.FontUIResource("SansSerif", f.getStyle(), 13));
        }
        Object[][] kv = {
            {"Panel.background", BG}, {"OptionPane.background", PANEL}, {"OptionPane.messageForeground", TEXT},
            {"Label.foreground", TEXT}, {"Label.disabledForeground", MUTED}, {"Label.disabledShadow", BG},
            {"TextField.background", FIELD}, {"TextField.foreground", TEXT}, {"TextField.caretForeground", ACCENT},
            {"TextField.inactiveBackground", PANEL}, {"TextField.inactiveForeground", MUTED},
            {"TextField.border", BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ACCENT2), new EmptyBorder(4, 6, 4, 6))},
            {"TextArea.background", FIELD}, {"TextArea.foreground", TEXT}, {"TextArea.caretForeground", ACCENT},
            {"TextArea.inactiveForeground", TEXT}, {"TextArea.selectionBackground", ACCENT2}, {"TextArea.selectionForeground", Color.WHITE},
            {"List.background", FIELD}, {"List.foreground", TEXT}, {"List.selectionBackground", ACCENT2}, {"List.selectionForeground", Color.WHITE},
            {"ComboBox.background", FIELD}, {"ComboBox.foreground", TEXT}, {"ComboBox.selectionBackground", ACCENT2},
            {"ScrollPane.background", FIELD}, {"Viewport.background", FIELD},
            {"ScrollPane.border", BorderFactory.createLineBorder(PANEL)},
            {"ScrollBar.background", PANEL}, {"ScrollBar.thumb", ACCENT2}, {"ScrollBar.thumbShadow", ACCENT2},
            {"ScrollBar.thumbHighlight", ACCENT2}, {"ScrollBar.track", PANEL}, {"ScrollBar.trackHighlight", PANEL},
            {"Button.background", ACCENT2}, {"Button.foreground", Color.WHITE}, {"Button.disabledText", MUTED},
            {"Button.select", ACCENT}, {"Button.focus", ACCENT2},
            {"Button.border", new EmptyBorder(7, 14, 7, 14)},
            {"TitledBorder.titleColor", MUTED}, {"TitledBorder.border", BorderFactory.createLineBorder(FIELD)},
            {"control", PANEL}, {"text", TEXT}, {"nimbusBase", ACCENT2},
        };
        for (Object[] e : kv) UIManager.put(e[0], e[1]);
    }
}
