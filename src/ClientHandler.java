import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** One instance (one thread) per connected client. Reads lines and hands them to the server. */
public class ClientHandler implements Runnable {
    final Socket socket;
    final DawnServer server;
    final int id;
    volatile String nick = "?";
    volatile boolean closing = false;
    private PrintWriter out;

    ClientHandler(Socket socket, DawnServer server) {
        this.socket = socket;
        this.server = server;
        this.id = server.newClientId();
    }

    /** Synchronized so two threads broadcasting at once never interleave their lines. */
    synchronized void send(String line) {
        if (out != null) out.println(line);
    }

    @Override
    public void run() {
        boolean joined = false;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            socket.setSoTimeout(15000); // a client must send JOIN quickly
            String first = in.readLine();
            socket.setSoTimeout(0);
            if (first == null) return;
            List<String> p = Protocol.parse(first);
            if (!p.get(0).equalsIgnoreCase("JOIN") || p.size() < 2) {
                send(Protocol.build("ERR", "Expected JOIN first."));
                return;
            }
            String err = server.register(this, p.get(1));
            if (err != null) {
                send(Protocol.build("ERR", err));
                return;
            }
            joined = true;

            String line;
            while (!closing && (line = in.readLine()) != null) {
                server.process(this, Protocol.parse(line));
            }
        } catch (IOException e) {
            System.out.println("[server] connection lost: " + nick + " (" + e.getMessage() + ")");
        } finally {
            if (joined) server.unregister(this);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
