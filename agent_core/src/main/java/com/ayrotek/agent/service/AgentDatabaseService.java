package com.ayrotek.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.*;
import java.util.*;

/**
 * Kurum İçi Entegrasyon Ajanı - Yerel Veritabanı Servisi.
 *
 * Sorumluluklar:
 * 1. Kurumun DMZ sınırları içerisindeki yerel PostgreSQL veritabanına bağlanma.
 * 2. Dinamik metrik sorguları ve ZKP predicate kontrolleri (ham veri asla kurum dışına çıkmaz).
 * 3. Şema ve tablo bilgilerini metadata üzerinden listeleme.
 */
@Service
public class AgentDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(AgentDatabaseService.class);

    @Value("${agent.db.url:${SPRING_DATASOURCE_URL:}}")
    private String dbUrl;

    @Value("${agent.db.username:${SPRING_DATASOURCE_USERNAME:verimarket}}")
    private String dbUsername = "verimarket";

    @Value("${agent.db.password:${SPRING_DATASOURCE_PASSWORD:verimarket_password}}")
    private String dbPassword = "verimarket_password";

    public boolean isDbConnected() {
        return dbUrl != null && !dbUrl.isBlank();
    }

    public String getDbUrl() {
        return dbUrl;
    }

    /**
     * Kurumun izole PostgreSQL veritabanından dinamik hedef metriği sorgular.
     * Eğer hedef kolon veya tablo veritabanında yoksa SQLException fırlatılır.
     */
    public BigInteger queryLocalDatabaseMetric(String datasetName, String targetMetric, String institutionId) throws SQLException {
        if (!isDbConnected()) {
            return null;
        }

        String table = (datasetName != null && !datasetName.isBlank()) ? datasetName.trim().toLowerCase() : "gelir_gider";
        String column = (targetMetric != null && !targetMetric.isBlank()) ? targetMetric.trim().toLowerCase() : "kar";

        // SQL Injection ve format denetimi: Sadece harf, rakam ve altçizgi
        if (!table.matches("^[a-zA-Z0-9_]+$") || !column.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Geçersiz tablo veya kolon adı formatı: " + table + "." + column);
        }

        String sql = "SELECT COALESCE(SUM(" + column + "), 0) FROM " + table;
        log.info("[AgentDatabase - {}] Yerel PostgreSQL sorgusu yürütülüyor: {} (DB: {})", institutionId, sql, dbUrl);

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                long val = rs.getLong(1);
                log.info("[AgentDatabase - {}] Yerel PostgreSQL sonucu alındı (DMZ içinde gizli tutuluyor)", institutionId);
                return BigInteger.valueOf(val);
            }
            return BigInteger.ZERO;
        }
    }

    /**
     * ZKP koşullarında sorgulanan niteliğin (predicate attribute) yerel PostgreSQL'deki değerini denetler.
     * Eğer kolon veritabanında mevcut değilse SQLException fırlatılır.
     */
    public Long queryPredicateAttribute(String datasetName, String attrName) throws SQLException {
        if (!isDbConnected()) {
            return null;
        }

        String table = (datasetName != null && !datasetName.isBlank()) ? datasetName.trim().toLowerCase() : "gelir_gider";
        String column = (attrName != null && !attrName.isBlank()) ? attrName.trim().toLowerCase() : "kar";

        if (!table.matches("^[a-zA-Z0-9_]+$") || !column.matches("^[a-zA-Z0-9_]+$")) {
            return null;
        }

        String sql = "SELECT COALESCE(MAX(" + column + "), 0) FROM " + table;
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        }
    }

    /** Tablo veya view'daki sayısal veri sütunları (sıralı). Kimlik sütunları ölçüm sayılmaz. */
    public List<String> listMetricColumns(String tableName) throws SQLException {
        if (!isDbConnected()) {
            return List.of();
        }
        String table = tableName.trim().toLowerCase();
        if (!table.matches("^[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Geçersiz tablo adı: " + table);
        }
        List<String> columns = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
             ResultSet rs = conn.getMetaData().getColumns(null, "public", table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                boolean numeric = switch (rs.getInt("DATA_TYPE")) {
                    case Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.DOUBLE, Types.FLOAT -> true;
                    default -> false;
                };
                if (numeric && !name.equalsIgnoreCase("id") && !name.toLowerCase().endsWith("_id")) {
                    columns.add(name);
                }
            }
        }
        return columns;
    }

    /**
     * Kurumun yerel PostgreSQL veritabanındaki tabloları ve şemayı listeler.
     * Ham veriler dışarı sızmaz, sadece tablo adları, kolonlar ve veri tipleri arayüze sunulur.
     */
    public Map<String, Object> getLocalDatabaseOverview(String institutionId, String institutionName, int serverPort) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("institutionId", institutionId);
        overview.put("institutionName", institutionName);
        overview.put("dbUrl", dbUrl);
        overview.put("dbConnected", isDbConnected());
        overview.put("serverPort", serverPort);

        List<Map<String, Object>> tables = new ArrayList<>();
        if (isDbConnected()) {
            try (Connection conn = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)) {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getTables(null, "public", "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        if ("flyway_schema_history".equalsIgnoreCase(tableName)) continue;

                        List<Map<String, Object>> columns = new ArrayList<>();
                        try (ResultSet colRs = meta.getColumns(null, "public", tableName, "%")) {
                            while (colRs.next()) {
                                String colName = colRs.getString("COLUMN_NAME");
                                String typeName = colRs.getString("TYPE_NAME");
                                int colSize = colRs.getInt("COLUMN_SIZE");
                                boolean sensitive = (colName.contains("kar") || colName.contains("gelir") || colName.contains("gider")
                                        || colName.contains("tutar") || colName.contains("bakiye") || colName.contains("ciro")
                                        || colName.contains("maas") || colName.contains("limit") || colName.contains("skor"));
                                columns.add(Map.of(
                                        "name", colName,
                                        "type", typeName,
                                        "size", colSize,
                                        "sensitiveCandidate", sensitive
                                    ));
                            }
                        }

                        long rowCount = 0L;
                        try (Statement stmt = conn.createStatement();
                             ResultSet countRs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                            if (countRs.next()) {
                                rowCount = countRs.getLong(1);
                            }
                        } catch (Exception ignored) {}

                        tables.add(Map.of(
                                "tableName", tableName,
                                "rowCount", rowCount,
                                "columns", columns
                        ));
                    }
                }
            } catch (Exception e) {
                log.warn("[AgentDatabase - {}] Yerel veritabanı şeması listelenirken hata: {}", institutionId, e.getMessage());
                overview.put("error", e.getMessage());
            }
        }
        overview.put("tables", tables);
        return overview;
    }
}
