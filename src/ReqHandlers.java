import com.auth0.jwt.exceptions.JWTVerificationException;
import com.mongodb.client.*;
import com.sun.net.httpserver.HttpExchange;
import org.bson.Document;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class ReqHandlers {
    private static final int REVIEWS_PER_PAGE = 10;

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

    public static void signUp(HttpExchange e) {
        try (e) {
            try {
                if (!ensureMethod(e, "POST")) return;

                Document req = getReqDoc(e.getRequestBody());
                String email = req.getString("email");
                String username = req.getString("username");
                String password = req.getString("password");

                try {
                    Auth.Tokens tokens = Auth.signUp(email, username, password);

                    Document resDoc = new Document();
                    resDoc.put("accessToken", tokens.accessToken());
                    resDoc.put("refreshToken", tokens.refreshToken());
                    byte[] response = resDoc.toJson().getBytes();
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
                }
            } catch (IOException ex) {
                System.out.println(ex); // TODO
                return;
            }
        }
    }

    public static void signIn(HttpExchange e) {
        try (e) {
            try {
                if (!ensureMethod(e, "POST")) return;

                Document body = getReqDoc(e.getRequestBody());
                String email = body.getString("email");
                String password = body.getString("password");

                try {
                    Auth.Tokens tokens = Auth.signIn(email, password);

                    Document resDoc = new Document();
                    resDoc.put("accessToken", tokens.accessToken());
                    resDoc.put("refreshToken", tokens.refreshToken());
                    byte[] response = resDoc.toJson().getBytes();
                    e.sendResponseHeaders(ResponseCodes.OK, response.length);
                    e.getResponseBody().write(response);
                } catch (Auth.UserNotFound | Auth.IncorrectPassword ex) {
                    closeOutRequest(e, ResponseCodes.UNAUTHORIZED, UNAUTHORIZED_SIGN_IN_RESPONSE);
                }
            } catch (IOException ex) {
                System.out.println(ex); // TODO
                return;
            }
        }
    }

    public static void refreshAccess(HttpExchange e) {
        try (e) {
            try {
                if (!ensureMethod(e, "POST")) return;
            } catch (IOException ex) {
                System.out.println(ex); // TODO
                return;
            }

            String refreshToken;
            try {
                Document reqDoc = getReqDoc(e.getRequestBody());
                refreshToken = reqDoc.getString("refreshToken");
            } catch (IOException ex) {
                System.out.println(ex); // TODO
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
                    System.out.println(ex); // TODO
                    return;
                }
            }

            Document resDoc = new Document()
                    .append("accessToken", accessToken);
            byte[] response = resDoc.toJson().getBytes();
            closeOutRequest(e, ResponseCodes.OK, response);
        } catch (IOException ex) {
            System.out.println(ex); // TODO
            return;
        }
    }

    public static void getReviews(HttpExchange e) {
        try (e) {
            try {
                if (!ensureMethod(e, "GET")) return;
            } catch (IOException ex) {
                System.out.println(ex); // TODO
                return;
            }

            String bathroomid;
            int page;
            try {
                Document reqDoc = getReqDoc(e.getRequestBody());
                bathroomid = reqDoc.getString("bathroomid");
                page = reqDoc.getInteger("page");
            } catch (IOException ex) {
                System.out.println(ex); // TODO
                return;
            }
            if (page < 1) page = 1; // TODO: Magic page base number
            if (bathroomid == null) {
                try {
                    closeOutRequest(e, ResponseCodes.BAD_REQUEST, BATHROOM_ID_NOT_PRESENT_RESPONSE);
                } catch (IOException ex) {
                    System.out.println(ex); // TODO
                    return;
                }
                return;
            }

            try (final MongoClient c = DB.client()) {
                MongoDatabase db = DB.db(c);

                StringBuilder sb = new StringBuilder("[");
                FindIterable<Document> docs =
                        db.getCollection("reviews").find(new Document("bathroomid", bathroomid));

                // TODO: Magic page base number
                try (MongoCursor<Document> cursor =
                             docs.skip((page - 1) * REVIEWS_PER_PAGE).limit(REVIEWS_PER_PAGE).iterator()) {
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
                    System.out.println(ex); // TODO
                    return;
                }
            }
        }
    }

    public static void createReview(HttpExchange e) {
        try {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, "No!".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void getBathrooms(HttpExchange e) {
        try {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, "No!".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static void getBuildings(HttpExchange e) {
        try {
            closeOutRequest(e, ResponseCodes.BAD_REQUEST, "No!".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Document getReqDoc(InputStream reqBody) throws IOException {
        return Document.parse(new String(reqBody.readAllBytes()));
    }

    private static void closeOutRequest(HttpExchange e, int rCode, byte[] response) throws IOException {
        e.sendResponseHeaders(rCode, response.length);
        e.getResponseBody().write(response);
        e.close();
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
