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
 * 2. Yalnızca kurumun eşlediği veri seti sütunlarını okuma (ham veri asla kurum dışına çıkmaz).
 * 3. Yerel panel için şema bilgisini metadata üzerinden listeleme.
 */
@Service
public class AgentDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(AgentDatabaseService.class);

    @Value("${agent.db.url:${SPRING_DATASOURCE_URL:}}")
    private String dbUrl;

    @Value("${agent.db.username:${SPRING_DATASOURCE_USERNAME:}}")
    private String dbUsername;

    @Value("${agent.db.password:${SPRING_DATASOURCE_PASSWORD:}}")
    private String dbPassword;

    private final ExposurePolicyService policy;

    public AgentDatabaseService(ExposurePolicyService policy) {
        this.policy = policy;
    }

    public boolean isDbConnected() {
        return dbUrl != null && !dbUrl.isBlank();
    }

    /**
     * Veri setinin mantıksal sütununun kurumun tablosundaki toplamı. Eşlenmemişse veritabanına hiç gidilmez.
     * Eğer eşlenen tablo veya sütun veritabanında yoksa SQLException fırlatılır.
     */
    public BigInteger queryLocalDatabaseMetric(String datasetKey, String logicalColumn, String institutionId) throws SQLException {
        if (!isDbConnected()) {
            return null;
        }
        ExposurePolicyService.Resolved target = policy.resolve(datasetKey, logicalColumn);
        String sql = "SELECT COALESCE(SUM(" + target.column() + "), 0) FROM " + target.table();
        log.info("[AgentDatabase - {}] {}.{} için yerel sorgu yürütülüyor", institutionId, datasetKey, logicalColumn);

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                BigInteger val = rs.getBigDecimal(1).toBigInteger();
                log.info("[AgentDatabase - {}] Yerel PostgreSQL sonucu alındı (DMZ içinde gizli tutuluyor)", institutionId);
                return val;
            }
            return BigInteger.ZERO;
        }
    }

    /** Yayına girecek mantıksal sütunlar: eşlenmiş ve kurumun tablosunda gerçekten sayısal olanlar (sıralı). */
    public List<String> listMetricColumns(String datasetKey) throws SQLException {
        if (!isDbConnected()) {
            return List.of();
        }
        ExposurePolicyService.DatasetMapping mapping = policy.requireDataset(datasetKey);
        List<String> numeric = numericColumns(mapping.table());
        return mapping.columns().entrySet().stream().filter(e -> numeric.contains(e.getValue())).map(Map.Entry::getKey).sorted().toList();
    }

    /** Yerel panel: public şemadaki tablo ve görünümler, sayısal sütunları ve satır sayılarıyla. Değer okunmaz. */
    public List<Map<String, Object>> describeSchema() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!isDbConnected()) {
            return out;
        }
        try (Connection conn = connect()) {
            List<String[]> tables = new ArrayList<>();
            try (ResultSet rs = conn.getMetaData().getTables(null, "public", "%", new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
                while (rs.next()) {
                    tables.add(new String[]{rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")});
                }
            }
            for (String[] t : tables) {
                String name = t[0];
                if (!name.matches("^[a-z0-9_]+$")) {
                    continue; // Tırnak gerektiren adlar paylaşılamaz
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("type", t[1]);
                item.put("numericColumns", numericColumns(conn, name));
                item.put("otherColumns", otherColumns(conn, name));
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + name)) {
                    item.put("rowCount", rs.next() ? rs.getLong(1) : 0);
                } catch (SQLException e) {
                    item.put("rowCount", null);
                    item.put("error", e.getMessage());
                }
                out.add(item);
            }
        }
        out.sort(Comparator.comparing(m -> (String) m.get("name")));
        return out;
    }

    /** Tablo veya view'daki sayısal veri sütunları (sıralı). Kimlik sütunları ölçüm sayılmaz. */
    public List<String> numericColumns(String table) throws SQLException {
        try (Connection conn = connect()) {
            return numericColumns(conn, table);
        }
    }

    private static List<String> numericColumns(Connection conn, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, "public", table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (isNumeric(rs.getInt("DATA_TYPE")) && name.matches("^[a-z0-9_]+$")
                        && !name.equals("id") && !name.endsWith("_id")) {
                    columns.add(name);
                }
            }
        }
        return columns;
    }

    private static List<String> otherColumns(Connection conn, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, "public", table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                if (!isNumeric(rs.getInt("DATA_TYPE")) || name.equals("id") || name.endsWith("_id")) {
                    columns.add(name);
                }
            }
        }
        return columns;
    }

    private static boolean isNumeric(int type) {
        return switch (type) {
            case Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.DOUBLE, Types.FLOAT -> true;
            default -> false;
        };
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
    }
}
