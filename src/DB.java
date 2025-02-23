import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

public class DB {
    public static MongoClient client() {
        return MongoClients.create(Env.DB_URL);
    }

    public static MongoDatabase db(MongoClient client) {
        return client.getDatabase("tt-database");
    }
}
