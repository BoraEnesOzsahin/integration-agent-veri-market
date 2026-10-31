package com.ayrotek.agent.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Kalıcı Ed25519 anahtar deposu.
 *
 * Önceki sürümde imza anahtarı her başlatmada yeniden üretiliyordu; bu yüzden yeniden başlatmadan sonra
 * daha önce imzalanmış tüm VC'ler doğrulanamaz hale geliyordu. Anahtar artık dosyada saklanır
 * (satır 1: PKCS#8 özel anahtar, satır 2: X.509 açık anahtar; ikisi de Base64). Dosya yalnızca sahibi tarafından okunabilir.
 * Dosya VAR ama bozuksa sessizce yeni anahtar üretilmez (kimlik değişmesin diye) — hata fırlatılır.
 * ÜRETİMDE bu dosya bir KMS/HSM ile değiştirilmelidir.
 */
public final class Ed25519KeyStore {

    private Ed25519KeyStore() {}

    public static KeyPair loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.size() < 2) {
                    throw new IllegalStateException("Ed25519 anahtar dosyası bozuk: " + file);
                }
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines.get(0).trim())));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(lines.get(1).trim())));
                return new KeyPair(pub, priv);
            }
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n"
                    + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n";
            Files.write(file, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows: POSIX izinleri yok; dosya kullanıcı profili dizininde tutulur.
            }
            return kp;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Ed25519 anahtarı yüklenemedi/oluşturulamadı (" + file + "): " + e.getMessage(), e);
        }
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
