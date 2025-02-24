import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.mindrot.jbcrypt.BCrypt;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    public static DecodedJWT verify(String token) throws JWTVerificationException {
        return JWT.require(Algorithm.RSA256(Env.PUBLIC_KEY, null)).build().verify(token);
    }

    /**
     * Does not ensure a user with the email address or username does not exist.
     * Does not add this document to the database.
     * db is used to get a unique userid.
     * @param email
     * @param username
     * @param password
     * @param db
     * @return
     * @throws InternalError
     */
    public static Document createNewUserDoc(String email, String username, String password, MongoDatabase db) throws InternalError {
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
        String accessToken = JWT.create()
                .withClaim("type", "access")
                .withClaim("userid", userid)
                .withClaim("expiresAt", LocalDateTime.now().plusHours(1).toEpochSecond(ZoneOffset.UTC))
                .sign(Algorithm.RSA256(Env.PUBLIC_KEY, Env.PRIVATE_KEY));

        String refreshToken = JWT.create()
                .withClaim("type", "refresh")
                .withClaim("userid", userid)
                .withClaim("expiresAt", LocalDateTime.now().plusDays(30).toEpochSecond(ZoneOffset.UTC))
                .sign(Algorithm.RSA256(Env.PUBLIC_KEY, Env.PRIVATE_KEY));

        return new Tokens(accessToken, refreshToken);
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
}
