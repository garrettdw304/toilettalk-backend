import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class Testing {
    public static void genTestingCollections() {
        try (MongoClient client = DB.client()) {
            MongoDatabase db = DB.db(client);
            Random rand = new Random();

            // Gen users
            Document[] userDocs = new Document[10];
            {
                db.createCollection("users");
                MongoCollection<Document> users = db.getCollection("users");
                for (int i = 0; i < 10; i++) {
                    userDocs[i] = Auth.createNewUserDoc(
                            "e" + rand.nextLong() + "@gmail.com",
                            "u" + rand.nextLong(),
                            "p" + rand.nextLong(), db);
                }
                users.insertMany(Arrays.asList(userDocs));
            }

            // Gen buildings
            Document[] buildingDocs = new Document[10];
            {
                db.createCollection("buildings");
                MongoCollection<Document> buildings = db.getCollection("buildings");
                for (int i = 0; i < buildingDocs.length; i++) {
                    buildingDocs[i] = new Document()
                            .append("buildingid", UUID.randomUUID().toString())
                            .append("name", "Building #" + Long.toString(rand.nextLong()).substring(0, 5));
                }
                buildings.insertMany(Arrays.asList(buildingDocs));
            }

            // Gen bathrooms
            Document[][] bathroomDocs = new Document[10][];
            {
                db.createCollection("bathrooms");
                MongoCollection<Document> bathrooms = db.getCollection("bathrooms");
                for (int i = 0; i < 10; i++) {
                    bathroomDocs[i] = new Document[rand.nextInt(0, 4)];
                    for (int j = 0; j < bathroomDocs[i].length; j++) {
                        bathroomDocs[i][j] = new Document()
                                .append("bathroomid", UUID.randomUUID().toString())
                                .append("buildingid", buildingDocs[i].get("buildingid"))
                                .append("name", (rand.nextBoolean() ? "Men's" : "Women's") + " Bathroom #" + Long.toString(rand.nextLong()).substring(0, 5));
                    }

                    if (bathroomDocs[i].length > 0)
                        bathrooms.insertMany(Arrays.asList(bathroomDocs[i]));
                }
            }

            // Gen reviews
            db.createCollection("reviews");
            MongoCollection<Document> reviews = db.getCollection("reviews");
            for (Document[] bathrooms : bathroomDocs) {
                for (Document bathroom : bathrooms) {
                    for (Document userDoc : userDocs) {
                        reviews.insertOne(new Document()
                                .append("reviewid", UUID.randomUUID().toString())
                                .append("userid", userDoc.get("userid"))
                                .append("bathroomid", bathroom.get("bathroomid"))
                                .append("rating", rand.nextInt(0, 6))
                                .append("review", "r" + rand.nextLong()));
                    }
                }
            }
        } catch (Auth.InternalError e) {
            throw new RuntimeException(e);
        }
    }

    public static void deleteAllData() {
        try (MongoClient client = DB.client()) {
            MongoDatabase db = DB.db(client);
            db.getCollection("users").drop();
            db.getCollection("buildings").drop();
            db.getCollection("bathrooms").drop();
            db.getCollection("reviews").drop();
        }
    }

    public static void genRSAKeys() {
        try {
            KeyPairGenerator kgen = KeyPairGenerator.getInstance("RSA");
            kgen.initialize(2048);
            KeyPair keys = kgen.generateKeyPair();
            Files.write(Paths.get("public.key"), Base64.getEncoder().encode(keys.getPublic().getEncoded()));
            Files.write(Paths.get("private.key"), Base64.getEncoder().encode(keys.getPrivate().getEncoded()));
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
