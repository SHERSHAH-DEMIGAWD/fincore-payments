package com.fincore.payments.merchantwebhook;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Recoverable signing keys, bound to the immutable subscription and external master-key ID. */
public final class MerchantWebhookCrypto {
    private static final SecureRandom RANDOM=new SecureRandom();
    private MerchantWebhookCrypto() { }

    public record Sealed(byte[] nonce,byte[] ciphertext) {
        public Sealed { nonce=nonce.clone(); ciphertext=ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public String toString() { return "Sealed[REDACTED]"; }
    }

    public static String generateSecret() {
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        finally { Arrays.fill(bytes,(byte)0); }
    }

    public static Sealed encrypt(String masterKeyBase64,String masterKeyId,UUID merchant,UUID subscription,String secret) {
        byte[] key=null,plain=null;
        try {
            key=masterKey(masterKeyBase64);
            if (!validSecret(secret)) throw new IllegalArgumentException();
            byte[] nonce=new byte[12]; RANDOM.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(masterKeyId,merchant,subscription));
            plain=secret.getBytes(StandardCharsets.US_ASCII);
            return new Sealed(nonce,cipher.doFinal(plain));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Merchant webhook encryption configuration is invalid.");
        } finally { erase(key); erase(plain); }
    }

    public static String decrypt(String masterKeyBase64,String masterKeyId,UUID merchant,UUID subscription,byte[] nonce,byte[] ciphertext) {
        byte[] key=null,plain=null;
        try {
            key=masterKey(masterKeyBase64);
            if (nonce==null || nonce.length!=12 || ciphertext==null || ciphertext.length<48 || ciphertext.length>272)
                throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(aad(masterKeyId,merchant,subscription));
            plain=cipher.doFinal(ciphertext);
            for (byte value:plain) if (value<33 || value>126) throw new IllegalArgumentException();
            String secret=new String(plain,StandardCharsets.US_ASCII);
            if (!validSecret(secret)) throw new IllegalArgumentException();
            return secret;
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IllegalStateException("Merchant webhook signing key is unavailable.");
        } finally { erase(key); erase(plain); }
    }

    public static String signature(String signingSecret,String keyId,long timestamp,byte[] body) {
        if (!validSecret(signingSecret) || keyId==null || !keyId.matches("[A-Za-z0-9_-]{1,64}")
                || timestamp<=0 || body==null || body.length<1 || body.length>16384)
            throw new IllegalArgumentException("Merchant webhook signing input is invalid.");
        byte[] key=signingSecret.getBytes(StandardCharsets.US_ASCII);
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            mac.update(("fincore-merchant-webhook-v1\n"+keyId+"\n"+timestamp+"\n").getBytes(StandardCharsets.US_ASCII));
            return "v1="+HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Merchant webhook signature algorithm is unavailable.");
        } finally { erase(key); }
    }

    public static String sha256(byte[] body) {
        if (body==null) throw new IllegalArgumentException("Merchant webhook body is required.");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (GeneralSecurityException unavailable) { throw new IllegalStateException("Merchant webhook digest algorithm is unavailable."); }
    }

    private static byte[] masterKey(String encoded) {
        if (encoded==null) throw new IllegalArgumentException();
        byte[] key=Base64.getDecoder().decode(encoded);
        if (key.length!=32 || !Base64.getEncoder().encodeToString(key).equals(encoded)) {
            erase(key); throw new IllegalArgumentException();
        }
        return key;
    }
    private static byte[] aad(String masterKeyId,UUID merchant,UUID subscription) {
        if (masterKeyId==null || !masterKeyId.matches("[A-Za-z0-9_-]{1,64}") || merchant==null || subscription==null
                || merchant.equals(new UUID(0,0)) || subscription.equals(new UUID(0,0))) throw new IllegalArgumentException();
        return ("fincore-merchant-webhook-secret-v1\n"+masterKeyId+"\n"+merchant+"\n"+subscription+"\n").getBytes(StandardCharsets.US_ASCII);
    }
    private static boolean validSecret(String value) { return value!=null && value.matches("[!-~]{32,256}"); }
    private static void erase(byte[] bytes) { if (bytes!=null) Arrays.fill(bytes,(byte)0); }
}
