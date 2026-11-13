package local.jile.v5;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SiteLoginStore {
    private static final String KEY_ALIAS = "jile.site-logins.v1";
    private static final int IV_LENGTH = 12;
    private final SharedPreferences saved;

    SiteLoginStore(Context context) {
        saved = context.getSharedPreferences("site-login-secrets", Context.MODE_PRIVATE);
    }

    static String origin(String url) {
        String origin = FamilyExtras.origin(url);
        return origin.startsWith("https://") ? origin : null;
    }

    private static String id(String origin) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    JSONObject load(String origin) throws Exception {
        String blob = saved.getString(id(origin), null);
        if (blob == null) return null;
        byte[] data = Base64.getDecoder().decode(blob);
        if (data.length < IV_LENGTH + 16) throw new IOException("Invalid saved login");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, IV_LENGTH));
        cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
        return new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
    }

    void save(String origin, String username, String password) throws Exception {
        if (!origin.equals(origin(origin)) || username.isEmpty() || username.length() > 1024
                || password.isEmpty() || password.length() > 4096) throw new IllegalArgumentException();
        byte[] plaintext = new JSONObject().put("username", username).put("password", password)
                .toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] data = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, data, iv.length, encrypted.length);
        if (!saved.edit().putString(id(origin), Base64.getEncoder().encodeToString(data)).commit()) {
            throw new IOException("Save failed");
        }
    }

    void clear(String origin) throws Exception {
        if (!saved.edit().remove(id(origin)).commit()) throw new IOException("Clear failed");
    }
}
