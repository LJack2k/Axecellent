import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Minecraft RCON client - how an agent drives the dev server headlessly.
 *
 * <p>Run it directly with the JDK's single-file source mode (no build step):
 * <pre>
 *   java tools/Rcon.java "axecellent give"
 *   java tools/Rcon.java "time query daytime" "gamerule doMobSpawning"
 * </pre>
 * Defaults match what :neoforge:prepareDevServer seeds into run-server:
 * localhost:25575, password "axe". Override with -Drcon.host / -Drcon.port /
 * -Drcon.password.
 *
 * <p>Each argument is one command, sent in order; the server's reply is printed.
 * Do not prefix commands with "/" - RCON runs them as the console.
 */
public final class Rcon implements AutoCloseable {
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_LOGIN = 3;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int requestId = 0;

    private Rcon(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(10_000);
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    private String send(int type, String body) throws IOException {
        int id = ++requestId;
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + payload.length);   // length excludes itself
        buf.putInt(id);
        buf.putInt(type);
        buf.put(payload);
        buf.put((byte) 0).put((byte) 0);   // body + packet terminators
        out.write(buf.array());
        out.flush();

        int length = Integer.reverseBytes(in.readInt());
        int responseId = Integer.reverseBytes(in.readInt());
        Integer.reverseBytes(in.readInt());   // response type, unused
        byte[] response = new byte[Math.max(0, length - 10)];
        in.readFully(response);
        in.readFully(new byte[2]);            // terminators
        if (responseId == -1) {
            throw new IOException("RCON auth failed - wrong password?");
        }
        return new String(response, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("rcon.host", "localhost");
        int port = Integer.getInteger("rcon.port", 25575);
        String password = System.getProperty("rcon.password", "axe");
        if (args.length == 0) {
            System.err.println("usage: java tools/Rcon.java \"<command>\" [\"<command>\" ...]");
            System.exit(2);
        }
        try (Rcon rcon = new Rcon(host, port)) {
            rcon.send(TYPE_LOGIN, password);
            for (String command : args) {
                String reply = rcon.send(TYPE_COMMAND, command);
                System.out.println("> " + command);
                System.out.println(reply.isBlank() ? "(no output)" : reply);
            }
        }
    }
}
