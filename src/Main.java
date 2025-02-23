import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class Main {
    public static void main(String[] args) {
        try {
            // This initializes the static members of the Env class. We do this so that if there is a problem with them,
            // it happens now instead of on first use of them.
            Class.forName("Env");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(9500), -1);
        } catch (IOException e) {
            throw new Error(e);
        }

        server.createContext("/api/signUp", ReqHandlers::signUp);
        server.createContext("/api/signIn", ReqHandlers::signIn);
        server.createContext("/api/getReviews", ReqHandlers::getReviews);
        server.createContext("/api/createReview", ReqHandlers::createReview);
        server.createContext("/api/getBathrooms", ReqHandlers::getBathrooms);
        server.createContext("/api/getBuildings", ReqHandlers::getBuildings);

        server.start();

        while (true) {
            if (System.console().readLine().equals("exit"))
                break;
            else
                System.out.println("Type 'exit' to close this program.");
        }

        System.out.println("Stopping, this may take ~10 seconds.");
        server.stop(10);
    }
}
