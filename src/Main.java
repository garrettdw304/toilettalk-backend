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

        HttpServer server = null;

        if (Env.INIT_SUCCESSFUL) {
            try {
                server = HttpServer.create(new InetSocketAddress(9500), -1);
            } catch (IOException e) {
                throw new Error(e);
            }

            server.createContext("/api/signUp",
                    e -> ReqHandlers.handleUncaughtExceptions(ReqHandlers::signUp, e));
            server.createContext("/api/signIn",
                    e -> ReqHandlers.handleUncaughtExceptions(ReqHandlers::signIn, e));
            server.createContext("/api/refreshAccess",
                    e -> ReqHandlers.handleUncaughtExceptions(ReqHandlers::refreshAccess, e));
            server.createContext("/api/getReviews",
                    e -> ReqHandlers.handleUncaughtExceptions(ReqHandlers::getReviews, e));
            server.createContext("/api/createReview",
                    e -> ReqHandlers.handleUncaughtExceptions(ReqHandlers::createReview, e));
            server.createContext("/api/getBathrooms",
                    e -> ReqHandlers.handleUncaughtExceptions(ReqHandlers::getBathrooms, e));
            server.createContext("/api/getBuildings",
                    e -> ReqHandlers.handleUncaughtExceptions(ReqHandlers::getBathrooms, e));

            server.start();
        } else {
            System.err.println("ENV initialization failed. THE SERVER IS NOT RUNNING! Fix the errors and relaunch the program.");
        }

        try {
            Commands.Repl();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (Env.INIT_SUCCESSFUL) {
            System.out.println("Stopping, this may take ~10 seconds.");
            server.stop(10);
        }
    }
}
