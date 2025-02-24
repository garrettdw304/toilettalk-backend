import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.UUID;

public class DB {
    public static MongoClient client() {
        return MongoClients.create(Env.DB_URL);
    }

    public static MongoDatabase db(MongoClient client) {
        return client.getDatabase("tt-database");
    }

    public static String getNewUserId(MongoDatabase db) throws Auth.InternalError {
        String userid = null;
        boolean foundId = false;
        for (int i = 0; i < 100; i++) {
            userid = UUID.randomUUID().toString();
            if (db.getCollection("users").find(new Document("userid", userid)).first() == null) {
                foundId = true;
                break;
            }
        }
        if (!foundId) {
            System.out.println("ERROR: Could not find free unique userid after 100 attempts.");
            throw new Auth.InternalError();
        }

        return userid;
    }
}
