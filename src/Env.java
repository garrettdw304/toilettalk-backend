import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class Env {
    private static final Dotenv dotenv = Dotenv.load();

    public static final String DB_URL;
    public static final RSAPublicKey PUBLIC_KEY;
    public static final RSAPrivateKey PRIVATE_KEY;
    public static final boolean INIT_SUCCESSFUL;

    static {
        String dbURL = null;
        RSAPrivateKey privateKey = null;
        RSAPublicKey publicKey = null;
        boolean initSuccessful = false;
        try {
            dbURL = dotenv.get("DB_URL");
            privateKey = (RSAPrivateKey) KeyFactory
                    .getInstance("RSA")
                    .generatePrivate(
                            new PKCS8EncodedKeySpec(
                                    Base64.getDecoder().decode(
                                            Files.readString(Paths.get(dotenv.get("PRIVATE_KEY_FILE"))))));
            publicKey = (RSAPublicKey)KeyFactory
                    .getInstance("RSA")
                    .generatePublic(
                            new X509EncodedKeySpec(
                                    Base64.getDecoder().decode(
                                            Files.readAllBytes(Paths.get(dotenv.get("PUBLIC_KEY_FILE"))))));
            initSuccessful = true;
        } catch (NullPointerException | IOException | InvalidKeySpecException | NoSuchAlgorithmException e) {
            System.err.println(e);
        }
        DB_URL = dbURL;
        PUBLIC_KEY = publicKey;
        PRIVATE_KEY = privateKey;
        INIT_SUCCESSFUL = initSuccessful;
    }

    public static String get(String key) {
        return dotenv.get(key);
    }
}
