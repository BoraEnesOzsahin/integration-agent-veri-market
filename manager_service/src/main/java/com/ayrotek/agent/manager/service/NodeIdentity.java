package com.ayrotek.agent.manager.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Kurumun Ed25519 kimliği. Ajanın sonuçları imzaladığı anahtarın aynısıdır (dosya aynı birimden okunur),
 * böylece yöneticinin onayladığı anahtar sonuç imzalarını da doğrular.
 * Dosya biçimi ajanla aynıdır: satır 1 PKCS#8 özel anahtar, satır 2 X.509 açık anahtar (Base64).
 */
@Component
public class NodeIdentity {

    private final Path file;
    private KeyPair keyPair;

    public NodeIdentity(@Value("${node.signing-key-file:}") String configuredFile,
                        @Value("${node.id:Banka_A}") String nodeId) {
        this.file = Path.of(!configuredFile.isBlank() ? configuredFile
                : System.getProperty("user.home") + "/.verimarket/agent-" + nodeId.toLowerCase() + "-ed25519.key");
    }

    public synchronized String publicKeyHex() {
        return HexFormat.of().formatHex(keyPair().getPublic().getEncoded());
    }

    public synchronized String sign(String payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keyPair().getPrivate());
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Kurum imzası atılamadı: " + e.getMessage(), e);
        }
    }

    private KeyPair keyPair() {
        if (keyPair == null) {
            keyPair = Files.exists(file) ? load() : create();
        }
        return keyPair;
    }

    private KeyPair load() {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                throw new IllegalStateException("Ed25519 anahtar dosyası bozuk: " + file);
            }
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            return new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(lines.get(1).trim()))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines.get(0).trim()))));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 anahtarı okunamadı (" + file + "): " + e.getMessage(), e);
        }
    }

    private KeyPair create() {
        try {
            KeyPair created = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = Base64.getEncoder().encodeToString(created.getPrivate().getEncoded()) + "\n"
                    + Base64.getEncoder().encodeToString(created.getPublic().getEncoded()) + "\n";
            Files.write(file, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: POSIX izinleri yok
            }
            return created;
        } catch (FileAlreadyExistsException e) {
            // Ajan aynı anda oluşturdu: onunkini kullan
            return load();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 anahtarı oluşturulamadı (" + file + "): " + e.getMessage(), e);
        }
    }
}
