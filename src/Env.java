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

    public static final String DB_URL = dotenv.get("DB_URL");
    public static final RSAPublicKey PUBLIC_KEY;
    public static final RSAPrivateKey PRIVATE_KEY;

    static {
        try {
            PRIVATE_KEY = (RSAPrivateKey) KeyFactory
                    .getInstance("RSA")
                    .generatePrivate(
                            new PKCS8EncodedKeySpec(
                                    Base64.getDecoder().decode(
                                            Files.readString(Paths.get(dotenv.get("PRIVATE_KEY_FILE"))))));
            PUBLIC_KEY = (RSAPublicKey)KeyFactory
                    .getInstance("RSA")
                    .generatePublic(
                            new X509EncodedKeySpec(
                                    Base64.getDecoder().decode(
                                            Files.readAllBytes(Paths.get(dotenv.get("PUBLIC_KEY_FILE"))))));
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String get(String key) {
        return dotenv.get(key);
    }
}
