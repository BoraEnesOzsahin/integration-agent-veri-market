package com.ayrotek.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExposurePolicyServiceTest {

    @TempDir
    Path dir;

    private ExposurePolicyService service(Path file) {
        return new ExposurePolicyService(file.toString(), "Kurum_T");
    }

    /** Liste yoksa hiçbir tablo açık değildir. */
    @Test
    void deniesEverythingWithoutPolicy() {
        ExposurePolicyService policy = service(dir.resolve("yok.json"));
        assertThrows(ResponseStatusException.class, () -> policy.requireAllowed("verimarket_veri", "tutar"));
        assertTrue(policy.allowedColumns("verimarket_veri").isEmpty());
    }

    @Test
    void allowsOnlySelectedColumnsAndSurvivesRestart() {
        Path file = dir.resolve("policy.json");
        service(file).replace(Map.of("verimarket_veri", List.of("Tutar", "adet"), "maaslar", List.of()));

        ExposurePolicyService reloaded = service(file);
        assertDoesNotThrow(() -> reloaded.requireAllowed("VERIMARKET_VERI", "tutar"));
        assertDoesNotThrow(() -> reloaded.requireAllowed("verimarket_veri", null));
        assertEquals(List.of("adet", "tutar"), reloaded.allowedColumns("verimarket_veri"));
        ResponseStatusException column = assertThrows(ResponseStatusException.class, () -> reloaded.requireAllowed("verimarket_veri", "maas"));
        assertEquals(403, column.getStatusCode().value());
        // Sütunsuz seçilen tablo açık sayılmaz
        assertThrows(ResponseStatusException.class, () -> reloaded.requireAllowed("maaslar", null));
    }

    @Test
    void rejectsNamesThatCouldEscapeTheQuery() {
        ExposurePolicyService policy = service(dir.resolve("p.json"));
        assertThrows(IllegalArgumentException.class, () -> policy.replace(Map.of("t; drop table x", List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed("t", "a)--"));
    }

    /** Bozuk dosya "her şey açık" diye yorumlanmaz: ajan başlamaz. */
    @Test
    void corruptPolicyFailsClosed() throws Exception {
        Path file = dir.resolve("bozuk.json");
        Files.writeString(file, "{tables:");
        assertThrows(IllegalStateException.class, () -> service(file));
    }
}
