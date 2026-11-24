import java.io.IOException;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DawnRoom server (MLBB Map Meeting).
 *
 * All shared state (clients, lane lineup, map calls) is guarded by this object's monitor: every method that
 * touches it is synchronized, so concurrent client threads can never corrupt it or interleave broadcasts.
 *
 * Roles: the longest-connected player is the SHOTCALLER. Only the shotcaller may assign roles to others,
 * clear the lineup, or clear the map. This is validated here on the server, not just in the GUI.
 */
public class DawnServer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final int MAX_CALLS_PER_PLAYER = 5, MAX_CALLS_TOTAL = 40;

    /** One call (marker) on the tactical map. The owner is stored by client id so renames never break it. */
    record Call(int id, String kind, int x, int y, int owner, String time, String zone) {}

    private final int port;
    private final List<ClientHandler> clients = new ArrayList<>();   // join order; index 0 = shotcaller
    private int nextClientId = 1, nextCallId = 1;

    // Feature 1: lane lineup (role -> client id)
    private final Map<String, Integer> roleOwner = new LinkedHashMap<>();

    // Feature 2: shared tactical map calls (oldest first)
    private final List<Call> calls = new ArrayList<>();

    public DawnServer(int port) { this.port = port; }

    public static void main(String[] args) {
        int port = Protocol.DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "'. Usage: java DawnServer [port]");
                return;
            }
        }
        new DawnServer(port).start();
    }

    public void start() {
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("DawnRoom server listening on port " + port);
            printAddresses();
            while (true) {
                Socket s = ss.accept();
                System.out.println("[server] connection from " + s.getRemoteSocketAddress());
                pool.execute(new ClientHandler(s, this));
            }
        } catch (IOException e) {
            System.err.println("Server error (is port " + port + " already in use?): " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private void printAddresses() {
        try {
            System.out.println("Clients on the LAN can connect using one of these IPs:");
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) System.out.println("  " + a.getHostAddress() + "  (" + ni.getDisplayName() + ")");
                }
            }
        } catch (SocketException ignored) {}
    }

    // ---------------------------------------------------------------- membership

    synchronized int newClientId() { return nextClientId++; }

    private ClientHandler shotcaller() { return clients.isEmpty() ? null : clients.get(0); }

    private ClientHandler byId(int id) {
        for (ClientHandler c : clients) if (c.id == id) return c;
        return null;
    }

    private ClientHandler byNick(String nick) {
        for (ClientHandler c : clients) if (c.nick.equalsIgnoreCase(nick.trim())) return c;
        return null;
    }

    private String validateNick(String nick, ClientHandler self) {
        nick = nick == null ? "" : nick.trim();
        if (nick.isEmpty()) return "Nickname cannot be blank.";
        if (!nick.matches("[A-Za-z0-9_.\\-]{1,20}"))
            return "Nickname must be 1-20 characters: letters, numbers, _ . -";
        for (ClientHandler c : clients) {
            if (c != self && c.nick.equalsIgnoreCase(nick)) return "Nickname '" + nick + "' is already in use.";
        }
        return null;
    }

    /** Returns an error message, or null on success. */
    synchronized String register(ClientHandler h, String nick) {
        String err = validateNick(nick, h);
        if (err != null) return err;
        h.nick = nick.trim();
        clients.add(h);
        System.out.println("[server] " + h.nick + " joined (" + clients.size() + " online)");
        h.send(Protocol.build("OK", h.nick));
        broadcastSys(h.nick + " joined the room.");
        broadcastUsers();
        if (clients.size() == 1) broadcastSys(h.nick + " is the shotcaller.");
        broadcast(shotcallerLine());
        h.send(rolesLine());
        h.send(callsLine());
        return null;
    }

    synchronized void unregister(ClientHandler h) {
        ClientHandler oldCaller = shotcaller();
        if (!clients.remove(h)) return;
        roleOwner.values().removeIf(v -> v == h.id);
        calls.removeIf(c -> c.owner() == h.id);
        System.out.println("[server] " + h.nick + " left (" + clients.size() + " online)");
        broadcastSys(h.nick + " left the room.");
        if (clients.isEmpty()) {            // empty room: reset everything
            roleOwner.clear(); calls.clear();
            return;
        }
        if (shotcaller() != oldCaller) broadcastSys(shotcaller().nick + " is now the shotcaller.");
        broadcastUsers();
        broadcast(shotcallerLine());
        broadcast(rolesLine());
        broadcast(callsLine());
    }

    // ---------------------------------------------------------------- request handling

    synchronized void process(ClientHandler h, List<String> p) {
        String type = p.get(0).toUpperCase();
        switch (type) {
            case "MSG" -> handleMsg(h, arg(p, 1));
            case "ROLE" -> handleRoleClaim(h, arg(p, 1));
            case "ROLEFREE" -> handleRoleFree(h);
            case "ROLEASSIGN" -> handleRoleAssign(h, arg(p, 1), arg(p, 2));
            case "ROLECLEAR" -> handleRoleClear(h);
            case "PING" -> handlePing(h, arg(p, 1), arg(p, 2), arg(p, 3));
            case "PINGUNDO" -> handlePingUndo(h);
            case "PINGCLEAR" -> handlePingClear(h);
            default -> err(h, "Unknown request: " + type);
        }
    }

    private void handleMsg(ClientHandler h, String raw) {
        String text = raw.trim();
        if (text.isEmpty()) { err(h, "Message cannot be blank."); return; }
        if (text.length() > 500) { err(h, "Message is too long (max 500 characters)."); return; }
        if (text.startsWith("/")) { handleCommand(h, text); return; }
        broadcast(Protocol.build("CHAT", now(), h.nick, text));
    }

    private void handleCommand(ClientHandler h, String text) {
        String[] cmd = text.split("\\s+", 2);
        switch (cmd[0].toLowerCase()) {
            case "/name" -> {
                if (cmd.length < 2) { err(h, "Usage: /name <newname>"); return; }
                String newName = cmd[1].trim();
                if (newName.equals(h.nick)) { err(h, "That is already your nickname."); return; }
                String e = validateNick(newName, h);
                if (e != null) { err(h, e); return; }
                String old = h.nick;
                h.nick = newName;
                h.send(Protocol.build("YOU", newName));
                broadcastSys(old + " is now known as " + newName + ".");
                broadcastUsers();
                broadcast(shotcallerLine());
                broadcast(rolesLine());
                broadcast(callsLine());
            }
            case "/exit" -> { h.send(Protocol.build("BYE")); h.closing = true; }
            case "/help" -> {
                h.send(Protocol.build("SYS", now(), "Commands: /name <newname>  /exit  /help"));
                h.send(Protocol.build("SYS", now(), "Lane Lineup: claim one role each; the shotcaller can assign roles."));
                h.send(Protocol.build("SYS", now(), "Tactical Map: pick a call type, then click the map to mark a spot."));
            }
            default -> err(h, "Unknown command '" + cmd[0] + "'. Type /help.");
        }
    }

    // ---- Feature 1: lane lineup

    private void handleRoleClaim(ClientHandler h, String roleArg) {
        String role = canonical(Board.ROLES, roleArg);
        if (role == null) { err(h, "Unknown role."); return; }
        Integer owner = roleOwner.get(role);
        if (owner != null && owner == h.id) { err(h, "You already hold " + role + "."); return; }
        if (owner != null) {
            ClientHandler o = byId(owner);
            err(h, role + " is already taken by " + (o == null ? "someone" : o.nick) + ".");
            return;
        }
        roleOwner.values().removeIf(v -> v == h.id);   // one role per player: claiming a new one frees the old one
        roleOwner.put(role, h.id);
        broadcastSys(h.nick + " took " + role + ".");
        broadcast(rolesLine());
        announceIfComplete();
    }

    private void handleRoleFree(ClientHandler h) {
        if (!roleOwner.containsValue(h.id)) { err(h, "You do not hold a role."); return; }
        roleOwner.values().removeIf(v -> v == h.id);
        broadcastSys(h.nick + " released their role.");
        broadcast(rolesLine());
    }

    private void handleRoleAssign(ClientHandler h, String roleArg, String nick) {
        if (h != shotcaller()) { err(h, "Only the shotcaller can assign roles."); return; }
        String role = canonical(Board.ROLES, roleArg);
        if (role == null) { err(h, "Unknown role."); return; }
        ClientHandler target = byNick(nick);
        if (target == null) { err(h, "No online player named '" + nick + "'."); return; }
        Integer owner = roleOwner.get(role);
        if (owner != null && owner == target.id) { err(h, target.nick + " already holds " + role + "."); return; }
        roleOwner.values().removeIf(v -> v == target.id);   // target gives up any other role
        roleOwner.put(role, target.id);                     // replaces the previous holder, if any
        broadcastSys("Shotcaller " + h.nick + " assigned " + role + " to " + target.nick + ".");
        broadcast(rolesLine());
        announceIfComplete();
    }

    private void handleRoleClear(ClientHandler h) {
        if (h != shotcaller()) { err(h, "Only the shotcaller can clear the lineup."); return; }
        roleOwner.clear();
        broadcastSys("Shotcaller " + h.nick + " cleared the lineup.");
        broadcast(rolesLine());
    }

    private void announceIfComplete() {
        if (roleOwner.size() != Board.ROLES.size()) return;
        StringBuilder sb = new StringBuilder("LINEUP COMPLETE - ");
        for (String r : Board.ROLES) {
            ClientHandler o = byId(roleOwner.get(r));
            sb.append(r).append(": ").append(o == null ? "?" : o.nick).append("  ");
        }
        broadcastSys(sb.toString().trim());
    }

    // ---- Feature 2: shared tactical map

    private void handlePing(ClientHandler h, String kindArg, String xs, String ys) {
        String kind = canonical(Board.KINDS, kindArg);
        if (kind == null) { err(h, "Unknown call type."); return; }
        int x, y;
        try { x = Integer.parseInt(xs.trim()); y = Integer.parseInt(ys.trim()); }
        catch (NumberFormatException e) { err(h, "Invalid map position."); return; }
        if (!Board.inside(x, y)) { err(h, "That position is outside the map."); return; }

        int mine = 0;
        for (Call c : calls) if (c.owner() == h.id) mine++;
        while (mine >= MAX_CALLS_PER_PLAYER) {          // keep only each player's newest calls
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).owner() == h.id) { calls.remove(i); break; }
            }
            mine--;
        }
        if (calls.size() >= MAX_CALLS_TOTAL) calls.remove(0);

        String zone = Board.zoneAt(x, y);
        calls.add(new Call(nextCallId++, kind, x, y, h.id, now(), zone));
        broadcastSys(h.nick + " called " + kind + " at " + zone + ".");
        broadcast(callsLine());
    }

    private void handlePingUndo(ClientHandler h) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (calls.get(i).owner() == h.id) {
                calls.remove(i);
                broadcast(callsLine());
                return;
            }
        }
        err(h, "You have no calls on the map.");
    }

    private void handlePingClear(ClientHandler h) {
        if (h != shotcaller()) { err(h, "Only the shotcaller can clear the map."); return; }
        calls.clear();
        broadcastSys("Shotcaller " + h.nick + " cleared the map.");
        broadcast(callsLine());
    }

    // ---------------------------------------------------------------- helpers

    private static String arg(List<String> p, int i) { return i < p.size() ? p.get(i) : ""; }
    private static String now() { return LocalTime.now().format(TIME); }
    private void err(ClientHandler h, String msg) { h.send(Protocol.build("ERR", msg)); }

    private static String canonical(List<String> options, String s) {
        for (String o : options) if (o.equalsIgnoreCase(s.trim())) return o;
        return null;
    }

    private void broadcast(String line) { for (ClientHandler c : new ArrayList<>(clients)) c.send(line); }
    private void broadcastSys(String text) { broadcast(Protocol.build("SYS", now(), text)); }

    private void broadcastUsers() {
        List<Object> f = new ArrayList<>();
        for (ClientHandler c : clients) f.add(c.nick);
        broadcast(Protocol.build("USERS", f.toArray()));
    }

    private String shotcallerLine() { return Protocol.build("SHOTCALLER", shotcaller() == null ? "" : shotcaller().nick); }

    /** ROLES|n|(role,holderNick or empty)* */
    private String rolesLine() {
        List<Object> f = new ArrayList<>();
        f.add(Board.ROLES.size());
        for (String r : Board.ROLES) {
            Integer id = roleOwner.get(r);
            ClientHandler o = id == null ? null : byId(id);
            f.add(r); f.add(o == null ? "" : o.nick);
        }
        return Protocol.build("ROLES", f.toArray());
    }

    /** CALLS|n|(id,kind,x,y,nick,time,zone)* */
    private String callsLine() {
        List<Object> f = new ArrayList<>();
        f.add(calls.size());
        for (Call c : calls) {
            ClientHandler o = byId(c.owner());
            f.add(c.id()); f.add(c.kind()); f.add(c.x()); f.add(c.y());
            f.add(o == null ? "?" : o.nick); f.add(c.time()); f.add(c.zone());
        }
        return Protocol.build("CALLS", f.toArray());
    }
}
