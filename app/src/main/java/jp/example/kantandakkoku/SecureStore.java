package jp.example.kantandakkoku;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

/** Credentials are encrypted locally using non-exportable Android Keystore key. */
public final class SecureStore {
    private static final String ALIAS = "ccbizmate-credentials-aes-v1";
    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public void save(String company, String account, String password) throws Exception {
        JSONObject json = new JSONObject();
        json.put("company", company);
        json.put("account", account);
        json.put("password", password);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply();
    }

    public Credentials read() {
        try {
            String ivText = prefs.getString("iv", "");
            String encryptedText = prefs.getString("data", "");
            if (ivText.isEmpty() || encryptedText.isEmpty()) return null;
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(encryptedText, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            JSONObject json = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            return new Credentials(json.getString("company"), json.getString("account"), json.getString("password"));
        } catch (Exception ex) {
            // Keystore key can become inaccessible after lock-screen changes or device restore.
            return null;
        }
    }

    public void clear() { prefs.edit().clear().apply(); }

    public static final class Credentials {
        public final String company, account, password;
        Credentials(String company, String account, String password) {
            this.company = company;
            this.account = account;
            this.password = password;
        }
    }
}
