package com.ayrotek.agent.service;

import com.ayrotek.agent.service.ExposurePolicyService.DatasetMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExposurePolicyServiceTest {

    @TempDir
    Path dir;

    private ExposurePolicyService service(Path file) {
        return new ExposurePolicyService(file.toString(), "Kurum_T");
    }

    /** Eşleme yoksa hiçbir veri seti açık değildir. */
    @Test
    void deniesEverythingWithoutMapping() {
        ExposurePolicyService policy = service(dir.resolve("yok.json"));
        assertThrows(ResponseStatusException.class, () -> policy.resolve("kar_analizi", "kar"));
        assertThrows(ResponseStatusException.class, () -> policy.requireDataset("kar_analizi"));
    }

    /** Mantıksal ad kurumun kendi tablosuna ve sütununa çevrilir; yeniden başlatmadan sonra da. */
    @Test
    void resolvesLogicalNamesToLocalOnesAndSurvivesRestart() {
        Path file = dir.resolve("policy.json");
        service(file).replace(Map.of(
                "kar_analizi", new DatasetMapping("banka_finans_ozet", Map.of("kar", "net_kar", "gelir", "brut_gelir")),
                "bos", new DatasetMapping("x", Map.of("a", ""))));

        ExposurePolicyService reloaded = service(file);
        assertEquals(new ExposurePolicyService.Resolved("banka_finans_ozet", "net_kar"), reloaded.resolve("KAR_ANALIZI", "kar"));
        assertEquals(new ExposurePolicyService.Resolved("banka_finans_ozet", "brut_gelir"), reloaded.resolve("kar_analizi", "gelir"));
        ResponseStatusException column = assertThrows(ResponseStatusException.class, () -> reloaded.resolve("kar_analizi", "gider"));
        assertEquals(403, column.getStatusCode().value());
        // Sütunu eşlenmemiş veri seti açık sayılmaz
        assertThrows(ResponseStatusException.class, () -> reloaded.requireDataset("bos"));
    }

    /** Eski dosya biçimi: anahtar tablo adıdır, sütunlar aynı adla eşlenir. */
    @Test
    void readsTheOldSharingListFormat() throws Exception {
        Path file = dir.resolve("eski.json");
        Files.writeString(file, "{\"tables\":{\"gelir_gider\":[\"kar\",\"gelir\"]}}");
        ExposurePolicyService policy = service(file);
        assertEquals(new ExposurePolicyService.Resolved("gelir_gider", "kar"), policy.resolve("gelir_gider", "kar"));
        assertThrows(ResponseStatusException.class, () -> policy.resolve("gelir_gider", "gider"));
    }

    @Test
    void rejectsNamesThatCouldEscapeTheQuery() {
        ExposurePolicyService policy = service(dir.resolve("p.json"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.replace(Map.of("k", new DatasetMapping("t; drop table x", Map.of("a", "b")))));
        assertThrows(IllegalArgumentException.class,
                () -> policy.replace(Map.of("k", new DatasetMapping("t", Map.of("a", "b)--")))));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve("k", "a)--"));
    }

    /** Bozuk dosya "her şey açık" diye yorumlanmaz: ajan başlamaz. */
    @Test
    void corruptPolicyFailsClosed() throws Exception {
        Path file = dir.resolve("bozuk.json");
        Files.writeString(file, "{datasets:");
        assertThrows(IllegalStateException.class, () -> service(file));
    }
}
