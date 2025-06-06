import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.mindrot.jbcrypt.BCrypt;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Auth {
    public static Tokens signUp(String email, String username, String password)
            throws DuplicateEmail, DuplicateUsername, InternalError, InvalidEmail, InvalidUsername, InvalidPassword {
        if (!validateEmail(email)) throw new InvalidEmail();
        if (!validateUsername(username)) throw new InvalidUsername();
        if (!validatePassword(password)) throw new InvalidPassword();

        try (MongoClient client = DB.client()) {
            MongoDatabase db = DB.db(client);
            if (db.getCollection("users").find(new Document("email", email)).first() != null)
                throw new DuplicateEmail();
            if (db.getCollection("users").find(new Document("username", username)).first() != null)
                throw new DuplicateUsername();

            Document userDoc = createNewUserDoc(email, username, password, db);
            db.getCollection("users").insertOne(userDoc);

            return genTokens(userDoc.getString("userid"), username);
        }
    }

    public static Tokens signIn(String email, String password) throws UserNotFound, IncorrectPassword {
        Document userdata;
        try (MongoClient client = DB.client()) {
            MongoDatabase db = DB.db(client);
            userdata = db.getCollection("users").find(new Document("email", email)).first();
        }

        if (userdata == null)
            throw new UserNotFound();
        if (!userdata.getString("password").equals(BCrypt.hashpw(password, userdata.getString("salt"))))
            throw new IncorrectPassword();

        return genTokens(userdata.getString("userid"), userdata.getString("username"));
    }

    public static String refreshAccess(@NotNull String refreshToken) throws JWTVerificationException, TokenIsNotRefresh {
        DecodedJWT jwt = verifyRefresh(refreshToken);
        String userid = jwt.getClaim("userid").asString();
        return genTokens(userid, "").accessToken;
    }

    public static DecodedJWT verify(@NotNull String token) throws JWTVerificationException {
        return JWT.require(Algorithm.RSA256(Env.PUBLIC_KEY, null)).build().verify(token);
    }

    public static DecodedJWT verifyAccess(@NotNull String token) throws JWTVerificationException, TokenIsNotAccess {
        DecodedJWT jwt = verify(token);
        if (!jwt.getClaim("type").asString().equals("access"))
            throw new TokenIsNotAccess();
        return jwt;
    }

    public static DecodedJWT verifyRefresh(@NotNull String token) throws JWTVerificationException, TokenIsNotRefresh {
        DecodedJWT jwt = verify(token);
        if (!jwt.getClaim("type").asString().equals("refresh"))
            throw new TokenIsNotRefresh();
        return jwt;
    }

    /**
     * Does not ensure a user with the email address or username does not exist.
     * Does not add this document to the database.
     * db is used to get a unique userid (assuming all userids are already in the users table).
     */
    public static Document createNewUserDoc(String email, String username, String password, MongoDatabase db)
            throws InternalError {
        String salt = BCrypt.gensalt();
        return new Document()
                .append("_id", new ObjectId())
                .append("userid", DB.getNewUserId(db))
                .append("email", email)
                .append("username", username)
                .append("password", BCrypt.hashpw(password, salt))
                .append("salt", salt);
    }

    public static String accessFromCookies(List<String> cookiesList) {
        if (cookiesList == null)
            return null;
        for (String cookies : cookiesList) {
            for (String cookie : splitCookies(cookies)) {
                int equalIndex = cookie.indexOf('=');
                if (equalIndex == -1)
                    continue;
                if (cookie.startsWith("AccessToken="))
                    return cookie.substring(equalIndex + 1);
            }
        }

        return null;
    }

    public static String refreshFromCookies(List<String> cookiesList) {
        if (cookiesList == null)
            return null;
        for (String cookies : cookiesList) {
            for (String cookie : splitCookies(cookies)) {
                int equalIndex = cookie.indexOf('=');
                if (equalIndex == -1)
                    continue;
                if (cookie.startsWith("RefreshToken="))
                    return cookie.substring(equalIndex + 1);
            }
        }

        return null;
    }

    private static List<String> splitCookies(String cookies) {
        ArrayList<String> list = new ArrayList<>();
        for (String cookie : cookies.split(";"))
            list.add(cookie.trim());
        return list;
    }

    private static Tokens genTokens(String userid, String username) {
        return new Tokens(genAccessToken(userid), genRefreshToken(userid), userid, username);
    }

    private static String genAccessToken(String userid) {
        return JWT.create()
                .withClaim("type", "access")
                .withClaim("userid", userid)
                // TODO: This could be different when hosted on Google's VMs.
                // For some reason this has to be in EST (aka UTC-5) maybe because that is the backend's timezone?
                .withExpiresAt(Date.from(LocalDateTime.now().plusHours(1).toInstant(ZoneOffset.ofHours(-5))))
                .sign(Algorithm.RSA256(Env.PUBLIC_KEY, Env.PRIVATE_KEY));
    }

    private static String genRefreshToken(String userid) {
        return JWT.create()
                .withClaim("type", "refresh")
                .withClaim("userid", userid)
                // TODO: This could be different when hosted on Google's VMs.
                // For some reason this has to be in EST (aka UTC-5) maybe because that is the backend's timezone?
                .withExpiresAt(Date.from(LocalDateTime.now().plusDays(30).toInstant(ZoneOffset.ofHours(-5))))
                .sign(Algorithm.RSA256(Env.PUBLIC_KEY, Env.PRIVATE_KEY));
    }

    private static boolean validateEmail(String email) {
        // TODO: This is terrible.
        return email != null && !email.isBlank() && email.indexOf('@') != -1;
    }

    private static boolean validateUsername(String username) {
        // TODO: This is terrible.
        return username != null && !username.isBlank();
    }

    private static boolean validatePassword(String password) {
        // TODO: This is terrible.
        return password != null && !password.isBlank() && password.length() >= 8;
    }

    public record Tokens(String accessToken, String refreshToken, String userid, String username) { }

    public static class AuthException extends Exception { }
    public static class InternalError extends AuthException { }
    public static class UserNotFound extends AuthException { }
    public static class DuplicateEmail extends AuthException { }
    public static class DuplicateUsername extends AuthException { }
    public static class IncorrectPassword extends AuthException { }
    public static class InvalidEmail extends AuthException { }
    public static class InvalidUsername extends AuthException { }
    public static class InvalidPassword extends AuthException { }

    public static class TokenIsNotAccess extends AuthException { }
    public static class TokenIsNotRefresh extends AuthException { }
}
