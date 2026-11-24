import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DawnRoom GUI client (Swing) - MLBB Map Meeting.
 * Threading: a reader thread blocks on the socket; every UI change is pushed to the Event Dispatch Thread
 * with SwingUtilities.invokeLater. Outgoing writes go through a single-thread executor so the GUI never blocks.
 */
public class DawnClient extends JFrame {
    private static final String HINT = "Hover the map to see zone names. Click to place the selected call.";
    private static final Map<String, String> ROLE_INFO = Map.of(
            "EXP Lane", "Top lane: left and top edge of the map",
            "Mid Lane", "The diagonal lane between both bases",
            "Gold Lane", "Bottom lane: bottom and right edge",
            "Jungler", "Farms the jungle, takes Turtle and Lord",
            "Roamer", "Moves between lanes: vision, ganks, support");

    // connection controls
    private final JTextField hostField = new JTextField("localhost", 11);
    private final JTextField portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 5);
    private final JTextField nickField = new JTextField(10);
    private final Theme.Btn connectBtn = new Theme.Btn("Connect");
    private final JLabel status = new JLabel("Disconnected");

    // chat panel (right side)
    private final JTextArea chatArea = new JTextArea();
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userModel);
    private final JScrollPane userScroll = new JScrollPane(userList);
    private final JTextField inputField = new JTextField();
    private final Theme.Btn sendBtn = new Theme.Btn("Send");

    // tactical map view
    private final MapPanel mapPanel = new MapPanel();
    private final Theme.Btn undoCallBtn = new Theme.Btn("Undo my last call");
    private final Theme.Btn clearMapBtn = new Theme.Btn("Clear map (shotcaller)");
    private final JLabel zoneLabel = new JLabel(HINT);
    private String selectedKind = "ATTACK";

    // lane lineup view
    private final JLabel lineupHeader = new JLabel(" ");
    private final JPanel rolesPanel = new JPanel();
    private final Theme.Btn releaseRoleBtn = new Theme.Btn("Release my role");
    private final Theme.Btn clearLineupBtn = new Theme.Btn("Clear lineup (shotcaller)");
    private final Map<String, String> roleHolders = new LinkedHashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);

    // network state
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "net-writer"); t.setDaemon(true); return t;
    });
    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile boolean connected;
    private volatile boolean leaving;
    private String myNick = "";
    private String shotcallerNick = "";
    private List<String> lastUsers = new ArrayList<>();

    public DawnClient() {
        super("DawnRoom - MLBB Map Meeting");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        for (String r : Board.ROLES) roleHolders.put(r, "");
        buildUi();
        getContentPane().setBackground(Theme.BG);
        wireActions();
        setConnectedUi(false);
        setSize(1220, 760);
        setMinimumSize(new Dimension(1000, 640));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUi() {
        // header + connection row
        JLabel logo = new JLabel("DAWNROOM");
        logo.setFont(logo.getFont().deriveFont(Font.BOLD, 24f));
        logo.setForeground(Theme.ACCENT);
        JLabel tagline = new JLabel("MLBB MAP MEETING  \u2022  LAN");
        tagline.setFont(tagline.getFont().deriveFont(Font.BOLD, 11f));
        tagline.setForeground(Theme.MUTED);
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        brand.setBackground(Theme.PANEL);
        brand.setBorder(BorderFactory.createMatteBorder(0, 0, 3, 0, Theme.ACCENT2));
        brand.add(logo); brand.add(tagline);

        JPanel conn = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        conn.setBackground(Theme.BG);
        conn.add(new JLabel("Server IP:")); conn.add(hostField);
        conn.add(new JLabel("Port:")); conn.add(portField);
        conn.add(new JLabel("Nickname:")); conn.add(nickField);
        conn.add(connectBtn);
        status.setForeground(Theme.MUTED);
        conn.add(status);
        JPanel north = new JPanel(new BorderLayout());
        north.add(brand, BorderLayout.NORTH);
        north.add(conn, BorderLayout.CENTER);

        // tactical map view
        JPanel kindRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        kindRow.setBackground(Theme.BG);
        kindRow.add(new JLabel("Call:"));
        ButtonGroup kinds = new ButtonGroup();
        for (String k : Board.KINDS) {
            Theme.Chip chip = new Theme.Chip(k.charAt(0) + k.substring(1).toLowerCase(), Theme.kindColor(k));
            chip.setSelected(k.equals(selectedKind));
            chip.addActionListener(e -> selectedKind = k);
            kinds.add(chip);
            kindRow.add(chip);
        }
        kindRow.add(undoCallBtn);
        kindRow.add(clearMapBtn);
        zoneLabel.setForeground(Theme.MUTED);
        zoneLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
        JPanel mapView = new JPanel(new BorderLayout());
        mapView.setBackground(Theme.BG);
        mapView.add(kindRow, BorderLayout.NORTH);
        mapView.add(mapPanel, BorderLayout.CENTER);
        mapView.add(zoneLabel, BorderLayout.SOUTH);

        // lane lineup view
        lineupHeader.setFont(lineupHeader.getFont().deriveFont(Font.BOLD, 16f));
        lineupHeader.setForeground(Theme.ACCENT);
        lineupHeader.setBorder(new EmptyBorder(10, 4, 10, 4));
        rolesPanel.setLayout(new BoxLayout(rolesPanel, BoxLayout.Y_AXIS));
        rolesPanel.setBackground(Theme.BG);
        JPanel rolesWrap = new JPanel(new BorderLayout());
        rolesWrap.setBackground(Theme.BG);
        rolesWrap.add(rolesPanel, BorderLayout.NORTH);
        JPanel lineupButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        lineupButtons.setBackground(Theme.BG);
        lineupButtons.add(releaseRoleBtn);
        lineupButtons.add(clearLineupBtn);
        JPanel lineupView = new JPanel(new BorderLayout(6, 6));
        lineupView.setBackground(Theme.BG);
        lineupView.setBorder(new EmptyBorder(6, 8, 6, 8));
        lineupView.add(lineupHeader, BorderLayout.NORTH);
        lineupView.add(rolesWrap, BorderLayout.CENTER);
        lineupView.add(lineupButtons, BorderLayout.SOUTH);

        cardHost.setBackground(Theme.BG);
        cardHost.add(mapView, "map");
        cardHost.add(lineupView, "lineup");

        // view switcher
        Theme.Chip mapNav = new Theme.Chip("Tactical Map", Theme.ACCENT2);
        Theme.Chip lineupNav = new Theme.Chip("Lane Lineup", Theme.ACCENT2);
        mapNav.setSelected(true);
        mapNav.addActionListener(e -> cards.show(cardHost, "map"));
        lineupNav.addActionListener(e -> cards.show(cardHost, "lineup"));
        ButtonGroup navGroup = new ButtonGroup();
        navGroup.add(mapNav); navGroup.add(lineupNav);
        JPanel navRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        navRow.setBackground(Theme.BG);
        navRow.add(mapNav); navRow.add(lineupNav);
        JPanel left = new JPanel(new BorderLayout());
        left.setBackground(Theme.BG);
        left.setBorder(new EmptyBorder(0, 6, 6, 0));
        left.add(navRow, BorderLayout.NORTH);
        left.add(cardHost, BorderLayout.CENTER);

        // chat panel
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setMargin(new Insets(6, 8, 6, 8));
        userScroll.setBorder(new TitledBorder("Online (0)"));
        userScroll.setPreferredSize(new Dimension(0, 130));
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.setBackground(Theme.BG);
        inputRow.add(inputField, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);
        JPanel chat = new JPanel(new BorderLayout(6, 6));
        chat.setBackground(Theme.BG);
        chat.setBorder(new EmptyBorder(6, 6, 6, 8));
        chat.setPreferredSize(new Dimension(370, 0));
        chat.add(userScroll, BorderLayout.NORTH);
        chat.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        chat.add(inputRow, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(north, BorderLayout.NORTH);
        add(left, BorderLayout.CENTER);
        add(chat, BorderLayout.EAST);
    }

    private void wireActions() {
        connectBtn.addActionListener(e -> { if (connected) disconnect(); else connect(); });
        nickField.addActionListener(e -> { if (!connected) connect(); });
        sendBtn.addActionListener(e -> sendChat());
        inputField.addActionListener(e -> sendChat());

        mapPanel.setListener(new MapPanel.Listener() {
            @Override public void place(int x, int y) { send(Protocol.build("PING", selectedKind, x, y)); }
            @Override public void hover(String zone) { zoneLabel.setText(zone.isEmpty() ? HINT : "Zone: " + zone); }
        });
        undoCallBtn.addActionListener(e -> send(Protocol.build("PINGUNDO")));
        clearMapBtn.addActionListener(e -> send(Protocol.build("PINGCLEAR")));
        releaseRoleBtn.addActionListener(e -> send(Protocol.build("ROLEFREE")));
        clearLineupBtn.addActionListener(e -> send(Protocol.build("ROLECLEAR")));
    }

    private boolean iAmShotcaller() { return connected && !myNick.isEmpty() && myNick.equals(shotcallerNick); }

    private boolean holdsRole() { return !myNick.isEmpty() && roleHolders.containsValue(myNick); }

    /** Enables/disables controls from the current connection, shotcaller and lineup state. */
    private void refreshButtons() {
        boolean cap = iAmShotcaller();
        mapPanel.setEnabled(connected);
        undoCallBtn.setEnabled(connected);
        clearMapBtn.setEnabled(cap);
        releaseRoleBtn.setEnabled(connected && holdsRole());
        clearLineupBtn.setEnabled(cap);
        rebuildRoles();
    }

    private void setConnectedUi(boolean on) {
        hostField.setEnabled(!on); portField.setEnabled(!on); nickField.setEnabled(!on);
        connectBtn.setEnabled(true);
        connectBtn.setText(on ? "Disconnect" : "Connect");
        inputField.setEnabled(on); sendBtn.setEnabled(on);
        if (on) inputField.requestFocusInWindow();
        else {
            userModel.clear(); lastUsers = new ArrayList<>();
            userScroll.setBorder(new TitledBorder("Online (0)"));
            for (String r : Board.ROLES) roleHolders.put(r, "");
            shotcallerNick = "";
            mapPanel.setCalls(List.of(), "");
            lineupHeader.setText("Lane Lineup: not connected");
            zoneLabel.setText(HINT);
        }
        refreshButtons();
    }

    // ------------------------------------------------------------------ lane lineup rows

    private void rebuildRoles() {
        rolesPanel.removeAll();
        for (String role : Board.ROLES) {
            String holder = roleHolders.getOrDefault(role, "");
            boolean mine = !holder.isEmpty() && holder.equals(myNick);
            JPanel row = new JPanel(new BorderLayout(12, 0));
            row.setBackground(Theme.PANEL);
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 5, 0, 0, mine ? Theme.GOLD : holder.isEmpty() ? Theme.FIELD : Theme.ACCENT2),
                    new EmptyBorder(12, 14, 12, 14)));

            JPanel names = new JPanel(new GridLayout(2, 1));
            names.setOpaque(false);
            JLabel roleLabel = new JLabel(role);
            roleLabel.setFont(roleLabel.getFont().deriveFont(Font.BOLD, 17f));
            JLabel info = new JLabel(ROLE_INFO.get(role));
            info.setForeground(Theme.MUTED);
            info.setFont(info.getFont().deriveFont(12f));
            names.add(roleLabel); names.add(info);

            String who = holder.isEmpty() ? "OPEN" : holder + (mine ? " (you)" : "") + (holder.equals(shotcallerNick) ? "  [SHOTCALLER]" : "");
            JLabel whoLabel = new JLabel(who);
            whoLabel.setFont(whoLabel.getFont().deriveFont(Font.BOLD, 16f));
            whoLabel.setForeground(holder.isEmpty() ? Theme.MUTED : mine ? Theme.GOLD : Theme.TEXT);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            btns.setOpaque(false);
            Theme.Btn claim = new Theme.Btn(mine ? "Yours" : "Claim");
            claim.setEnabled(connected && !mine);
            claim.addActionListener(e -> send(Protocol.build("ROLE", role)));
            btns.add(claim);
            if (iAmShotcaller()) {
                Theme.Btn assign = new Theme.Btn("Assign...");
                assign.addActionListener(e -> assignRole(role));
                btns.add(assign);
            }

            row.add(names, BorderLayout.WEST);
            row.add(whoLabel, BorderLayout.CENTER);
            row.add(btns, BorderLayout.EAST);
            rolesPanel.add(row);
            rolesPanel.add(Box.createVerticalStrut(8));
        }
        rolesPanel.revalidate();
        rolesPanel.repaint();
    }

    private void assignRole(String role) {
        if (lastUsers.isEmpty()) return;
        Object pick = JOptionPane.showInputDialog(this, "Assign " + role + " to:", "Assign role",
                JOptionPane.PLAIN_MESSAGE, null, lastUsers.toArray(), lastUsers.get(0));
        if (pick != null) send(Protocol.build("ROLEASSIGN", role, pick));
    }

    // ------------------------------------------------------------------ connecting

    private void connect() {
        String host = hostField.getText().trim();
        String portTxt = portField.getText().trim();
        String nick = nickField.getText().trim();
        if (host.isEmpty()) { showError("Enter the server IP address (or hostname)."); return; }
        if (host.matches("[0-9.]+") && !host.matches("((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)")) {
            showError("'" + host + "' is not a valid IP address."); return;
        }
        int port;
        try { port = Integer.parseInt(portTxt); }
        catch (NumberFormatException e) { showError("Port must be a number between 1 and 65535."); return; }
        if (port < 1 || port > 65535) { showError("Port must be between 1 and 65535."); return; }
        if (nick.isEmpty()) { showError("Enter a nickname."); return; }

        connectBtn.setEnabled(false);
        status.setText("Connecting to " + host + ":" + port + " ...");
        Thread t = new Thread(() -> runConnection(host, port, nick), "net-reader");
        t.setDaemon(true);
        t.start();
    }

    /** Runs on the reader thread: connect, join, then read lines until the connection ends. */
    private void runConnection(String host, int port, String nick) {
        Socket s = new Socket();
        boolean joined = false;
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            pw.println(Protocol.build("JOIN", nick));

            s.setSoTimeout(5000);
            String first = in.readLine();
            s.setSoTimeout(0);
            if (first == null) throw new IOException("The server closed the connection.");
            List<String> reply = Protocol.parse(first);
            if (reply.get(0).equals("ERR")) { failConnect(reply.size() > 1 ? reply.get(1) : "Join refused."); return; }

            socket = s; out = pw; connected = true; leaving = false;
            SwingUtilities.invokeLater(() -> {
                myNick = nick;
                setConnectedUi(true);
                status.setText("Connected as " + nick);
                chatArea.setText("");
                appendChat("* Connected to " + host + ":" + port + " as " + nick + ". Type /help for commands.");
            });
            joined = true;

            String line;
            while ((line = in.readLine()) != null) {
                List<String> p = Protocol.parse(line);
                SwingUtilities.invokeLater(() -> handle(p));
                if (p.get(0).equals("BYE")) { leaving = true; break; }
            }
            if (!leaving) SwingUtilities.invokeLater(() -> showError("The connection to the server was lost."));
        } catch (UnknownHostException e) {
            failConnect("Unknown host '" + host + "'. Check the IP address.");
        } catch (ConnectException e) {
            failConnect("Could not reach " + host + ":" + port + ". Is the server running and the port correct?");
        } catch (SocketTimeoutException e) {
            failConnect("Connection to " + host + ":" + port + " timed out.");
        } catch (IOException e) {
            if (joined && !leaving) SwingUtilities.invokeLater(() -> showError("Connection error: " + e.getMessage()));
            else if (!joined) failConnect("Could not connect: " + e.getMessage());
        } finally {
            connected = false;
            try { s.close(); } catch (IOException ignored) {}
            if (joined) SwingUtilities.invokeLater(() -> {
                setConnectedUi(false);
                status.setText("Disconnected");
                appendChat("* Disconnected.");
            });
        }
    }

    private void failConnect(String msg) {
        SwingUtilities.invokeLater(() -> {
            connectBtn.setEnabled(true);
            status.setText("Disconnected");
            showError(msg);
        });
    }

    private void disconnect() {
        if (!connected) return;
        leaving = true;
        send(Protocol.build("MSG", "/exit"));
    }

    private void shutdown() {
        if (connected) {
            leaving = true;
            final PrintWriter pw = out; final Socket s = socket;
            sender.execute(() -> {
                if (pw != null) pw.println(Protocol.build("MSG", "/exit"));
                try { if (s != null) s.close(); } catch (IOException ignored) {}
            });
        }
        sender.shutdown();
        try { sender.awaitTermination(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------ sending

    private void send(String line) {
        final PrintWriter pw = out;
        if (!connected || pw == null) { status.setText("Not connected."); return; }
        sender.execute(() -> pw.println(line)); // off the EDT
    }

    private void sendChat() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) { status.setText("Cannot send a blank message."); return; }
        inputField.setText("");
        send(Protocol.build("MSG", text));
    }

    // ------------------------------------------------------------------ incoming (always on the EDT)

    private void handle(List<String> p) {
        try {
            switch (p.get(0)) {
                case "CHAT" -> appendChat("[" + p.get(1) + "] " + p.get(2) + ": " + p.get(3));
                case "SYS" -> appendChat("[" + p.get(1) + "] * " + p.get(2));
                case "ERR" -> { appendChat("! " + p.get(1)); status.setText(p.get(1)); }
                case "YOU" -> { myNick = p.get(1); status.setText("You are now " + myNick); refreshButtons(); }
                case "USERS" -> { lastUsers = new ArrayList<>(p.subList(1, p.size())); refreshUsers(); }
                case "SHOTCALLER" -> { shotcallerNick = p.get(1); refreshUsers(); refreshButtons(); updateLineupHeader(); }
                case "ROLES" -> readRoles(p);
                case "CALLS" -> readCalls(p);
                default -> { /* BYE and unknown types need no UI update */ }
            }
        } catch (RuntimeException ex) {
            System.err.println("Bad message from server: " + p + " -> " + ex);
        }
    }

    private void refreshUsers() {
        userModel.clear();
        for (String u : lastUsers) {
            String label = u;
            if (u.equals(shotcallerNick)) label += "  [SHOTCALLER]";
            if (u.equals(myNick)) label += "  (you)";
            userModel.addElement(label);
        }
        userScroll.setBorder(new TitledBorder("Online (" + lastUsers.size() + ")"));
        userScroll.repaint();
    }

    private void readRoles(List<String> p) {
        int n = Integer.parseInt(p.get(1));
        for (int k = 0, i = 2; k < n; k++, i += 2) roleHolders.put(p.get(i), p.get(i + 1));
        updateLineupHeader();
        refreshButtons();
    }

    private void updateLineupHeader() {
        long filled = roleHolders.values().stream().filter(v -> !v.isEmpty()).count();
        lineupHeader.setText("Lane Lineup: " + filled + " / " + Board.ROLES.size() + " roles filled"
                + (shotcallerNick.isEmpty() ? "" : "   |   Shotcaller: " + shotcallerNick));
    }

    private void readCalls(List<String> p) {
        int n = Integer.parseInt(p.get(1));
        List<MapPanel.CallView> list = new ArrayList<>();
        for (int k = 0, i = 2; k < n; k++, i += 7) {
            list.add(new MapPanel.CallView(Integer.parseInt(p.get(i)), p.get(i + 1),
                    Integer.parseInt(p.get(i + 2)), Integer.parseInt(p.get(i + 3)),
                    p.get(i + 4), p.get(i + 5), p.get(i + 6)));
        }
        mapPanel.setCalls(list, myNick);
    }

    private void appendChat(String line) {
        chatArea.append(line + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private void showError(String msg) {
        status.setText(msg);
        JOptionPane.showMessageDialog(this, msg, "DawnRoom", JOptionPane.WARNING_MESSAGE);
    }

    public static void main(String[] args) {
        Theme.install();
        SwingUtilities.invokeLater(() -> new DawnClient().setVisible(true));
    }
}
