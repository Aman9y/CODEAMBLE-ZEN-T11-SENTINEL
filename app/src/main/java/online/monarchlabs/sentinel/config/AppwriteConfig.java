package online.monarchlabs.sentinel.config;

import android.content.Context;
import online.monarchlabs.sentinel.BuildConfig;
import io.appwrite.Client;
import io.appwrite.services.Account;
import io.appwrite.services.Databases;
import io.appwrite.services.Functions;

/**
 * Appwrite Configuration Class
 * Sets up the Appwrite client and services for the application
 */
public class AppwriteConfig {
    
    // Appwrite Configuration Constants
    private static final String ENDPOINT = BuildConfig.APPWRITE_ENDPOINT;
    private static final String PROJECT_ID = BuildConfig.APPWRITE_PROJECT_ID;
    // Server API key must never be embedded in the client APK. Leave empty on client.
    private static final String API_KEY = null;
    private static final String DATABASE_ID = BuildConfig.APPWRITE_DATABASE_ID;
    private static final String USERS_COLLECTION_ID = BuildConfig.APPWRITE_USERS_COLLECTION_ID;
    private static final String OTP_COLLECTION_ID = BuildConfig.APPWRITE_OTP_COLLECTION_ID;
    private static final String EMAIL_FUNCTION_ID = BuildConfig.APPWRITE_EMAIL_FUNCTION_ID;
    private static final String PARENT_OTP_FUNCTION_ID = BuildConfig.APPWRITE_PARENT_OTP_FUNCTION_ID;
    private static final String PRIVACY_FUNCTION_ID = BuildConfig.APPWRITE_PRIVACY_FUNCTION_ID;
    
    // Singleton instance
    private static AppwriteConfig instance;
    private Client client;
    private Account account;
    private Databases databases;
    private Functions functions;
    private Context context;
    
    private AppwriteConfig(Context context) {
        this.context = context.getApplicationContext();
        initializeClient();
    }
    
    public static synchronized AppwriteConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppwriteConfig(context);
        }
        return instance;
    }
    
    // For backward compatibility - but requires initialization with context first
    public static synchronized AppwriteConfig getInstance() {
        if (instance == null) {
            throw new IllegalStateException("AppwriteConfig must be initialized with context first. Call getInstance(Context) first.");
        }
        return instance;
    }
    
    private void initializeClient() {
        try {
            android.util.Log.d("AppwriteConfig", "ðŸ”§ Initializing Appwrite client...");
            
            // Initialize Appwrite Client
            client = new Client(context, ENDPOINT, PROJECT_ID);
            
            // Initialize Services
            account = new Account(client);
            databases = new Databases(client);
            functions = new Functions(client);
            
            android.util.Log.d("AppwriteConfig", "âœ… Appwrite client initialized successfully");
            android.util.Log.d("AppwriteConfig", "ðŸ“¡ Endpoint: " + ENDPOINT);
            android.util.Log.d("AppwriteConfig", "ðŸ—‚ï¸ Project ID: " + PROJECT_ID);
            
        } catch (Exception e) {
            android.util.Log.e("AppwriteConfig", "âŒ Error initializing Appwrite client: " + e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Appwrite client", e);
        }
    }
    
    // Getters
    public Client getClient() {
        return client;
    }
    
    public Account getAccount() {
        return account;
    }
    
    public Databases getDatabases() {
        return databases;
    }
    
    public Functions getFunctions() {
        return functions;
    }
    
    public String getDatabaseId() {
        return DATABASE_ID;
    }
    
    public String getUsersCollectionId() {
        return USERS_COLLECTION_ID;
    }
    
    public String getOtpCollectionId() {
        return OTP_COLLECTION_ID;
    }
    
    public String getEmailFunctionId() {
        return EMAIL_FUNCTION_ID;
    }

    public String getParentOtpFunctionId() {
        return PARENT_OTP_FUNCTION_ID;
    }

    public String getPrivacyFunctionId() {
        return PRIVACY_FUNCTION_ID;
    }
    
    public String getApiKey() {
        // Clients must not expose server keys. Return null so callers won't send it.
        return null;
    }
    
    public String getEndpoint() {
        return ENDPOINT;
    }
    
    public String getProjectId() {
        return PROJECT_ID;
    }
}
