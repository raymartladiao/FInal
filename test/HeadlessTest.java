import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * Optional automated check of the SERVER over real TCP sockets (no GUI).
 * Run:  java -cp out:test-out HeadlessTest      (start nothing else; it launches its own server on port 5055)
 * It does not replace the manual two-computer LAN demo, but it proves the protocol rules quickly.
 */
public class HeadlessTest {
    static int pass = 0, fail = 0;

    static class Cli {
        final Socket s; final PrintWriter out; final BlockingQueue<List<String>> in = new LinkedBlockingQueue<>();
        final String nick;
        Cli(String nick, int port) throws Exception {
            this.nick = nick;
            s = new Socket("localhost", port);
            out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Thread t = new Thread(() -> {
                try { String l; while ((l = r.readLine()) != null) in.add(Protocol.parse(l)); } catch (IOException ignored) {}
            });
            t.setDaemon(true); t.start();
            send("JOIN", nick);
        }
        void send(String type, Object... f) { out.println(Protocol.build(type, f)); }
        /** Consumes messages until one of this type matches; returns null on timeout. */
        List<String> wait(String type, Predicate<List<String>> cond) throws Exception {
            long end = System.currentTimeMillis() + 2500;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return null;
                List<String> m = in.poll(left, TimeUnit.MILLISECONDS);
                if (m == null) return null;
                if (m.get(0).equals(type) && cond.test(m)) return m;
            }
        }
        List<String> wait(String type) throws Exception { return wait(type, m -> true); }
        void drain() throws Exception { Thread.sleep(150); in.clear(); }
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (ok) pass++; else fail++;
    }

    static String roleHolder(List<String> rolesMsg, String role) {
        for (int i = 2; i + 1 < rolesMsg.size(); i += 2) if (rolesMsg.get(i).equals(role)) return rolesMsg.get(i + 1);
        return null;
    }

    static int callsBy(List<String> calls, String nick) {
        int n = Integer.parseInt(calls.get(1)), c = 0;
        for (int k = 0, i = 2; k < n; k++, i += 7) if (calls.get(i + 4).equals(nick)) c++;
        return c;
    }

    public static void main(String[] args) throws Exception {
        int port = 5055;
        Thread srv = new Thread(() -> new DawnServer(port).start());
        srv.setDaemon(true); srv.start();
        Thread.sleep(600);

        Cli a = new Cli("Alpha", port);
        check("A joins (OK)", a.wait("OK") != null);
        Thread.sleep(100);
        Cli b = new Cli("Bravo", port);
        Cli c = new Cli("Charlie", port);
        check("B joins (OK)", b.wait("OK") != null);
        check("C joins (OK)", c.wait("OK") != null);
        check("Online list shows 3 users on every client",
                a.wait("USERS", m -> m.size() == 4) != null && b.wait("USERS", m -> m.size() == 4) != null && c.wait("USERS", m -> m.size() == 4) != null);
        check("First player is shotcaller", c.wait("SHOTCALLER", m -> m.get(1).equals("Alpha")) != null);

        Cli dup = new Cli("alpha", port);
        List<String> dupErr = dup.wait("ERR");
        check("Duplicate nickname rejected at join", dupErr != null && dupErr.get(1).contains("already in use"));

        a.drain(); b.drain(); c.drain();
        a.send("MSG", "   ");
        check("Blank message rejected", a.wait("ERR", m -> m.get(1).contains("blank")) != null);
        a.send("MSG", "hello team");
        boolean all = a.wait("CHAT", m -> m.get(3).equals("hello team")) != null && b.wait("CHAT", m -> m.get(3).equals("hello team")) != null
                && c.wait("CHAT", m -> m.get(2).equals("Alpha")) != null;
        check("Broadcast reaches all 3 clients incl. sender", all);

        // ---- Feature 1: lane lineup
        a.drain(); b.drain(); c.drain();
        a.send("ROLE", "Mid Lane");
        List<String> r1 = c.wait("ROLES", m -> "Alpha".equals(roleHolder(m, "Mid Lane")));
        check("Lineup: claim syncs to another client", r1 != null);
        b.send("ROLE", "mid lane");
        check("Lineup: taken role refused", b.wait("ERR", m -> m.get(1).contains("already taken")) != null);
        b.send("ROLE", "Jungler");
        check("Lineup: second client claims a free role", a.wait("ROLES", m -> "Bravo".equals(roleHolder(m, "Jungler"))) != null);
        b.send("ROLE", "Roamer");
        List<String> r2 = a.wait("ROLES", m -> "Bravo".equals(roleHolder(m, "Roamer")));
        check("Lineup: claiming a new role frees the old one", r2 != null && roleHolder(r2, "Jungler").isEmpty());
        b.send("ROLEASSIGN", "Jungler", "Charlie");
        check("Lineup: non-shotcaller cannot assign", b.wait("ERR", m -> m.get(1).contains("Only the shotcaller")) != null);
        a.send("ROLEASSIGN", "Jungler", "Charlie");
        check("Lineup: shotcaller assigns a role", c.wait("ROLES", m -> "Charlie".equals(roleHolder(m, "Jungler"))) != null);
        a.send("ROLEASSIGN", "Gold Lane", "Nobody");
        check("Lineup: assign to unknown player refused", a.wait("ERR", m -> m.get(1).contains("No online player")) != null);

        // ---- Feature 2: tactical map
        a.drain(); b.drain(); c.drain();
        b.send("PING", "GANK", 500, 500);
        List<String> k1 = c.wait("CALLS", m -> m.get(1).equals("1"));
        check("Map: call syncs to other client with owner", k1 != null && k1.contains("Bravo") && k1.contains("GANK"));
        check("Map: call zone computed by server (Mid Lane)", k1 != null && k1.contains("Mid Lane"));
        b.send("PING", "NOPE", 10, 10);
        check("Map: unknown call type refused", b.wait("ERR", m -> m.get(1).contains("Unknown call")) != null);
        b.send("PING", "ATTACK", 2000, 10);
        check("Map: out-of-range position refused", b.wait("ERR", m -> m.get(1).contains("outside")) != null);
        b.send("PING", "ATTACK", "abc", 10);
        check("Map: non-numeric position refused", b.wait("ERR", m -> m.get(1).contains("Invalid")) != null);
        for (int i = 0; i < 7; i++) b.send("PING", "DEFEND", 100 + i * 50, 600);
        Thread.sleep(300);
        List<String> last = null, m;
        while ((m = c.in.poll()) != null) if (m.get(0).equals("CALLS")) last = m;
        check("Map: max 5 calls per player (oldest dropped)", last != null && callsBy(last, "Bravo") == 5);
        b.send("PINGUNDO");
        check("Map: undo removes own latest call", c.wait("CALLS", mm -> callsBy(mm, "Bravo") == 4) != null);
        b.send("PINGCLEAR");
        check("Map: non-shotcaller cannot clear", b.wait("ERR", mm -> mm.get(1).contains("Only the shotcaller")) != null);
        a.send("PINGCLEAR");
        check("Map: shotcaller clears map for everyone", c.wait("CALLS", mm -> mm.get(1).equals("0")) != null);

        // ---- rename
        a.drain(); b.drain(); c.drain();
        c.send("MSG", "/name Alpha");
        check("Rename to existing nickname refused", c.wait("ERR", mm -> mm.get(1).contains("already in use")) != null);
        c.send("MSG", "/name Charles");
        check("Valid rename notifies all and updates lineup", a.wait("ROLES", mm -> "Charles".equals(roleHolder(mm, "Jungler"))) != null);

        // ---- abrupt disconnect of the shotcaller
        a.drain(); b.drain(); c.drain();
        a.s.close();
        check("Abrupt disconnect: others see user list shrink", b.wait("USERS", mm -> mm.size() == 3) != null);
        check("Abrupt disconnect: shotcaller passes to next player", c.wait("SHOTCALLER", mm -> mm.get(1).equals("Bravo")) != null);
        check("Abrupt disconnect: leaver's role is freed", b.wait("ROLES", mm -> roleHolder(mm, "Mid Lane").isEmpty()) != null);
        b.drain(); c.drain();
        b.send("MSG", "still here");
        check("Chat continues between remaining clients", c.wait("CHAT", mm -> mm.get(3).equals("still here")) != null);

        // ---- clean exit
        b.send("MSG", "/exit");
        check("Clean /exit: server replies BYE", b.wait("BYE") != null);
        check("Clean /exit: remaining client sees user list shrink", c.wait("USERS", mm -> mm.size() == 2) != null);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
