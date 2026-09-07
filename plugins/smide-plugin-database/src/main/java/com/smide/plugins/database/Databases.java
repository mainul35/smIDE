package com.smide.plugins.database;

import com.smide.api.Ide;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The JDBC side of the database tool: the saved connections, the live ones, and the
 * queries run over them.
 *
 * <p>Every method that touches a database blocks, so callers go through
 * {@link DatabaseUi} to keep them off the JavaFX thread. Passwords live in this object
 * and nowhere else - not on disk, not in the settings file - so they last exactly as
 * long as the IDE does.
 */
public final class Databases {

    /** Where the connection list is kept; the same list in every workspace. */
    private static final String SETTING = "database.connections";
    /** A result set larger than this is cut off: a grid is for looking at, not for bulk. */
    static final int ROW_LIMIT = 500;

    private final Ide ide;
    private final List<DataSource> sources = new ArrayList<>();
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private final Map<String, Connection> open = new LinkedHashMap<>();

    public Databases(Ide ide) {
        this.ide = ide;
        for (String line : ide.settings().getList(SETTING)) {
            DataSource source = DataSource.decode(line);
            if (source != null) {
                sources.add(source);
            }
        }
    }

    // ------------------------------------------------------------- the list

    public List<DataSource> sources() {
        return List.copyOf(sources);
    }

    public void add(DataSource source) {
        sources.add(source);
        save();
    }

    /** Replaces one connection with an edited copy, keeping its place in the list. */
    public void replace(DataSource old, DataSource updated) {
        int index = sources.indexOf(old);
        if (index < 0) {
            return;
        }
        close(old);
        passwords.remove(old.name());
        sources.set(index, updated);
        save();
    }

    public void remove(DataSource source) {
        close(source);
        passwords.remove(source.name());
        sources.remove(source);
        save();
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        for (DataSource source : sources) {
            lines.add(source.encode());
        }
        ide.settings().setList(SETTING, lines);
    }

    // -------------------------------------------------------- the password

    /** Remembers a password for this run only. */
    public void setPassword(DataSource source, String password) {
        passwords.put(source.name(), password == null ? "" : password);
    }

    public boolean hasPassword(DataSource source) {
        return passwords.containsKey(source.name());
    }

    // ------------------------------------------------------ the connection

    public boolean isConnected(DataSource source) {
        Connection connection = open.get(source.name());
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Opens the connection, or returns the one already open. Blocks. */
    public Connection connect(DataSource source) throws SQLException {
        Connection existing = open.get(source.name());
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        String password = passwords.getOrDefault(source.name(), "");
        Connection connection;
        if (source.driverPath() != null && !source.driverPath().isBlank()) {
            // A driver the user attached: it has to be asked to connect directly.
            connection = Drivers.connect(source.driverPath(), source.driver(), source.url(),
                    source.user(), password);
        } else {
            /* A bundled driver lives in this plugin's own class loader, which
               DriverManager will not search by itself; loading the class registers it. */
            String driver = source.driverClass();
            if (!driver.isBlank()) {
                try {
                    Class.forName(driver);
                } catch (ClassNotFoundException e) {
                    throw new SQLException("No driver " + driver + " on the classpath. Bundled:"
                            + " MySQL, MariaDB, PostgreSQL, SQL Server, SQLite and H2. For anything"
                            + " else, attach the driver jar in the connection dialog.", e);
                }
            }
            connection = DriverManager.getConnection(source.url(), source.user(), password);
        }
        open.put(source.name(), connection);
        return connection;
    }

    public void close(DataSource source) {
        Connection connection = open.remove(source.name());
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing a broken connection is not worth reporting.
            }
        }
    }

    public void closeAll() {
        for (DataSource source : List.copyOf(sources)) {
            close(source);
        }
    }

    // ----------------------------------------------------------- the schema

    /**
     * What holds the tables, whatever this server calls it.
     *
     * <p>MySQL and SQL Server group tables into catalogs; PostgreSQL, H2 and the rest use
     * schemas. Both come from {@code DatabaseMetaData}, so one method covers every driver
     * and there is no SQL here that only one database understands.
     */
    public List<String> schemas(DataSource source) throws SQLException {
        List<String> names = new ArrayList<>();
        DatabaseMetaData meta = connect(source).getMetaData();
        if (source.kind().usesCatalogs()) {
            try (ResultSet rows = meta.getCatalogs()) {
                while (rows.next()) {
                    names.add(rows.getString("TABLE_CAT"));
                }
            }
        }
        if (names.isEmpty()) {
            try (ResultSet rows = meta.getSchemas()) {
                while (rows.next()) {
                    names.add(rows.getString("TABLE_SCHEM"));
                }
            }
        }
        if (names.isEmpty()) {
            // SQLite and friends have neither; the one database is the whole file.
            names.add("");
        }
        if (!source.database().isBlank() && names.remove(source.database())) {
            // The one named in the connection first: it is the one being worked on.
            names.add(0, source.database());
        }
        return names;
    }

    public List<Table> tables(DataSource source, String schema) throws SQLException {
        List<Table> out = new ArrayList<>();
        DatabaseMetaData meta = connect(source).getMetaData();
        String catalog = source.kind().usesCatalogs() ? schema : null;
        String pattern = source.kind().usesCatalogs() ? null : blankToNull(schema);
        try (ResultSet rows = meta.getTables(catalog, pattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (rows.next()) {
                out.add(new Table(schema, rows.getString("TABLE_NAME"),
                        "VIEW".equalsIgnoreCase(rows.getString("TABLE_TYPE"))));
            }
        }
        return out;
    }

    public List<Column> columns(DataSource source, Table table) throws SQLException {
        List<Column> out = new ArrayList<>();
        DatabaseMetaData meta = connect(source).getMetaData();
        String catalog = source.kind().usesCatalogs() ? table.schema() : null;
        String pattern = source.kind().usesCatalogs() ? null : blankToNull(table.schema());
        List<String> keys = new ArrayList<>();
        try (ResultSet rows = meta.getPrimaryKeys(catalog, pattern, table.name())) {
            while (rows.next()) {
                keys.add(rows.getString("COLUMN_NAME"));
            }
        }
        try (ResultSet rows = meta.getColumns(catalog, pattern, table.name(), "%")) {
            while (rows.next()) {
                String name = rows.getString("COLUMN_NAME");
                out.add(new Column(name, rows.getString("TYPE_NAME"),
                        rows.getInt("COLUMN_SIZE"),
                        "YES".equalsIgnoreCase(rows.getString("IS_NULLABLE")),
                        keys.contains(name)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ the query

    /**
     * Runs one statement.
     *
     * <p>A select comes back as rows; anything else comes back as the number of rows it
     * changed, which is what the console shows.
     */
    public Result execute(DataSource source, String sql) throws SQLException {
        try (Statement statement = connect(source).createStatement()) {
            statement.setMaxRows(ROW_LIMIT);
            long started = System.currentTimeMillis();
            boolean hasResultSet = statement.execute(sql);
            long millis = System.currentTimeMillis() - started;
            if (!hasResultSet) {
                return new Result(List.of(), List.of(), statement.getUpdateCount(), millis, false);
            }
            try (ResultSet rows = statement.getResultSet()) {
                ResultSetMetaData meta = rows.getMetaData();
                List<String> headers = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    headers.add(meta.getColumnLabel(i));
                }
                List<List<String>> data = new ArrayList<>();
                while (rows.next() && data.size() < ROW_LIMIT) {
                    List<String> row = new ArrayList<>(headers.size());
                    for (int i = 1; i <= headers.size(); i++) {
                        Object value = rows.getObject(i);
                        row.add(value == null ? null : String.valueOf(value));
                    }
                    data.add(row);
                }
                boolean truncated = data.size() >= ROW_LIMIT;
                return new Result(headers, data, -1, millis, truncated);
            }
        }
    }

    /** Opens a connection, asks the server its version, and closes it again. */
    public String test(DataSource source) throws SQLException {
        close(source);
        Connection connection = connect(source);
        DatabaseMetaData meta = connection.getMetaData();
        String banner = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        close(source);
        return banner;
    }

    /** A qualified name for a table, quoted the way this server quotes identifiers. */
    public String qualify(DataSource source, Table table) throws SQLException {
        String quote = connect(source).getMetaData().getIdentifierQuoteString();
        String q = quote == null || quote.isBlank() ? "" : quote;
        String name = q + table.name() + q;
        return table.schema().isBlank() ? name : q + table.schema() + q + "." + name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public Optional<DataSource> byName(String name) {
        return sources.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    // ------------------------------------------------------------- records

    /** A table or a view in one schema. */
    public record Table(String schema, String name, boolean view) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** One column, with what a reader wants to see beside its name. */
    public record Column(String name, String type, int size, boolean nullable, boolean primaryKey) {
        public String describe() {
            return type + (size > 0 && !type.toUpperCase(java.util.Locale.ROOT).contains("INT")
                    ? "(" + size + ")" : "")
                    + (primaryKey ? "  PK" : "") + (nullable ? "" : "  not null");
        }
    }

    /**
     * What a statement produced.
     *
     * @param updated  rows changed, or -1 when the statement returned rows instead
     * @param truncated true when the row limit cut the result short
     */
    public record Result(List<String> headers, List<List<String>> rows, int updated, long millis,
                         boolean truncated) {

        public String summary() {
            if (updated >= 0) {
                return updated + (updated == 1 ? " row" : " rows") + " changed in " + millis + " ms";
            }
            return rows.size() + (rows.size() == 1 ? " row" : " rows")
                    + (truncated ? " (first " + ROW_LIMIT + ")" : "") + " in " + millis + " ms";
        }
    }
}
