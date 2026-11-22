package io.alensi.insure.platform.pii;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Identifiers such as national IDs (data dictionary, "PII"): stored AES-256-GCM encrypted, found by
 * an HMAC-SHA256 hash of the normalised value, and shown only masked ({@code ****5678}).
 */
@Component
public class PiiProtector {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hashKey;
    private final SecureRandom random = new SecureRandom();

    PiiProtector(Keys keys) {
        this.encryptionKey = new SecretKeySpec(key("insure.pii.encryption-key", keys.encryptionKey()), "AES");
        this.hashKey = new SecretKeySpec(key("insure.pii.hash-key", keys.hashKey()), "HmacSHA256");
    }

    /** Upper case without spaces, dashes or dots, so {@code "12 345-678"} and {@code "12345678"} match. */
    public static String normalise(String identifier) {
        return identifier.replaceAll("[\\s\\-.]", "").toUpperCase(Locale.ROOT);
    }

    public byte[] encrypt(String identifier) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(normalise(identifier).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(IV_BYTES + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt", ex);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            return new String(cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not decrypt", ex);
        }
    }

    /** Lookup and duplicate-detection hash; the identifier itself is never stored in clear. */
    public String hash(String identifier) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(normalise(identifier).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not hash", ex);
        }
    }

    /** {@code ****5678}: the last four characters only. */
    public String mask(byte[] stored) {
        if (stored == null) {
            return null;
        }
        String identifier = decrypt(stored);
        return "****" + identifier.substring(Math.max(0, identifier.length() - 4));
    }

    private static byte[] key(String property, String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(property + " is not set (base64, 32 bytes)");
        }
        byte[] key = Base64.getDecoder().decode(base64);
        if (key.length != 32) {
            throw new IllegalStateException(property + " must be 32 bytes, not " + key.length);
        }
        return key;
    }

    @ConfigurationProperties("insure.pii")
    record Keys(String encryptionKey, String hashKey) {
    }
}
