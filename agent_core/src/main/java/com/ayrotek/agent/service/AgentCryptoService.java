package com.ayrotek.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Kurum İçi Entegrasyon Ajanı - Kriptografik Hesaplama ve Şifreleme Servisi.
 *
 * Sorumluluklar:
 * 1. Shamir Gizli Paylaşımı için polinom katsayıları üretme ve Horner metodu ile pay hesaplama.
 * 2. Tüketici Açık Anahtarıyla RSA-OAEP-256 şifreleme ve parmak izi çıkarma.
 * 3. SHA-256 kriptografik özet üretimi.
 */
@Service
public class AgentCryptoService {

    private static final Logger log = LoggerFactory.getLogger(AgentCryptoService.class);

    // 64-bit Mersenne Asalı: 2^61 - 1 (Matematiksel MPC Asal Alan Sabiti)
    public static final BigInteger PRIME = BigInteger.valueOf(2).pow(61).subtract(BigInteger.ONE);

    private final SecureRandom random = new SecureRandom();

    @Value("${agent.default-consumer-public-key:}")
    private String defaultConsumerPublicKey;

    // Tüketici anahtar belirtmediğinde otomatik üretilen dinamik oturum anahtarı (Hardcoded string yerine güvenli bellek içi anahtar)
    private volatile KeyPair ephemeralSessionKeyPair;

    private synchronized KeyPair getOrCreateEphemeralKeyPair() {
        if (ephemeralSessionKeyPair == null) {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048, random);
                ephemeralSessionKeyPair = kpg.generateKeyPair();
                log.info("[AgentCryptoService] Dinamik oturum RSA-2048 anahtar çifti üretildi.");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("RSA anahtar üreticisi başlatılamadı: " + e.getMessage(), e);
            }
        }
        return ephemeralSessionKeyPair;
    }

    // -------------------------------------------------------------------------
    // Düğümler arası (peer-to-peer) pay taşıma anahtarı.
    // Koordinatör payları ASLA düz metin görmez: her pay hedef ajanın bu anahtarıyla şifrelenir.
    // Anahtar yalnızca bellekte tutulur; ajan yeniden başlayınca yenilenir (oturum payları zaten bellek içi).
    // -------------------------------------------------------------------------
    private volatile KeyPair nodeTransportKeyPair;

    private synchronized KeyPair getOrCreateNodeTransportKeyPair() {
        if (nodeTransportKeyPair == null) {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(3072, random);
                nodeTransportKeyPair = kpg.generateKeyPair();
                log.info("[AgentCryptoService] Düğümler arası pay taşıma RSA-3072 anahtar çifti üretildi.");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("RSA anahtar üreticisi başlatılamadı: " + e.getMessage(), e);
            }
        }
        return nodeTransportKeyPair;
    }

    /** Bu düğümün pay taşıma açık anahtarı (Base64 X.509). Eşler payları bununla şifreler. */
    public String getNodeTransportPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(getOrCreateNodeTransportKeyPair().getPublic().getEncoded());
    }

    /** Eşlerden gelen RSA-OAEP şifreli payı bu düğümün özel anahtarıyla çözer. */
    public String decryptWithNodeTransportKey(String base64Ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateNodeTransportKeyPair().getPrivate());
            return new String(cipher.doFinal(Base64.getDecoder().decode(base64Ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Şifreli pay çözülemedi (yanlış anahtar veya bozuk veri): " + e.getMessage(), e);
        }
    }

    /**
     * Eşik değeri (threshold) kadar Shamir polinom katsayıları üretir:
     * f(x) = secret + a_1*x + ... + a_{t-1}*x^{t-1} mod p
     */
    public BigInteger[] generatePolynomialCoefficients(BigInteger secret, int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Eşik değeri (threshold) pozitif olmalıdır!");
        }
        BigInteger[] coeffs = new BigInteger[threshold];
        coeffs[0] = secret != null ? secret.mod(PRIME) : BigInteger.ZERO;
        for (int i = 1; i < threshold; i++) {
            BigInteger c;
            do {
                c = new BigInteger(PRIME.bitLength(), random);
            } while (c.compareTo(BigInteger.ZERO) <= 0 || c.compareTo(PRIME) >= 0);
            coeffs[i] = c;
        }
        return coeffs;
    }

    /**
     * Horner metodu ile f(x) mod PRIME değerini hesaplar.
     */
    public BigInteger evaluatePolynomial(BigInteger[] coeffs, int x, int threshold) {
        BigInteger xVal = BigInteger.valueOf(x);
        BigInteger shareVal = BigInteger.ZERO;
        for (int i = threshold - 1; i >= 0; i--) {
            shareVal = shareVal.multiply(xVal).add(coeffs[i]).mod(PRIME);
        }
        return shareVal;
    }

    /**
     * Tüketici açık anahtarı ile RSA/ECB/OAEPWithSHA-256AndMGF1Padding şifreleme uygular.
     * Anahtar belirtilmemişse konfigürasyondan veya dinamik oturum anahtarından faydalanır.
     */
    public String encryptWithRsaOaep(String publicKeyStr, String plaintext) {
        try {
            PublicKey pubKey = resolvePublicKey(publicKeyStr);
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pubKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("RSA şifreleme hatası: " + e.getMessage(), e);
        }
    }

    /**
     * Açık anahtarın SHA-256 parmak izini hesaplar.
     */
    public String calculateFingerprint(String publicKeyStr) {
        try {
            String keyToHash = (publicKeyStr != null && !publicKeyStr.isBlank())
                    ? publicKeyStr
                    : getEffectivePublicKeyBase64();

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(keyToHash.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("SHA256:");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "UNKNOWN_FINGERPRINT";
        }
    }

    /**
     * Girdinin SHA-256 hex özetini üretir.
     */
    public String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    /**
     * Tüketici anahtarı verilmemişse bellekte üretilen geçici anahtara şifrelemek, kimsenin açamayacağı bir
     * sonuç üretir ve sessizce "başarılı" görünür. Bu yüzden varsayılan olarak hata verir
     * (yalnızca agent.crypto.allow-ephemeral-key=true ile demo için açılır).
     */
    @Value("${agent.crypto.allow-ephemeral-key:false}")
    private boolean allowEphemeralKey = false;

    public String getEffectivePublicKeyBase64() {
        if (defaultConsumerPublicKey != null && !defaultConsumerPublicKey.isBlank()) {
            return defaultConsumerPublicKey;
        }
        if (!allowEphemeralKey) {
            throw new IllegalArgumentException("Tüketici açık anahtarı (consumerPublicKey) zorunludur; geçici anahtara şifreleme kapalı.");
        }
        return Base64.getEncoder().encodeToString(getOrCreateEphemeralKeyPair().getPublic().getEncoded());
    }

    private PublicKey resolvePublicKey(String publicKeyStr) throws Exception {
        String effectiveKey = (publicKeyStr != null && !publicKeyStr.isBlank())
                ? publicKeyStr
                : defaultConsumerPublicKey;

        if (effectiveKey != null && !effectiveKey.isBlank()) {
            String cleaned = effectiveKey.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(cleaned);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        }

        if (!allowEphemeralKey) {
            throw new IllegalArgumentException("Tüketici açık anahtarı (consumerPublicKey) zorunludur; geçici anahtara şifreleme kapalı.");
        }
        return getOrCreateEphemeralKeyPair().getPublic();
    }
}
