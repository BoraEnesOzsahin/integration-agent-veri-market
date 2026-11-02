package com.ayrotek.agent.service;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tüketici anahtarına RSA-OAEP şifreleme, kuruma özel mühürleme ve SHA-256 özeti.
 */
@Service
public class AgentCryptoService {

    // Tarayıcı Web Crypto yalnızca özet ve MGF1 aynı olunca açar; Java'nın varsayılanı MGF1-SHA-1 olduğu için açıkça verilir.
    private static final javax.crypto.spec.OAEPParameterSpec OAEP_SHA256 = new javax.crypto.spec.OAEPParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT);

    private final SecureRandom random = new SecureRandom();

    /** Mühürleme anahtarı yalnızca bellekte tutulur; ajan yeniden başlayınca yenilenir. */
    private volatile KeyPair sealingKeyPair;

    private synchronized KeyPair sealingKey() {
        if (sealingKeyPair == null) {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(3072, random);
                sealingKeyPair = kpg.generateKeyPair();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("RSA anahtar üreticisi başlatılamadı: " + e.getMessage(), e);
            }
        }
        return sealingKeyPair;
    }

    /**
     * RSA'nın sınırını aşan veriyi yalnızca bu düğümün açabileceği biçimde mühürler:
     * Base64(RSA-OAEP(AES anahtarı) || IV || AES-256-GCM(veri)).
     */
    public String sealForThisNode(String plaintext) {
        try {
            byte[] aesKey = new byte[32];
            byte[] iv = new byte[12];
            random.nextBytes(aesKey);
            random.nextBytes(iv);

            Cipher wrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            wrap.init(Cipher.ENCRYPT_MODE, sealingKey().getPublic(), OAEP_SHA256);
            byte[] wrappedKey = wrap.doFinal(aesKey);

            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(aesKey, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] body = gcm.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[wrappedKey.length + iv.length + body.length];
            System.arraycopy(wrappedKey, 0, out, 0, wrappedKey.length);
            System.arraycopy(iv, 0, out, wrappedKey.length, iv.length);
            System.arraycopy(body, 0, out, wrappedKey.length + iv.length, body.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Mühürleme hatası: " + e.getMessage(), e);
        }
    }

    /** Tüketiciye özel büyük veri: RSA'nın sınırını aştığı için AES-256-GCM ile şifrelenir, anahtarı RSA-OAEP ile sarılır. */
    public record ConsumerSealed(String wrappedKey, String iv, String ciphertext) {}

    public ConsumerSealed sealForConsumer(String publicKeyStr, String plaintext) {
        try {
            byte[] aesKey = new byte[32];
            byte[] iv = new byte[12];
            random.nextBytes(aesKey);
            random.nextBytes(iv);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(aesKey, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] body = gcm.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Cipher wrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            wrap.init(Cipher.ENCRYPT_MODE, consumerKey(publicKeyStr), OAEP_SHA256);
            Base64.Encoder b64 = Base64.getEncoder();
            return new ConsumerSealed(b64.encodeToString(wrap.doFinal(aesKey)), b64.encodeToString(iv), b64.encodeToString(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Tüketici açık anahtarı geçersiz: " + e.getMessage(), e);
        }
    }

    private static PublicKey consumerKey(String publicKeyStr) throws GeneralSecurityException {
        String cleaned = publicKeyStr.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(cleaned)));
    }

    /** Tüketici açık anahtarı ile RSA/ECB/OAEPWithSHA-256AndMGF1Padding şifreleme uygular. */
    public String encryptWithRsaOaep(String publicKeyStr, String plaintext) {
        try {
            PublicKey pubKey = consumerKey(publicKeyStr);
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pubKey, OAEP_SHA256);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Tüketici açık anahtarı geçersiz: " + e.getMessage(), e);
        }
    }

    public String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
