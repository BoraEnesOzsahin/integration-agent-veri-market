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
 * Kurumun paylaşıma açtığı tablolar ve sütunlar. Kurumun kendi makinesindeki dosyada durur ve yalnızca yerel panelden değişir.
 * Listede olmayan tablo veya sütun ne yayınlanır ne sorgulanır; dosya yoksa hiçbir şey açık değildir.
 */
@Service
public class ExposurePolicyService {

    private static final Logger log = LoggerFactory.getLogger(ExposurePolicyService.class);
    private static final String NAME = "^[a-z0-9_]+$";

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path file;
    private volatile Policy policy;

    /** tablo → paylaşılan sütunlar. */
    public record Policy(Map<String, List<String>> tables, Instant updatedAt) {}

    public ExposurePolicyService(@Value("${agent.policy.file:}") String configured,
                                 @Value("${agent.institution-id}") String institutionId) {
        this.file = Path.of(configured != null && !configured.isBlank()
                ? configured
                : System.getProperty("user.home") + "/.verimarket/exposure-policy-" + institutionId.toLowerCase() + ".json");
        this.policy = load();
    }

    private Policy load() {
        if (!Files.exists(file)) {
            log.warn("[ExposurePolicy] {} yok: hiçbir tablo paylaşıma açık değil. Yerel panelden seçin.", file);
            return new Policy(Map.of(), null);
        }
        try {
            JsonNode root = mapper.readTree(file.toFile());
            Map<String, List<String>> tables = new TreeMap<>();
            root.path("tables").fields().forEachRemaining(e -> {
                List<String> columns = new ArrayList<>();
                e.getValue().forEach(c -> columns.add(c.asText()));
                tables.put(e.getKey(), List.copyOf(columns));
            });
            Instant updated = root.hasNonNull("updatedAt") ? Instant.parse(root.get("updatedAt").asText()) : null;
            log.info("[ExposurePolicy] Paylaşılan tablolar: {}", tables.keySet());
            return new Policy(Collections.unmodifiableMap(tables), updated);
        } catch (IOException | RuntimeException e) {
            // Bozuk dosya açık kabul edilmez
            throw new IllegalStateException("Paylaşım listesi okunamadı (" + file + "): " + e.getMessage(), e);
        }
    }

    public Policy current() {
        return policy;
    }

    public Path file() {
        return file;
    }

    public List<String> allowedColumns(String table) {
        return policy.tables().getOrDefault(normalize(table), List.of());
    }

    /** Yayın ve sorgu için tek kapı: kurum açmadıysa istek kurum verisine dokunmadan reddedilir. */
    public void requireAllowed(String table, String column) {
        String t = normalize(table);
        String c = column == null ? null : normalize(column);
        List<String> columns = policy.tables().get(t);
        if (columns == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "'" + t + "' tablosu bu kurumda paylaşıma açık değil.");
        }
        if (c != null && !columns.contains(c)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "'" + t + "." + c + "' sütunu bu kurumda paylaşıma açık değil.");
        }
    }

    /** Dosya önce geçici olarak yazılıp yerine taşınır: yarım yazılmış liste hiçbir zaman okunmaz. */
    public synchronized Policy replace(Map<String, List<String>> requested) {
        Map<String, List<String>> tables = new TreeMap<>();
        requested.forEach((table, columns) -> {
            String t = normalize(table);
            List<String> cols = columns == null ? List.of() : columns.stream().map(ExposurePolicyService::normalize).distinct().sorted().toList();
            if (!cols.isEmpty()) {
                tables.put(t, cols);
            }
        });
        Policy next = new Policy(Collections.unmodifiableMap(tables), Instant.now());
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), Map.of("tables", next.tables(), "updatedAt", next.updatedAt().toString()));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Paylaşım listesi kaydedilemedi: " + e.getMessage(), e);
        }
        policy = next;
        log.info("[ExposurePolicy] Paylaşım listesi güncellendi: {}", next.tables());
        return next;
    }

    static String normalize(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        if (!n.matches(NAME)) {
            throw new IllegalArgumentException("Geçersiz tablo veya sütun adı: " + name);
        }
        return n;
    }
}
