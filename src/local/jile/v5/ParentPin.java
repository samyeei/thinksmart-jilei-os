package local.jile.v5;

import android.content.SharedPreferences;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class ParentPin {
    private final SharedPreferences preferences;

    ParentPin(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    boolean configured() {
        return preferences.contains("pin_hash");
    }

    static boolean valid(String pin) {
        return pin.matches("[0-9]{6}");
    }

    boolean create(String pin) throws GeneralSecurityException {
        if (configured() || !valid(pin)) return false;
        return store(pin);
    }

    boolean change(String current, String next) throws GeneralSecurityException {
        return valid(next) && verify(current) && store(next);
    }

    private boolean store(String pin) throws GeneralSecurityException {
        byte[] salt = new byte[24];
        new SecureRandom().nextBytes(salt);
        return preferences.edit()
                .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin_hash", Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP))
                .commit();
    }

    boolean verify(String pin) throws GeneralSecurityException {
        if (!configured() || !valid(pin)) return false;
        byte[] salt = Base64.decode(preferences.getString("pin_salt", ""), Base64.DEFAULT);
        byte[] hash = Base64.decode(preferences.getString("pin_hash", ""), Base64.DEFAULT);
        return MessageDigest.isEqual(hash, derive(pin, salt));
    }

    private static byte[] derive(String pin, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 120000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
