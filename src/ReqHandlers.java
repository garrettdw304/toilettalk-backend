import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.mongodb.client.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bson.Document;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/*
 * This file defines Request Handlers that will be called by the HttpServer when a request with a specific context
 * is received. It is suggested to wrap the entire contents of a handler in a try-catch that catches all Exceptions and
 * at least print them to System.err. This is because the executor that the HttpServer uses simply eats up any unhandled
 * exceptions without any indication of an exception even occurring which can be hard to debug.
 * See ReqHandlers.printUncaughtExceptions to do this without wrapping an entire function's contents in a try-catch.
 */

public class ReqHandlers {
    private static final int REVIEWS_PER_PAGE = 10;
    private static final int BASE_PAGE_NUMBER = 1; // Page numbers start at 1

    private static final byte[] INVALID_METHOD_RESPONSE =
            "{ \"error\": \"Method not allowed.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTERNAL_ERROR_RESPONSE =
            "{ \"error\": \"Internal error occurred (no fault of the client).\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_EMAIL_RESPONSE =
            "{ \"error\": \"Email is invalid.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_USERNAME_RESPONSE =
            "{ \"error\": \"Username is invalid.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_PASSWORD_RESPONSE =
            "{ \"error\": \"Password is invalid.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMAIL_ALREADY_EXISTS_RESPONSE =
            "{ \"error\": \"User with that email already exists.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] USERNAME_ALREADY_EXISTS_RESPONSE =
            "{ \"error\": \"User with that username already exists.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAUTHORIZED_SIGN_IN_RESPONSE =
            "{ \"error\": \"Invalid username or password.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BATHROOM_ID_NOT_PRESENT_RESPONSE =
            "{ \"error\": \"Bathroom id not present in request.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAUTHORIZED_REFRESH =
            "{ \"error\": \"Token sent could not authorize an access refresh.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAUTHORIZED_CREATE_REVIEW =
            "{ \"error\": \"Token sent could not authorize a create review.\" }".getBytes(StandardCharsets.UTF_8);

    /**
     * Helper method that calls an HttpHandler with the provided HttpExchange
     * and catches and prints all uncaught exceptions.
     * Also closes the exchange after the handler returns or an exception is caught to keep from freezing up the client.
     * @param handler The handler to call.
     * @param e The exchange to pass to the handler when calling it.
     */
    public static void handleUncaughtExceptions(HttpHandler handler, HttpExchange e) {
        try (e) {
            handler.handle(e);
        } catch (Exception ex) {
            printException(e, ex, "This exception was uncaught (and then caught by handleUncaughtExceptions).");
        }
    }

    public static void signUp(HttpExchange e) throws IOException {
        System.out.println("Received /api/signUp request");
        try {
            if (!ensureMethod(e, "POST")) {
                System.out.println("Method not POST, returning");
                return;
            }
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }
    
        String email;
        String username;
        String password;
        try {
            System.out.println("Parsing request body");
            Document req = getReqDoc(e.getRequestBody());
            email = req.getString("email");
            username = req.getString("username");
            password = req.getString("password");
            System.out.println("Request parsed: email=" + email + ", username=" + username);
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }
    
        try {
            System.out.println("Calling Auth.signUp");
            Auth.Tokens tokens = Auth.signUp(email, username, password);
    
            Document resDoc = new Document();
            resDoc.put("accessToken", tokens.accessToken());
            resDoc.put("refreshToken", tokens.refreshToken());
            byte[] response = resDoc.toJson().getBytes();
            System.out.println("Sending response: " + resDoc.toJson());
            e.sendResponseHeaders(ResponseCodes.OK, response.length);
            e.getResponseBody().write(response);
        } catch (Auth.DuplicateEmail ex) {
            closeOutRequest(e, ResponseCodes.CONFLICT, EMAIL_ALREADY_EXISTS_RESPONSE);
        } catch (Auth.InternalError ex) {
            closeOutRequest(e, ResponseCodes.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_RESPONSE);
        } catch (Auth.DuplicateUsername ex) {
            closeOutRequest(e, ResponseCodes.CONFLICT, USERNAME_ALREADY_EXISTS_RESPONSE);
        } catch (Auth.InvalidUsername ex) {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, INVALID_USERNAME_RESPONSE);
        } catch (Auth.InvalidEmail ex) {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, INVALID_EMAIL_RESPONSE);
        } catch (Auth.InvalidPassword ex) {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, INVALID_PASSWORD_RESPONSE);
        } catch (IOException ex) {
            printException(e, ex, "Failed while responding to client with tokens for new account.");
        }
        try {
            e.getResponseBody().close();
        } catch (IOException ex) {
            printException(e, ex, "Failed while closing response body.");
        }
    }

    public static void signIn(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
        }

        String email;
        String password;
        try {
            Document body = getReqDoc(e.getRequestBody());
            email = body.getString("email");
            password = body.getString("password");
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }

        try {
            Auth.Tokens tokens = Auth.signIn(email, password);

            Document resDoc = new Document();
            resDoc.put("accessToken", tokens.accessToken());
            resDoc.put("refreshToken", tokens.refreshToken());
            byte[] response = resDoc.toJson().getBytes();
            e.sendResponseHeaders(ResponseCodes.OK, response.length);
            e.getResponseBody().write(response);
        } catch (Auth.UserNotFound | Auth.IncorrectPassword ex) {
            try {
                closeOutRequest(e, ResponseCodes.UNAUTHORIZED, UNAUTHORIZED_SIGN_IN_RESPONSE);
            } catch (IOException exc) {
                printException(e, ex, "Failed while sending error response about client being unauthorized.");
            }
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending response containing new tokens.");
        }
    }

    public static void refreshAccess(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String refreshToken;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            refreshToken = reqDoc.getString("refreshToken");
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }

        String accessToken;
        try {
            if (refreshToken == null) throw new Auth.TokenIsNotRefresh();
            accessToken = Auth.refreshAccess(refreshToken);
        } catch (Auth.TokenIsNotRefresh | JWTVerificationException ex) {
            try {
                closeOutRequest(e, ResponseCodes.UNAUTHORIZED, UNAUTHORIZED_REFRESH);
                return;
            } catch (IOException exc) {
                printException(e, exc, "Failed while sending error response about the client being unauthorized.");
                return;
            }
        }

        Document resDoc = new Document()
                .append("accessToken", accessToken);
        byte[] response = resDoc.toJson().getBytes();
        try {
            closeOutRequest(e, ResponseCodes.OK, response);
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending response containing new access token.");
        }
    }

    // TODO: Allow users to sort by rating.
    public static void getReviews(HttpExchange e) {
        try {
            if (!ensureMethod(e, "GET")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String bathroomid;
        int page;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            bathroomid = reqDoc.getString("bathroomid");
            page = reqDoc.getInteger("page"); // TODO: What if null is returned?
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }
        if (page < BASE_PAGE_NUMBER) page = BASE_PAGE_NUMBER;
        if (bathroomid == null) {
            try {
                closeOutRequest(e, ResponseCodes.BAD_REQUEST, BATHROOM_ID_NOT_PRESENT_RESPONSE);
            } catch (IOException ex) {
                printException(e, ex, "Failed while sending error response about a bathroom not existing.");
                return;
            }
            return;
        }

        try (final MongoClient c = DB.client()) {
            MongoDatabase db = DB.db(c);

            StringBuilder sb = new StringBuilder("[");
            FindIterable<Document> docs =
                    db.getCollection("reviews").find(new Document("bathroomid", bathroomid));

            try (MongoCursor<Document> cursor =
                         docs.skip((page - BASE_PAGE_NUMBER) * REVIEWS_PER_PAGE).limit(REVIEWS_PER_PAGE).iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();
                    sb.append(new Document()
                            .append("username", db.getCollection("users").find(new Document("userid",
                                    d.getString("userid"))).first().getString("username"))
                            .append("rating", d.getInteger("rating"))
                            .append("review", d.getString("review")).toJson()).append(", ");
                }
            }
            if (!sb.isEmpty()) sb.delete(sb.length() - 2, sb.length()); // Deletes final comma and space after it.
            sb.append("]");
            byte[] response = sb.toString().getBytes(StandardCharsets.UTF_8);

            try {
                closeOutRequest(e, ResponseCodes.OK, response);
            } catch (IOException ex) {
                printException(e, ex, "Failed while sending response containing reviews.");
                return;
            }
        }
    }

    public static void createReview(HttpExchange e) {
        try {
            if (!ensureMethod(e, "PUT")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        DecodedJWT accessToken;
        String bathroomid;
        int rating;
        String review;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            accessToken = Auth.verifyAccess(reqDoc.getString("accessToken"));
            bathroomid = reqDoc.getString("bathroomid");
            rating = reqDoc.getInteger("rating");
            review = reqDoc.getString("review");

        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        } catch (Auth.TokenIsNotAccess | JWTVerificationException ex) {
            try {
                System.err.println(ex);
                closeOutRequest(e, ResponseCodes.UNAUTHORIZED, UNAUTHORIZED_CREATE_REVIEW);
            } catch (IOException exc) {
                printException(e, exc, "Failed while sending error response about the client being unauthorized.");
                return;
            }
            return;
        }

        String userid = accessToken.getClaim("userid").asString();

        try (MongoClient c = DB.client()) {
            MongoDatabase db = DB.db(c);
            MongoCollection<Document> collection = db.getCollection("reviews");
            Document oldReview = collection.find(new Document()
                    .append("userid", userid)
                    .append("bathroomid", bathroomid)).first();
            if (oldReview != null)
                collection.deleteOne(oldReview);
            collection.insertOne(new Document()
                    .append("userid", userid)
                    .append("bathroomid", bathroomid)
                    .append("rating", rating)
                    .append("review", review));
        }

        try {
            closeOutRequest(e, ResponseCodes.OK);
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending response confirming creation of new review.");
        }
    }

    public static void getBathrooms(HttpExchange e) {
        // remember to update body->raw JSON on postman
        try {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, "No!".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void getBuildings(HttpExchange e) {
        // remember to update body->raw JSON on postman
        try {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, "No!".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void printException(HttpExchange e, Exception ex) {
        printException(e, ex, null);
    }

    public static void printException(HttpExchange e, Exception ex, @Nullable String description) {
        StringWriter sw = new StringWriter();
        sw.append("An exception was raised while handling a request.");
        if (description != null)
            sw.append('\n').append(description);
        sw
                .append("\nCurrent Time: ").append(LocalDateTime.now().toString())
                .append("\nRequest URL: ").append(e.getRequestURI().toString())
                .append("\nRequest Method: ").append(e.getRequestMethod())
                .append("\n");
        ex.printStackTrace(new PrintWriter(sw));
        System.err.println(sw);
    }

    private static Document getReqDoc(InputStream reqBody) throws IOException {
        try (reqBody) { // close on success or more importantly on failure as suggested by InputStream.readAllBytes().
            return Document.parse(new String(reqBody.readAllBytes()));
        }
    }

    private static void closeOutRequest(HttpExchange e, int rCode) throws IOException {
        closeOutRequest(e, rCode, null);
    }

    private static void closeOutRequest(HttpExchange e, int rCode, byte @Nullable [] response) throws IOException {
        if (response == null) {
            e.sendResponseHeaders(rCode, 0);
            e.close();
        } else {
            e.sendResponseHeaders(rCode, response.length);
            e.getResponseBody().write(response);
            e.close();
        }
    }

    /**
     * Ensures that the method is the provided method, and if not, returns 405 as response and closes the exchange.
     * @param e The request to check the method of.
     * @param method The method to expect.
     * @return True if the methods match, false if they do not, a response was sent and the exchange was closed.
     */
    private static boolean ensureMethod(HttpExchange e, String method) throws IOException {
        if (!e.getRequestMethod().equalsIgnoreCase(method)) {
            closeOutRequest(e, 405, INVALID_METHOD_RESPONSE);
            return false;
        }
        else
            return true;
    }
}
