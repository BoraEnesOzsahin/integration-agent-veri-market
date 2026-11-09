package com.ayrotek.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

/**
 * Kurumun veri seti eşlemesi: platformdaki veri seti anahtarı ve mantıksal sütunlar → kurumun kendi tablosu ve sütunları.
 * Kurumun makinesindeki dosyada durur ve yalnızca yerel panelden değişir. Gerçek tablo ve sütun adları makineden çıkmaz.
 * Eşlenmemiş veri seti veya sütun ne yayınlanır ne sorgulanır; dosya yoksa hiçbir şey açık değildir.
 */
@Service
public class ExposurePolicyService {

    private static final Logger log = LoggerFactory.getLogger(ExposurePolicyService.class);
    private static final String NAME = "^[a-z0-9_]+$";

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;
    private volatile Policy policy;

    /** Bir veri setinin eşlemesi: kurumun tablosu ve mantıksal sütun → yerel sütun. */
    public record DatasetMapping(String table, Map<String, String> columns) {}

    /** anahtar → eşleme. */
    public record Policy(Map<String, DatasetMapping> datasets, Instant updatedAt) {}

    /** Bir mantıksal sütunun kurumdaki karşılığı. */
    public record Resolved(String table, String column) {}

    public ExposurePolicyService(@Value("${agent.policy.file:}") String configured,
                                 @Value("${agent.institution-id}") String institutionId) {
        this.file = Path.of(configured != null && !configured.isBlank()
                ? configured
                : System.getProperty("user.home") + "/.verimarket/exposure-policy-" + institutionId.toLowerCase() + ".json");
        this.policy = load();
    }

    private Policy load() {
        if (!Files.exists(file)) {
            log.warn("[ExposurePolicy] {} yok: hiçbir veri seti eşlenmedi. Yerel panelden eşleyin.", file);
            return new Policy(Map.of(), null);
        }
        try {
            JsonNode root = mapper.readTree(file.toFile());
            Map<String, DatasetMapping> datasets = new TreeMap<>();
            root.path("datasets").fields().forEachRemaining(e -> {
                Map<String, String> columns = new TreeMap<>();
                e.getValue().path("columns").fields().forEachRemaining(c -> columns.put(c.getKey(), c.getValue().asText()));
                datasets.put(e.getKey(), new DatasetMapping(e.getValue().path("table").asText(), Collections.unmodifiableMap(columns)));
            });
            // Eski biçim {"tables": {tablo: [sütun]}}: anahtar tablo adıdır, sütunlar aynı adla eşlenir
            root.path("tables").fields().forEachRemaining(e -> {
                Map<String, String> columns = new TreeMap<>();
                e.getValue().forEach(c -> columns.put(c.asText(), c.asText()));
                datasets.putIfAbsent(e.getKey(), new DatasetMapping(e.getKey(), Collections.unmodifiableMap(columns)));
            });
            datasets.forEach((key, m) -> {
                normalize(key);
                normalize(m.table());
                m.columns().forEach((logical, local) -> { normalize(logical); normalize(local); });
            });
            Instant updated = root.hasNonNull("updatedAt") ? Instant.parse(root.get("updatedAt").asText()) : null;
            log.info("[ExposurePolicy] Eşlenen veri setleri: {}", datasets.keySet());
            return new Policy(Collections.unmodifiableMap(datasets), updated);
        } catch (IOException | RuntimeException e) {
            // Bozuk dosya açık kabul edilmez
            throw new IllegalStateException("Eşleme dosyası okunamadı (" + file + "): " + e.getMessage(), e);
        }
    }

    public Policy current() {
        return policy;
    }

    public Path file() {
        return file;
    }

    /** Veri setinin eşlenmiş mantıksal sütunları. Eşleme yoksa reddedilir. */
    public DatasetMapping requireDataset(String datasetKey) {
        String key = normalize(datasetKey);
        DatasetMapping mapping = policy.datasets().get(key);
        if (mapping == null || mapping.columns().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "'" + key + "' veri seti bu kurumda eşlenmedi.");
        }
        return mapping;
    }

    /** Yayın ve sorgu için tek kapı: kurum eşlemediyse istek kurum verisine dokunmadan reddedilir. */
    public Resolved resolve(String datasetKey, String logicalColumn) {
        String column = normalize(logicalColumn);
        DatasetMapping mapping = requireDataset(datasetKey);
        String local = mapping.columns().get(column);
        if (local == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "'" + normalize(datasetKey) + "." + column + "' sütunu bu kurumda eşlenmedi.");
        }
        return new Resolved(mapping.table(), local);
    }

    /** Dosya önce geçici olarak yazılıp yerine taşınır: yarım yazılmış eşleme hiçbir zaman okunmaz. */
    public synchronized Policy replace(Map<String, DatasetMapping> requested) {
        Map<String, DatasetMapping> datasets = new TreeMap<>();
        requested.forEach((key, m) -> {
            if (m == null || m.columns() == null) {
                return;
            }
            Map<String, String> columns = new TreeMap<>();
            m.columns().forEach((logical, local) -> {
                if (local != null && !local.isBlank()) {
                    columns.put(normalize(logical), normalize(local));
                }
            });
            if (!columns.isEmpty()) {
                datasets.put(normalize(key), new DatasetMapping(normalize(m.table()), Collections.unmodifiableMap(columns)));
            }
        });
        Policy next = new Policy(Collections.unmodifiableMap(datasets), Instant.now());
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), Map.of("datasets", next.datasets(), "updatedAt", next.updatedAt().toString()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Eşleme dosyası kaydedilemedi: " + e.getMessage(), e);
        }
        policy = next;
        log.info("[ExposurePolicy] Eşleme güncellendi: {}", next.datasets().keySet());
        return next;
    }

    static String normalize(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        if (!n.matches(NAME)) {
            throw new IllegalArgumentException("Geçersiz tablo, sütun veya veri seti adı: " + name);
        }
        return n;
    }
}
