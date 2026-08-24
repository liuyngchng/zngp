package com.rd.zngp.store;

import com.rd.zngp.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed data store, mirroring server/internal/store/store.go and methods.go.
 * Uses raw JDBC for simplicity and JDK 1.8 compatibility.
 */
public class Store {

    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final String dbPath;
    private Connection conn;

    public Store(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Initialize the database: create directories, open connection, migrate tables.
     */
    public void init() throws SQLException {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }

        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
        }

        migrate();
        log.info("数据库初始化完成: {}", dbPath);
    }

    private void migrate() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "username TEXT UNIQUE NOT NULL," +
                "password_hash TEXT NOT NULL," +
                "role TEXT DEFAULT 'admin'," +
                "created_at TEXT DEFAULT (datetime('now'))" +
                ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS records (" +
                "id TEXT PRIMARY KEY," +
                "title TEXT," +
                "description TEXT," +
                "inspector_name TEXT," +
                "customer_name TEXT," +
                "customer_address TEXT," +
                "inspection_date TEXT," +
                "source_type TEXT DEFAULT 'RECORDING'," +
                "audio_file_path TEXT," +
                "audio_duration REAL DEFAULT 0," +
                "transcript_text TEXT," +
                "transcript_status TEXT DEFAULT 'PENDING'," +
                "inspection_status TEXT DEFAULT 'NONE'," +
                "created_at TEXT DEFAULT (datetime('now'))," +
                "updated_at TEXT DEFAULT (datetime('now'))" +
                ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS inspection_templates (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "description TEXT," +
                "category TEXT," +
                "is_active INTEGER DEFAULT 1," +
                "created_at TEXT DEFAULT (datetime('now'))," +
                "updated_at TEXT DEFAULT (datetime('now'))" +
                ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS inspection_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "template_id INTEGER NOT NULL," +
                "item_number INTEGER," +
                "name TEXT NOT NULL," +
                "description TEXT," +
                "category TEXT," +
                "is_required INTEGER DEFAULT 1," +
                "weight INTEGER DEFAULT 1," +
                "created_at TEXT DEFAULT (datetime('now'))" +
                ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS inspection_results (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "record_id TEXT NOT NULL," +
                "template_id INTEGER," +
                "overall_conclusion TEXT," +
                "overall_score INTEGER DEFAULT 0," +
                "summary TEXT," +
                "raw_llm_response TEXT," +
                "model_used TEXT," +
                "tokens_used INTEGER DEFAULT 0," +
                "created_at TEXT DEFAULT (datetime('now'))" +
                ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS item_results (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "inspection_result_id INTEGER NOT NULL," +
                "item_id INTEGER," +
                "item_name TEXT," +
                "verdict TEXT," +
                "evidence TEXT," +
                "confidence REAL DEFAULT 0," +
                "ai_reasoning TEXT" +
                ")");
        }
        log.info("数据库迁移完成");
    }

    // ---- Helpers ----

    private String now() {
        return LocalDateTime.now().format(DTF);
    }

    private LocalDateTime parseDT(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s, DTF);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- User methods ----

    public User findUserByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, password_hash, role, created_at FROM users WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapUser(rs);
                }
            }
        }
        return null;
    }

    public long countUsers() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            return rs.getLong(1);
        }
    }

    public void createUser(User user) throws SQLException {
        String sql = "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setString(2, user.passwordHash);
            ps.setString(3, user.role);
            ps.setString(4, now());
            ps.executeUpdate();
        }
    }

    public void updateUserPassword(long userId, String hash) throws SQLException {
        String sql = "UPDATE users SET password_hash = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    private User mapUser(ResultSet rs) throws SQLException {
        User u = new User();
        u.id = rs.getLong("id");
        u.username = rs.getString("username");
        u.passwordHash = rs.getString("password_hash");
        u.role = rs.getString("role");
        u.createdAt = parseDT(rs.getString("created_at"));
        return u;
    }

    // ---- Record methods ----

    public void createRecord(Record record) throws SQLException {
        String sql = "INSERT INTO records (id, title, description, inspector_name, customer_name, " +
            "customer_address, inspection_date, source_type, audio_file_path, audio_duration, " +
            "transcript_text, transcript_status, inspection_status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.id);
            ps.setString(2, record.title);
            ps.setString(3, record.description);
            ps.setString(4, record.inspectorName);
            ps.setString(5, record.customerName);
            ps.setString(6, record.customerAddress);
            ps.setString(7, record.inspectionDate != null ? record.inspectionDate.format(DTF) : now());
            ps.setString(8, record.sourceType);
            ps.setString(9, record.audioFilePath);
            ps.setDouble(10, record.audioDuration);
            ps.setString(11, record.transcriptText);
            ps.setString(12, record.transcriptStatus);
            ps.setString(13, record.inspectionStatus);
            ps.setString(14, now());
            ps.setString(15, now());
            ps.executeUpdate();
        }
    }

    public Record getRecord(String id) throws SQLException {
        String sql = "SELECT * FROM records WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRecord(rs);
                }
            }
        }
        return null;
    }

    public ListResult<Record> listRecords(int page, int pageSize, String keyword) throws SQLException {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.isEmpty()) {
            where.append(" WHERE (title LIKE ? OR customer_name LIKE ? OR inspector_name LIKE ? OR transcript_text LIKE ?)");
            String like = "%" + keyword + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }

        String countSql = "SELECT COUNT(*) FROM records" + where;
        long total = 0;
        try (PreparedStatement ps = conn.prepareStatement(countSql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                total = rs.getLong(1);
            }
        }

        int offset = (page - 1) * pageSize;
        String dataSql = "SELECT * FROM records" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        params.add(pageSize);
        params.add(offset);

        List<Record> records = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRecord(rs));
                }
            }
        }

        return new ListResult<>(records, total);
    }

    public void updateRecordTranscript(String recordId, String transcript, String status) throws SQLException {
        String sql = "UPDATE records SET transcript_text = ?, transcript_status = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transcript);
            ps.setString(2, status);
            ps.setString(3, now());
            ps.setString(4, recordId);
            ps.executeUpdate();
        }
    }

    public void updateRecordInspectionStatus(String recordId, String status) throws SQLException {
        String sql = "UPDATE records SET inspection_status = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, now());
            ps.setString(3, recordId);
            ps.executeUpdate();
        }
    }

    public void deleteRecord(String id) throws SQLException {
        String sql = "DELETE FROM records WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private Record mapRecord(ResultSet rs) throws SQLException {
        Record r = new Record();
        r.id = rs.getString("id");
        r.title = rs.getString("title");
        r.description = rs.getString("description");
        r.inspectorName = rs.getString("inspector_name");
        r.customerName = rs.getString("customer_name");
        r.customerAddress = rs.getString("customer_address");
        r.inspectionDate = parseDT(rs.getString("inspection_date"));
        r.sourceType = rs.getString("source_type");
        r.audioFilePath = rs.getString("audio_file_path");
        r.audioDuration = rs.getDouble("audio_duration");
        r.transcriptText = rs.getString("transcript_text");
        r.transcriptStatus = rs.getString("transcript_status");
        r.inspectionStatus = rs.getString("inspection_status");
        r.createdAt = parseDT(rs.getString("created_at"));
        r.updatedAt = parseDT(rs.getString("updated_at"));
        return r;
    }

    // ---- Template methods ----

    public void createTemplate(InspectionTemplate t) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String sql = "INSERT INTO inspection_templates (name, description, category, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, t.name);
                ps.setString(2, t.description);
                ps.setString(3, t.category);
                ps.setInt(4, t.isActive ? 1 : 0);
                ps.setString(5, now());
                ps.setString(6, now());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        t.id = keys.getLong(1);
                    }
                }
            }

            // Insert items
            if (t.items != null && !t.items.isEmpty()) {
                String itemSql = "INSERT INTO inspection_items (template_id, item_number, name, description, category, is_required, weight, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                for (InspectionItem item : t.items) {
                    try (PreparedStatement ps = conn.prepareStatement(itemSql)) {
                        ps.setLong(1, t.id);
                        ps.setInt(2, item.itemNumber);
                        ps.setString(3, item.name);
                        ps.setString(4, item.description);
                        ps.setString(5, item.category);
                        ps.setInt(6, item.isRequired ? 1 : 0);
                        ps.setInt(7, item.weight);
                        ps.setString(8, now());
                        ps.executeUpdate();
                    }
                }
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public InspectionTemplate getTemplate(long id) throws SQLException {
        String sql = "SELECT * FROM inspection_templates WHERE id = ?";
        InspectionTemplate t = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    t = mapTemplate(rs);
                }
            }
        }
        if (t != null) {
            t.items = listItemsByTemplate(id);
        }
        return t;
    }

    public List<InspectionTemplate> listTemplates() throws SQLException {
        List<InspectionTemplate> templates = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM inspection_templates ORDER BY id ASC")) {
            while (rs.next()) {
                InspectionTemplate t = mapTemplate(rs);
                t.items = listItemsByTemplate(t.id);
                templates.add(t);
            }
        }
        return templates;
    }

    public void updateTemplate(InspectionTemplate t) throws SQLException {
        String sql = "UPDATE inspection_templates SET name = ?, description = ?, is_active = ?, updated_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.name);
            ps.setString(2, t.description);
            ps.setInt(3, t.isActive ? 1 : 0);
            ps.setString(4, now());
            ps.setLong(5, t.id);
            ps.executeUpdate();
        }
    }

    public void deleteTemplate(long id) throws SQLException {
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM inspection_items WHERE template_id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM inspection_templates WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private List<InspectionItem> listItemsByTemplate(long templateId) throws SQLException {
        List<InspectionItem> items = new ArrayList<>();
        String sql = "SELECT * FROM inspection_items WHERE template_id = ? ORDER BY item_number ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapItem(rs));
                }
            }
        }
        return items;
    }

    public void createItem(InspectionItem item) throws SQLException {
        String sql = "INSERT INTO inspection_items (template_id, item_number, name, description, category, is_required, weight, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, item.templateId);
            ps.setInt(2, item.itemNumber);
            ps.setString(3, item.name);
            ps.setString(4, item.description);
            ps.setString(5, item.category);
            ps.setInt(6, item.isRequired ? 1 : 0);
            ps.setInt(7, item.weight);
            ps.setString(8, now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    item.id = keys.getLong(1);
                }
            }
        }
    }

    public void updateItem(InspectionItem item) throws SQLException {
        String sql = "UPDATE inspection_items SET name = ?, description = ?, category = ?, is_required = ?, weight = ?, item_number = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.name);
            ps.setString(2, item.description);
            ps.setString(3, item.category);
            ps.setInt(4, item.isRequired ? 1 : 0);
            ps.setInt(5, item.weight);
            ps.setInt(6, item.itemNumber);
            ps.setLong(7, item.id);
            ps.executeUpdate();
        }
    }

    public void deleteItem(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM inspection_items WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public InspectionItem getItem(long id) throws SQLException {
        String sql = "SELECT * FROM inspection_items WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapItem(rs);
                }
            }
        }
        return null;
    }

    private InspectionTemplate mapTemplate(ResultSet rs) throws SQLException {
        InspectionTemplate t = new InspectionTemplate();
        t.id = rs.getLong("id");
        t.name = rs.getString("name");
        t.description = rs.getString("description");
        t.category = rs.getString("category");
        t.isActive = rs.getInt("is_active") != 0;
        t.createdAt = parseDT(rs.getString("created_at"));
        t.updatedAt = parseDT(rs.getString("updated_at"));
        return t;
    }

    private InspectionItem mapItem(ResultSet rs) throws SQLException {
        InspectionItem item = new InspectionItem();
        item.id = rs.getLong("id");
        item.templateId = rs.getLong("template_id");
        item.itemNumber = rs.getInt("item_number");
        item.name = rs.getString("name");
        item.description = rs.getString("description");
        item.category = rs.getString("category");
        item.isRequired = rs.getInt("is_required") != 0;
        item.weight = rs.getInt("weight");
        item.createdAt = parseDT(rs.getString("created_at"));
        return item;
    }

    // ---- Inspection methods ----

    public void createInspectionResult(InspectionResult r) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String sql = "INSERT INTO inspection_results (record_id, template_id, overall_conclusion, overall_score, summary, raw_llm_response, model_used, tokens_used, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, r.recordId);
                ps.setLong(2, r.templateId);
                ps.setString(3, r.overallConclusion);
                ps.setInt(4, r.overallScore);
                ps.setString(5, r.summary);
                ps.setString(6, r.rawLlmResponse);
                ps.setString(7, r.modelUsed);
                ps.setInt(8, r.tokensUsed);
                ps.setString(9, now());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        r.id = keys.getLong(1);
                    }
                }
            }

            // Insert item results
            if (r.items != null && !r.items.isEmpty()) {
                String itemSql = "INSERT INTO item_results (inspection_result_id, item_id, item_name, verdict, evidence, confidence, ai_reasoning) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";
                for (ItemResult ir : r.items) {
                    try (PreparedStatement ps = conn.prepareStatement(itemSql)) {
                        ps.setLong(1, r.id);
                        ps.setLong(2, ir.itemId);
                        ps.setString(3, ir.itemName);
                        ps.setString(4, ir.verdict);
                        ps.setString(5, ir.evidence);
                        ps.setDouble(6, ir.confidence);
                        ps.setString(7, ir.aiReasoning);
                        ps.executeUpdate();
                    }
                }
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public InspectionResult getInspectionResult(long id) throws SQLException {
        String sql = "SELECT * FROM inspection_results WHERE id = ?";
        InspectionResult r = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    r = mapInspectionResult(rs);
                }
            }
        }
        if (r != null) {
            r.items = listItemResultsByInspection(r.id);
        }
        return r;
    }

    public InspectionResult getInspectionByRecordId(String recordId) throws SQLException {
        String sql = "SELECT * FROM inspection_results WHERE record_id = ? ORDER BY created_at DESC LIMIT 1";
        InspectionResult r = null;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, recordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    r = mapInspectionResult(rs);
                }
            }
        }
        if (r != null) {
            r.items = listItemResultsByInspection(r.id);
        }
        return r;
    }

    private List<ItemResult> listItemResultsByInspection(long inspectionResultId) throws SQLException {
        List<ItemResult> items = new ArrayList<>();
        String sql = "SELECT * FROM item_results WHERE inspection_result_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, inspectionResultId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(mapItemResult(rs));
                }
            }
        }
        return items;
    }

    private InspectionResult mapInspectionResult(ResultSet rs) throws SQLException {
        InspectionResult r = new InspectionResult();
        r.id = rs.getLong("id");
        r.recordId = rs.getString("record_id");
        r.templateId = rs.getLong("template_id");
        r.overallConclusion = rs.getString("overall_conclusion");
        r.overallScore = rs.getInt("overall_score");
        r.summary = rs.getString("summary");
        r.rawLlmResponse = rs.getString("raw_llm_response");
        r.modelUsed = rs.getString("model_used");
        r.tokensUsed = rs.getInt("tokens_used");
        r.createdAt = parseDT(rs.getString("created_at"));
        return r;
    }

    private ItemResult mapItemResult(ResultSet rs) throws SQLException {
        ItemResult ir = new ItemResult();
        ir.id = rs.getLong("id");
        ir.inspectionResultId = rs.getLong("inspection_result_id");
        ir.itemId = rs.getLong("item_id");
        ir.itemName = rs.getString("item_name");
        ir.verdict = rs.getString("verdict");
        ir.evidence = rs.getString("evidence");
        ir.confidence = rs.getDouble("confidence");
        ir.aiReasoning = rs.getString("ai_reasoning");
        return ir;
    }

    // ---- Stats ----

    public OverviewResult getOverview() throws SQLException {
        OverviewResult result = new OverviewResult();
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM records")) {
                result.totalRecords = rs.getLong(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM inspection_results WHERE overall_conclusion = '规范'")) {
                result.compliantCount = rs.getLong(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM inspection_results WHERE overall_conclusion = '不规范'")) {
                result.nonCompliantCount = rs.getLong(1);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM inspection_results WHERE overall_conclusion = '需复核'")) {
                result.reviewCount = rs.getLong(1);
            }
        }
        return result;
    }

    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.error("关闭数据库连接失败", e);
        }
    }

    // ---- Helper classes ----

    public static class ListResult<T> {
        public final List<T> items;
        public final long total;

        public ListResult(List<T> items, long total) {
            this.items = items;
            this.total = total;
        }
    }

    public static class OverviewResult {
        public long totalRecords;
        public long compliantCount;
        public long nonCompliantCount;
        public long reviewCount;
    }
}