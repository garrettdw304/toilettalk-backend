import java.io.*;
import java.net.*;

/**
 * This class allows commands to be sent to the server over TCP.
 */
public class ConfigServer {
    public static void start() {
        try (ServerSocket socket = new ServerSocket(9501)) {
            while (true) {
                Socket clientSocket = socket.accept();
                clientSocket.setSoTimeout(0);

                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintStream out = new PrintStream(clientSocket.getOutputStream(), true);

                String input;
                while ((input = in.readLine()) != null) {
                    System.out.println("Executing command from ConfigServer: " + input);
                    if (Commands.execute(input, out)) {
                        out.println("Shutting down, may take ~10 seconds.");
                        clientSocket.close();
                        return;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
