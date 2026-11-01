package com.ayrotek.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerCertificateVerificationTest {

    private static final UnaryOperator<String> HASH = s -> "h" + s;

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    private static String sign(KeyPair k, String jobId, int party, String cert) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k.getPrivate());
        s.update(IntegrationAgentService.certPayload(jobId, party, HASH.apply(cert)).getBytes("UTF-8"));
        return hex(s.sign());
    }

    @Test
    void acceptsCertificatesSignedByListedInstitutions() throws Exception {
        KeyPair a = keys(), b = keys();
        Map<Integer, String> certs = Map.of(1, "c1", 2, "c2");
        Map<Integer, String> sigs = Map.of(1, sign(a, "j", 1, "c1"), 2, sign(b, "j", 2, "c2"));
        Map<String, String> keyMap = Map.of("A", hex(a.getPublic().getEncoded()), "B", hex(b.getPublic().getEncoded()));
        assertDoesNotThrow(() -> IntegrationAgentService.verifyPeerCertificates("j", 2, certs, sigs, Map.of(1, "A", 2, "B"), keyMap, HASH));
    }

    @Test
    void rejectsFakePartyAndReusedInstitution() throws Exception {
        KeyPair a = keys(), b = keys(), attacker = keys();
        Map<Integer, String> certs = Map.of(1, "c1", 2, "c2");
        Map<String, String> keyMap = Map.of("A", hex(a.getPublic().getEncoded()), "B", hex(b.getPublic().getEncoded()));

        // Sahte taraf kendi anahtarıyla imzalar
        Map<Integer, String> fake = Map.of(1, sign(a, "j", 1, "c1"), 2, sign(attacker, "j", 2, "c2"));
        assertThrows(ResponseStatusException.class, () -> IntegrationAgentService.verifyPeerCertificates("j", 2, certs, fake, Map.of(1, "A", 2, "B"), keyMap, HASH));

        // Aynı kurum iki taraf gibi gösterilir
        Map<Integer, String> twice = Map.of(1, sign(a, "j", 1, "c1"), 2, sign(a, "j", 2, "c2"));
        assertThrows(ResponseStatusException.class, () -> IntegrationAgentService.verifyPeerCertificates("j", 2, certs, twice, Map.of(1, "A", 2, "A"), keyMap, HASH));

        // Başka işin imzası yeniden kullanılır
        Map<Integer, String> other = Map.of(1, sign(a, "baska", 1, "c1"), 2, sign(b, "j", 2, "c2"));
        assertThrows(ResponseStatusException.class, () -> IntegrationAgentService.verifyPeerCertificates("j", 2, certs, other, Map.of(1, "A", 2, "B"), keyMap, HASH));

        // Eksik imza
        Map<Integer, String> missing = new HashMap<>(Map.of(1, sign(a, "j", 1, "c1")));
        assertThrows(ResponseStatusException.class, () -> IntegrationAgentService.verifyPeerCertificates("j", 2, certs, missing, Map.of(1, "A", 2, "B"), keyMap, HASH));
    }
}
