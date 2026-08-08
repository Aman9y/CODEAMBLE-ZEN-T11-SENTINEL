package online.monarchlabs.sentinel.services;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;

import online.monarchlabs.sentinel.utils.PhoneUtils;
import online.monarchlabs.sentinel.utils.DataSecurityUtils;

import java.util.concurrent.CompletableFuture;

/**
 * Parent directory service.
 * Registers a public phone-to-email mapping for phone number login securely using transactions.
 */
public final class ParentDirectoryService {
    private static final String TAG = "ParentDirectoryService";
    
    public ParentDirectoryService(Context context) {
    }

    private DatabaseReference getDirectoryRef(String phone) {
        String hashedPhoneKey = DataSecurityUtils.hashLookupKey(phone);
        return FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("directory")
                .child("phone_to_email")
                .child(hashedPhoneKey);
    }

    public CompletableFuture<Boolean> registerProfileIndex(
            String expectedUid, String email, String phone) {
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (TextUtils.isEmpty(phone) || TextUtils.isEmpty(email)) {
            future.complete(true);
            return future;
        }

        String normalizedPhone = PhoneUtils.normalize(phone);
        if (normalizedPhone.isEmpty()) {
            future.complete(true);
            return future;
        }

        String encryptedEmail = DataSecurityUtils.encryptText(email);
        getDirectoryRef(normalizedPhone).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData mutableData) {
                String currentRawValue = mutableData.getValue(String.class);
                String currentDecryptedEmail = DataSecurityUtils.decryptText(currentRawValue);
                if (currentDecryptedEmail == null || currentDecryptedEmail.equals(email)) {
                    mutableData.setValue(encryptedEmail);
                    return Transaction.success(mutableData);
                }
                return Transaction.abort();
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot currentData) {
                if (error != null) {
                    Log.e(TAG, "Failed to register phone-to-email index due to network error", error.toException());
                    future.complete(false);
                } else if (!committed) {
                    Log.e(TAG, "Failed to register phone-to-email index: Phone number already claimed by another user");
                    future.complete(false);
                } else {
                    Log.d(TAG, "Phone-to-email index successfully registered for " + normalizedPhone);
                    future.complete(true);
                }
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> updatePhoneMapping(String oldPhone, String newPhone, String email) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String normalizedNewPhone = PhoneUtils.normalize(newPhone);
        
        if (normalizedNewPhone.isEmpty()) {
            future.complete(false);
            return future;
        }

        // 1. Claim new phone
        registerProfileIndex(null, email, newPhone).thenAccept(success -> {
            if (success) {
                // 2. Remove old phone if different
                String normalizedOldPhone = PhoneUtils.normalize(oldPhone);
                if (!normalizedOldPhone.isEmpty() && !normalizedOldPhone.equals(normalizedNewPhone)) {
                    getDirectoryRef(normalizedOldPhone).removeValue();
                }
                future.complete(true);
            } else {
                future.complete(false);
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> updateEmailMapping(String phone, String newEmail) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String normalizedPhone = PhoneUtils.normalize(phone);
        
        if (normalizedPhone.isEmpty() || TextUtils.isEmpty(newEmail)) {
            future.complete(true);
            return future;
        }

        // We use set value since the current user already owns this phone
        String encryptedEmail = DataSecurityUtils.encryptText(newEmail);
        getDirectoryRef(normalizedPhone).setValue(encryptedEmail)
                .addOnSuccessListener(aVoid -> future.complete(true))
                .addOnFailureListener(e -> future.complete(false));

        return future;
    }

    public CompletableFuture<Boolean> deleteMapping(String phone) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        String normalizedPhone = PhoneUtils.normalize(phone);
        if (normalizedPhone.isEmpty()) {
            future.complete(true);
            return future;
        }
        
        getDirectoryRef(normalizedPhone).removeValue()
                .addOnSuccessListener(aVoid -> future.complete(true))
                .addOnFailureListener(e -> future.complete(false));
                
        return future;
    }
}
