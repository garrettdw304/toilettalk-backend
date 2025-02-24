import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.mindrot.jbcrypt.BCrypt;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Date;
import java.util.UUID;

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

            return genTokens(userDoc.getString("userid"));
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

        return genTokens(userdata.getString("userid"));
    }

    public static String refreshAccess(@NotNull String refreshToken) throws JWTVerificationException, TokenIsNotRefresh {
        DecodedJWT jwt = verifyRefresh(refreshToken);
        String userid = jwt.getClaim("userid").asString();
        return genTokens(userid).accessToken;
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

    private static Tokens genTokens(String userid) {
        return new Tokens(genAccessToken(userid), genRefreshToken(userid));
    }

    private static String genAccessToken(String userid) {
        return JWT.create()
                .withClaim("type", "access")
                .withClaim("userid", userid)
                .withExpiresAt(Date.from(LocalDateTime.now().plusHours(1).toInstant(ZoneOffset.UTC)))
                .sign(Algorithm.RSA256(Env.PUBLIC_KEY, Env.PRIVATE_KEY));
    }

    private static String genRefreshToken(String userid) {
        return JWT.create()
                .withClaim("type", "refresh")
                .withClaim("userid", userid)
                .withExpiresAt(Date.from(LocalDateTime.now().plusDays(30).toInstant(ZoneOffset.UTC)))
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

    public record Tokens(String accessToken, String refreshToken) { }

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
