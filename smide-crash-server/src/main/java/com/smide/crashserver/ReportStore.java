package com.smide.crashserver;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every report received, and what has been decided about each kind of failure.
 *
 * <p>Two tables. {@code reports} is one row per occurrence, exactly as it arrived.
 * {@code failures} is one row per signature - per distinct failure - holding what a person
 * has decided about it: whether it is open, being looked at, fixed or ignored, and a note.
 * A thousand reports of one bug are one row there, which is what makes the dashboard
 * readable; the thousand are still kept, because the one with the useful note is somewhere
 * among them.
 *
 * <p>H2 in a single file under the data folder. The server is one process and this is its
 * only connection, so every method is synchronized rather than pooled.
 */
final class ReportStore implements AutoCloseable {

    /** What a failure can be marked as. Open is where every new one starts. */
    static final Set<String> STATUSES = Set.of("open", "investigating", "fixed", "ignored");

    private final Connection db;

    ReportStore(Path dataDir) throws SQLException {
        // AUTO_SERVER off: nothing else should be writing to this file while the server runs.
        this.db = DriverManager.getConnection("jdbc:h2:file:" + dataDir.resolve("reports").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try (Statement s = db.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS reports (
                        id         VARCHAR(64) PRIMARY KEY,
                        received   TIMESTAMP WITH TIME ZONE NOT NULL,
                        kind       VARCHAR(32) NOT NULL,
                        happened   VARCHAR(64),
                        version    VARCHAR(64),
                        os         VARCHAR(300),
                        java       VARCHAR(300),
                        thread     VARCHAR(300),
                        exception  VARCHAR(500),
                        message    CHARACTER LARGE OBJECT,
                        stack      CHARACTER LARGE OBJECT,
                        signature  VARCHAR(64) NOT NULL,
                        location   VARCHAR(500),
                        note       CHARACTER LARGE OBJECT,
                        extra      CHARACTER LARGE OBJECT
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_reports_signature ON reports (signature, received)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_reports_received ON reports (received)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS failures (
                        signature VARCHAR(64) PRIMARY KEY,
                        status    VARCHAR(20) NOT NULL,
                        comment   CHARACTER LARGE OBJECT,
                        updated   TIMESTAMP WITH TIME ZONE NOT NULL
                    )""");
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Keeps a report. The first report of a failure opens it; a report of a failure that
     * was marked fixed opens it again, because the fix evidently was not one.
     *
     * @return whether a fixed failure was reopened by this report
     */
    synchronized boolean add(Map<String, String> report) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO reports (id, received, kind, happened, version, os, java, thread, exception,
                                     message, stack, signature, location, note, extra)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            insert.setString(1, report.get("id"));
            insert.setTimestamp(2, Timestamp.from(Instant.now()));
            insert.setString(3, report.getOrDefault("kind", "exception"));
            insert.setString(4, report.get("time"));
            insert.setString(5, report.get("version"));
            insert.setString(6, report.get("os"));
            insert.setString(7, report.get("java"));
            insert.setString(8, report.get("thread"));
            insert.setString(9, report.get("exception"));
            insert.setString(10, report.get("message"));
            insert.setString(11, report.get("stack"));
            insert.setString(12, report.get("signature"));
            insert.setString(13, report.get("where"));
            insert.setString(14, report.get("note"));
            insert.setString(15, report.get("extra"));
            insert.executeUpdate();
        }
        String status = statusOf(report.get("signature")).orElse(null);
        if (status == null) {
            setStatus(report.get("signature"), "open", null);
            return false;
        }
        if ("fixed".equals(status)) {
            setStatus(report.get("signature"), "open", "Reopened: reported again after being marked fixed.");
            return true;
        }
        return false;
    }

    synchronized void setStatus(String signature, String status, String comment) throws SQLException {
        String existingComment = null;
        try (PreparedStatement read = db.prepareStatement("SELECT comment FROM failures WHERE signature = ?")) {
            read.setString(1, signature);
            try (ResultSet rs = read.executeQuery()) {
                if (rs.next()) {
                    existingComment = rs.getString(1);
                }
            }
        }
        try (PreparedStatement merge = db.prepareStatement(
                "MERGE INTO failures (signature, status, comment, updated) KEY (signature) VALUES (?, ?, ?, ?)")) {
            merge.setString(1, signature);
            merge.setString(2, status);
            merge.setString(3, comment == null ? existingComment : comment);
            merge.setTimestamp(4, Timestamp.from(Instant.now()));
            merge.executeUpdate();
        }
    }

    synchronized boolean exists(String id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT 1 FROM reports WHERE id = ?")) {
            s.setString(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        }
    }

    synchronized boolean knows(String signature) throws SQLException {
        return statusOf(signature).isPresent();
    }

    private Optional<String> statusOf(String signature) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT status FROM failures WHERE signature = ?")) {
            s.setString(1, signature);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * One row per failure, newest first: how often, since when, on which versions and
     * systems, and what it looks like - taken from the most recent report of it.
     *
     * @param status a status, or null for every status
     * @param search words to find in the exception, message, location or notes; null for all
     */
    synchronized List<Map<String, Object>> failures(String status, String search) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT r.signature,
                       f.status, f.comment, f.updated,
                       COUNT(*) AS occurrences,
                       COUNT(DISTINCT r.os) AS systems,
                       MIN(r.received) AS first_seen,
                       MAX(r.received) AS last_seen,
                       LISTAGG(DISTINCT r.version, ', ') WITHIN GROUP (ORDER BY r.version) AS versions,
                       SUM(CASE WHEN r.note IS NOT NULL AND r.note <> '' THEN 1 ELSE 0 END) AS notes
                FROM reports r JOIN failures f ON f.signature = r.signature
                WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND f.status = ?");
            args.add(status);
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND r.signature IN (SELECT signature FROM reports WHERE LOWER(exception) LIKE ?"
                    + " OR LOWER(CAST(message AS VARCHAR)) LIKE ? OR LOWER(location) LIKE ?"
                    + " OR LOWER(CAST(note AS VARCHAR)) LIKE ? OR signature = ?)");
            String like = "%" + search.toLowerCase(Locale.ROOT).strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(search.strip());
        }
        sql.append(" GROUP BY r.signature, f.status, f.comment, f.updated ORDER BY last_seen DESC LIMIT 500");

        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                s.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("signature", rs.getString("signature"));
                    row.put("status", rs.getString("status"));
                    row.put("comment", rs.getString("comment"));
                    row.put("updated", instant(rs.getTimestamp("updated")));
                    row.put("occurrences", rs.getLong("occurrences"));
                    row.put("systems", rs.getLong("systems"));
                    row.put("firstSeen", instant(rs.getTimestamp("first_seen")));
                    row.put("lastSeen", instant(rs.getTimestamp("last_seen")));
                    row.put("versions", rs.getString("versions"));
                    row.put("notes", rs.getLong("notes"));
                    rows.add(row);
                }
            }
        }
        // What it looks like, from its latest report: one query, not one per failure.
        Map<String, Map<String, Object>> latest = latestOf(rows.stream().map(r -> (String) r.get("signature")).toList());
        for (Map<String, Object> row : rows) {
            Map<String, Object> last = latest.getOrDefault((String) row.get("signature"), Map.of());
            row.put("kind", last.get("kind"));
            row.put("exception", last.get("exception"));
            row.put("message", last.get("message"));
            row.put("where", last.get("where"));
        }
        return rows;
    }

    /** The most recent report of each of these failures. */
    private Map<String, Map<String, Object>> latestOf(List<String> signatures) throws SQLException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (signatures.isEmpty()) {
            return out;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(signatures.size(), "?"));
        try (PreparedStatement s = db.prepareStatement("""
                SELECT signature, kind, exception, message, location FROM (
                    SELECT signature, kind, exception, message, location,
                           ROW_NUMBER() OVER (PARTITION BY signature ORDER BY received DESC) AS n
                    FROM reports WHERE signature IN (""" + marks + """
                )) WHERE n = 1""")) {
            for (int i = 0; i < signatures.size(); i++) {
                s.setString(i + 1, signatures.get(i));
            }
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("kind", rs.getString("kind"));
                    row.put("exception", rs.getString("exception"));
                    row.put("message", rs.getString("message"));
                    row.put("where", rs.getString("location"));
                    out.put(rs.getString("signature"), row);
                }
            }
        }
        return out;
    }

    /** The reports of one failure, newest first, without their stack traces - those are fetched one at a time. */
    synchronized List<Map<String, Object>> reportsOf(String signature) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement("""
                SELECT id, received, kind, happened, version, os, java, thread, note
                FROM reports WHERE signature = ? ORDER BY received DESC LIMIT 200""")) {
            s.setString(1, signature);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("received", instant(rs.getTimestamp("received")));
                    row.put("kind", rs.getString("kind"));
                    row.put("time", rs.getString("happened"));
                    row.put("version", rs.getString("version"));
                    row.put("os", rs.getString("os"));
                    row.put("java", rs.getString("java"));
                    row.put("thread", rs.getString("thread"));
                    row.put("note", rs.getString("note"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    synchronized Optional<Map<String, Object>> report(String id) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT * FROM reports WHERE id = ?")) {
            s.setString(1, id);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getString("id"));
                row.put("received", instant(rs.getTimestamp("received")));
                row.put("kind", rs.getString("kind"));
                row.put("time", rs.getString("happened"));
                row.put("version", rs.getString("version"));
                row.put("os", rs.getString("os"));
                row.put("java", rs.getString("java"));
                row.put("thread", rs.getString("thread"));
                row.put("exception", rs.getString("exception"));
                row.put("message", rs.getString("message"));
                row.put("stack", rs.getString("stack"));
                row.put("signature", rs.getString("signature"));
                row.put("where", rs.getString("location"));
                row.put("note", rs.getString("note"));
                row.put("extra", rs.getString("extra"));
                return Optional.of(row);
            }
        }
    }

    /** The numbers across the top of the dashboard. */
    synchronized Map<String, Object> summary() throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Statement s = db.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM reports")) {
                rs.next();
                out.put("reports", rs.getLong(1));
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) FROM reports WHERE received > DATEADD('HOUR', -24, CURRENT_TIMESTAMP)")) {
                rs.next();
                out.put("lastDay", rs.getLong(1));
            }
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (String status : List.of("open", "investigating", "fixed", "ignored")) {
                byStatus.put(status, 0L);
            }
            try (ResultSet rs = s.executeQuery("SELECT status, COUNT(*) FROM failures GROUP BY status")) {
                while (rs.next()) {
                    byStatus.put(rs.getString(1), rs.getLong(2));
                }
            }
            out.put("failures", byStatus);
        }
        return out;
    }

    private static String instant(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }
}
