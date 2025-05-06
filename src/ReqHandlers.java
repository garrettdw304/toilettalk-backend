import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.mongodb.client.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bson.Document;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/*
 * This file defines Request Handlers that will be called by the HttpServer when a request with a specific context
 * is received. It is suggested to wrap the entire contents of a handler in a try-catch that catches all Exceptions and
 * at least print them to System.err. This is because the executor that the HttpServer uses simply eats up any unhandled
 * exceptions without any indication of an exception even occurring which can be hard to debug.
 * See ReqHandlers.printUncaughtExceptions to do this without wrapping an entire function's contents in a try-catch.
 */

public class ReqHandlers {
    private static final int ITEMS_PER_PAGE = 100;
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
    private static final byte[] BUILDING_ID_NOT_PRESENT_RESPONSE =
            "{ \"error\": \"Building id not present in request.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAUTHORIZED_REFRESH =
            "{ \"error\": \"Token sent could not authorize an access refresh.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAUTHORIZED_CREATE_REVIEW =
            "{ \"error\": \"Token sent could not authorize a create review.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BATHROOM_DOES_NOT_EXIST_RESPONSE =
            "{ \"error\": \"The bathroom with the specified id does not exist.\" }".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_SIGNED_IN_RESPONSE =
            "{ \"error\": \"Not signed in.\" }".getBytes(StandardCharsets.UTF_8);

    /**
     * Helper method that calls an HttpHandler with the provided HttpExchange
     * and catches and prints all uncaught exceptions.
     * Also closes the exchange after the handler returns or an exception is caught to keep from freezing up the client.
     * @param handler The handler to call.
     * @param e The exchange to pass to the handler when calling it.
     */
    public static void handleUncaughtExceptions(HttpHandler handler, HttpExchange e) {
        System.out.println("Request");
        System.out.println(e.getRequestURI());

        e.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        e.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        e.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        e.getResponseHeaders().put("Content-Type", List.of("application/json"));

        if ("OPTIONS".equalsIgnoreCase(e.getRequestMethod())) {
            System.out.println("OPTIONS");
            try{
                e.sendResponseHeaders(200, 0);
                e.close();
            } catch (IOException ex) {
                printException(e, ex, "OPTIONS return caused error.");
            }
            return;
        }

        System.out.println("Request received!");
        try (e) {
            handler.handle(e);
        } catch (Exception ex) {
            printException(e, ex, "This exception was uncaught (and then caught by handleUncaughtExceptions).");
        }
    }

    public static void signUp(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String email;
        String username;
        String password;
        try {
            Document req = getReqDoc(e.getRequestBody());
            email = req.getString("email");
            username = req.getString("username");
            password = req.getString("password");
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }

        try {
            try {
                Auth.Tokens tokens = Auth.signUp(email, username, password);

                Document resDoc = new Document().append("accessToken", tokens.accessToken())
                        .append("refreshToken", tokens.refreshToken())
                        .append("username", username)
                        .append("userid", tokens.userid());
                closeOutRequest(e, ResponseCodes.OK, resDoc);
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
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending an error response.");
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

            Document resDoc = new Document().append("accessToken", tokens.accessToken())
                    .append("refreshToken", tokens.refreshToken())
                    .append("username", tokens.username())
                    .append("userid", tokens.userid());
            closeOutRequest(e, ResponseCodes.OK, resDoc);
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

    public static void getMyInfo(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String accessCookie;
        try {
            Document body = getReqDoc(e.getRequestBody());
            accessCookie = body.getString("accessToken");
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }

        DecodedJWT accessToken;
        try {
            accessToken = Auth.verifyAccess(accessCookie);

        } catch (Auth.TokenIsNotAccess | JWTVerificationException ex) {
            try {
                System.err.println(ex);
                closeOutRequest(e, ResponseCodes.UNAUTHORIZED, NOT_SIGNED_IN_RESPONSE);
            } catch (IOException exc) {
                printException(e, exc, "Failed while sending error response about the client being unauthorized.");
                return;
            }
            return;
        }

        String userid = accessToken.getClaim("userid").asString();
        Document resDoc = new Document();

        try (final MongoClient c = DB.client()) {
            MongoDatabase db = DB.db(c);

            List<Document> recentReviews = new ArrayList<>(5);
            try (MongoCursor<Document> cursor = db.getCollection("reviews").find(new Document("userid", userid)).limit(5).sort(new Document("_id", -1)).cursor()) {
                while (cursor.hasNext()) {
                    Document review = cursor.next();

                    String username = db.getCollection("users").find(new Document("userid", review.getString("userid"))).first().getString("username");
                    review.append("username", username);

                    recentReviews.add(review);
                }
            }

            resDoc.append("reviews", recentReviews);
        }

        try {
            closeOutRequest(e, ResponseCodes.OK, resDoc.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending a user's info.");
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
            Document body = getReqDoc(e.getRequestBody());
            refreshToken = body.getString("refreshToken");
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

        try {
            Document resDoc = new Document().append("accessToken", accessToken);
            closeOutRequest(e, ResponseCodes.OK, resDoc);
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending response containing new access token.");
        }
    }

    // TODO: Allow users to sort by rating.
    public static void getReviews(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
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
                         docs.skip((page - BASE_PAGE_NUMBER) * ITEMS_PER_PAGE).limit(ITEMS_PER_PAGE).iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();
                    sb.append(new Document()
                            .append("username", db.getCollection("users").find(new Document("userid",
                                    d.getString("userid"))).first().getString("username"))
                            .append("rating", d.getInteger("rating"))
                            .append("review", d.getString("review")).toJson()).append(", ");
                }
            }
            if (sb.length() > 1) sb.delete(sb.length() - 2, sb.length()); // Deletes final comma and space after it.
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
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String accessCookie;
        String bathroomid;
        int rating;
        String review;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            accessCookie = reqDoc.getString("accessToken");
            bathroomid = reqDoc.getString("bathroomid");
            rating = reqDoc.getInteger("rating");
            review = reqDoc.getString("review");
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }

        DecodedJWT accessToken;
        try {
            accessToken = Auth.verifyAccess(accessCookie);
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
            if (db.getCollection("bathrooms").find(new Document("bathroomid", bathroomid)).first() == null) {
                try {
                    closeOutRequest(e, ResponseCodes.BAD_REQUEST, BATHROOM_DOES_NOT_EXIST_RESPONSE);
                } catch (IOException exc) {
                    printException(e, exc, "Failed while sending error response about the client being unauthorized.");
                    return;
                }
                return;
            }

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
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String buildingId;
        int page;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            buildingId = reqDoc.getString("buildingid");
            page = reqDoc.getInteger("page"); // TODO: What if null is returned?
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }
        if (page < BASE_PAGE_NUMBER) page = BASE_PAGE_NUMBER;
        if (buildingId == null) {
            try {
                closeOutRequest(e, ResponseCodes.BAD_REQUEST, BUILDING_ID_NOT_PRESENT_RESPONSE);
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
                    db.getCollection("bathrooms").find(new Document("buildingid", buildingId));

            try (MongoCursor<Document> cursor =
                         docs.skip((page - BASE_PAGE_NUMBER) * ITEMS_PER_PAGE).limit(ITEMS_PER_PAGE).iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();
                    sb.append(new Document()
                            .append("bathroomid", d.getString("bathroomid"))
                            // Do we need to send bathroom id if they are all the one that the request asked for?
                            .append("buildingid", d.getString("buildingid"))
                            .append("name", d.getString("name")).toJson()).append(", ");
                }
            }
            if (sb.length() > 1) sb.delete(sb.length() - 2, sb.length()); // Deletes final comma and space after it.
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

    public static void getBuildings(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        int page;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            page = reqDoc.getInteger("page"); // TODO: What if null is returned?
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }
        if (page < BASE_PAGE_NUMBER) page = BASE_PAGE_NUMBER;

        try (final MongoClient c = DB.client()) {
            MongoDatabase db = DB.db(c);

            StringBuilder sb = new StringBuilder("[");
            FindIterable<Document> docs =
                    db.getCollection("buildings").find();

            try (MongoCursor<Document> cursor =
                         docs.skip((page - BASE_PAGE_NUMBER) * ITEMS_PER_PAGE).limit(ITEMS_PER_PAGE).iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();
                    sb.append(new Document()
                            .append("buildingid", d.getString("buildingid"))
                            .append("name", d.getString("name")).toJson()).append(", ");
                }
            }
            if (sb.length() > 1) sb.delete(sb.length() - 2, sb.length()); // Deletes final comma and space after it.
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

    public static void getBuildingsWithBathrooms(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        int page;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            page = reqDoc.getInteger("page"); // TODO: What if null is returned?
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }
        if (page < BASE_PAGE_NUMBER) page = BASE_PAGE_NUMBER;

        try (final MongoClient c = DB.client()) {
            MongoDatabase db = DB.db(c);

            FindIterable<Document> docs =
                    db.getCollection("buildings").find();
            List<Document> toReturn = new ArrayList<>(ITEMS_PER_PAGE);
            try (MongoCursor<Document> cursor =
                         docs.skip((page - BASE_PAGE_NUMBER) * ITEMS_PER_PAGE).limit(ITEMS_PER_PAGE).iterator()) {
                while (cursor.hasNext())
                    toReturn.add(cursor.next());
            }

            for (Document d : toReturn) {
                docs = db.getCollection("bathrooms").find(new Document("buildingid", d.getString("buildingid")));
                try (MongoCursor<Document> cursor =
                             docs.skip((page - BASE_PAGE_NUMBER) * ITEMS_PER_PAGE).limit(ITEMS_PER_PAGE).iterator()) {
                    List<Document> bathrooms = new ArrayList<>();
                    while (cursor.hasNext())
                        bathrooms.add(cursor.next());
                    d.append("bathrooms", bathrooms);
                }
            }

            StringBuilder sb = new StringBuilder("[");
            for (Document d : toReturn)
                sb.append(d.toJson()).append(", ");
            if (sb.length() > 1) sb.delete(sb.length() - 2, sb.length()); // Deletes final comma and space after it.
            sb.append("]");

            System.out.println(sb);

            byte[] response = sb.toString().getBytes(StandardCharsets.UTF_8);

            try {
                closeOutRequest(e, ResponseCodes.OK, response);
            } catch (IOException ex) {
                printException(e, ex, "Failed while sending response containing reviews.");
                return;
            }
        }
    }

    public static void getBathroomWithReviews(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
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

            Document toReturn = db.getCollection("bathrooms").find(new Document("bathroomid", bathroomid)).first();
            FindIterable<Document> docs =
                    db.getCollection("reviews").find(new Document("bathroomid", bathroomid));

            try (MongoCursor<Document> cursor =
                         docs.skip((page - BASE_PAGE_NUMBER) * ITEMS_PER_PAGE).limit(ITEMS_PER_PAGE).iterator()) {
                List<Document> reviews = new ArrayList<>(ITEMS_PER_PAGE);
                while (cursor.hasNext()) {
                    Document d = cursor.next();
                    d.append("username", db.getCollection("users").find(new Document("userid", d.getString("userid"))).first().getString("username"));
                    reviews.add(d);
                }

                toReturn.append("reviews", reviews);
                toReturn.append("buildingName", db.getCollection("buildings").find(new Document("buildingid", toReturn.getString("buildingid"))).first().getString("name"));
            }

            try {
                System.out.println(toReturn.toJson());
                closeOutRequest(e, ResponseCodes.OK, toReturn.toJson());
            } catch (IOException ex) {
                printException(e, ex, "Failed while sending response containing reviews.");
                return;
            }
        }
    }

    public static void getChats(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        int page;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            page = reqDoc.getInteger("page"); // TODO: What if null is returned?
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }
        if (page < BASE_PAGE_NUMBER) page = BASE_PAGE_NUMBER;

        try (final MongoClient c = DB.client()) {
            MongoDatabase db = DB.db(c);

            FindIterable<Document> docs =
                    db.getCollection("chats").find();

            JSONArray toReturn = new JSONArray();
            try (MongoCursor<Document> cursor =
                         docs.skip((page - BASE_PAGE_NUMBER) * ITEMS_PER_PAGE).limit(ITEMS_PER_PAGE).iterator()) {
                while (cursor.hasNext()) {
                    Document d = cursor.next();
                    if (d.getBoolean("anon")) {
                        d.remove("userid");
                        d.append("username", "anon");
                    }
                    else
                        d.append("username", db.getCollection("users").find(new Document("userid", d.getString("userid"))).first().getString("username"));

                    toReturn.put(new org.json.JSONObject(d.toJson()));
                }
            }

            try {
                closeOutRequest(e, ResponseCodes.OK, toReturn.toString());
            } catch (IOException ex) {
                printException(e, ex, "Failed while sending response containing reviews.");
                return;
            }
        }
    }

    public static void createChat(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String accessCookie;
        String text;
        boolean isAnon;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            accessCookie = reqDoc.getString("accessToken");
            text = reqDoc.getString("text");
            isAnon = reqDoc.getBoolean("anon");
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }

        DecodedJWT accessToken;
        try {
            accessToken = Auth.verifyAccess(accessCookie);
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

            MongoCollection<Document> chats = db.getCollection("chats");
            chats.insertOne(new Document()
                    .append("userid", userid)
                    .append("text", text)
                    .append("datetime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM uuuu HH:mm:ss")))
                    .append("anon", isAnon)
                    .append("chatid", UUID.randomUUID().toString())); // TODO: Pure luck that they don't collide :)
        }

        try {
            closeOutRequest(e, ResponseCodes.OK);
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending response confirming creation of new review.");
        }
    }

    public static void deleteChat(HttpExchange e) {
        try {
            if (!ensureMethod(e, "POST")) return;
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending error response about wrong request method.");
            return;
        }

        String accessCookie;
        String chatid;
        try {
            Document reqDoc = getReqDoc(e.getRequestBody());
            accessCookie = reqDoc.getString("accessToken");
            chatid = reqDoc.getString("chatid");
        } catch (IOException ex) {
            printException(e, ex, "Failed while getting request body.");
            return;
        }

        DecodedJWT accessToken;
        try {
            accessToken = Auth.verifyAccess(accessCookie);
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

            MongoCollection<Document> chats = db.getCollection("chats");
            if (!chats.find(new Document("chatid", chatid)).first().getString("userid").equals(userid))
            {
                try {
                    closeOutRequest(e, ResponseCodes.UNAUTHORIZED, "User ids do not match.");
                    return;
                } catch (IOException exc) {
                    printException(e, exc, "Failed while sending error response about the client being unauthorized.");
                    return;
                }
            }
            chats.deleteOne(new Document("chatid", chatid));
        }

        try {
            closeOutRequest(e, ResponseCodes.OK);
        } catch (IOException ex) {
            printException(e, ex, "Failed while sending response confirming creation of new review.");
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
            String str = new String(reqBody.readAllBytes());
            System.out.println(str);
            return Document.parse(str);
        }
    }

    private static void closeOutRequest(HttpExchange e, int rCode) throws IOException {
        closeOutRequest(e, rCode, (byte[])null);
    }

    private static void closeOutRequest(HttpExchange e, int rCode, byte @Nullable [] response) throws IOException {
        if (response == null) {
            e.sendResponseHeaders(rCode, 0);
            e.close();
        } else {
            System.out.println("Response\n" + new String(response));
            e.sendResponseHeaders(rCode, response.length);
            e.getResponseBody().write(response);
            e.close();
        }
    }

    private static void closeOutRequest(HttpExchange e, int rCode, String response) throws IOException {
        closeOutRequest(e, rCode, response.getBytes(StandardCharsets.UTF_8));
    }

    private static void closeOutRequest(HttpExchange e, int rCode, Document response) throws IOException {
        closeOutRequest(e, rCode, response.toJson());
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
