package com.smide.plugins.database;

/**
 * A database the IDE can talk to: how to build its URL, which driver to load, and what
 * it calls the thing that holds tables.
 *
 * <p>JDBC is the same everywhere; the differences are the URL shape and whether a server
 * groups tables into catalogs (MySQL, SQL Server) or schemas (PostgreSQL, H2, Oracle).
 * Everything else - listing tables, reading columns, running a statement - goes through
 * {@code DatabaseMetaData}, which every driver implements.
 *
 * <p>{@link #OTHER} is the escape hatch: type the URL yourself and the driver is
 * whatever the class name says, which covers a database nobody thought to list here.
 */
public enum DatabaseKind {

    MYSQL("MySQL", 3306, "com.mysql.cj.jdbc.Driver", true) {
        @Override
        public String url(String host, int port, String database) {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?connectTimeout=5000&socketTimeout=60000&useSSL=false"
                    + "&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        }
    },
    MARIADB("MariaDB", 3306, "org.mariadb.jdbc.Driver", true) {
        @Override
        public String url(String host, int port, String database) {
            return "jdbc:mariadb://" + host + ":" + port + "/" + database + "?connectTimeout=5000";
        }
    },
    POSTGRESQL("PostgreSQL", 5432, "org.postgresql.Driver", false) {
        @Override
        public String url(String host, int port, String database) {
            return "jdbc:postgresql://" + host + ":" + port + "/"
                    + (database.isBlank() ? "postgres" : database) + "?connectTimeout=5";
        }
    },
    SQLSERVER("SQL Server", 1433, "com.microsoft.sqlserver.jdbc.SQLServerDriver", true) {
        @Override
        public String url(String host, int port, String database) {
            return "jdbc:sqlserver://" + host + ":" + port
                    + (database.isBlank() ? "" : ";databaseName=" + database)
                    + ";encrypt=true;trustServerCertificate=true;loginTimeout=5";
        }
    },
    H2("H2", 0, "org.h2.Driver", false) {
        @Override
        public String url(String host, int port, String database) {
            // A file, not a server: the database field is the path.
            return "jdbc:h2:" + (database.isBlank() ? "mem:test" : database);
        }

        @Override
        public boolean isFile() {
            return true;
        }
    },
    SQLITE("SQLite", 0, "org.sqlite.JDBC", false) {
        @Override
        public String url(String host, int port, String database) {
            return "jdbc:sqlite:" + database;
        }

        @Override
        public boolean isFile() {
            return true;
        }
    },
    OTHER("Other (JDBC URL)", 0, "", false) {
        @Override
        public String url(String host, int port, String database) {
            return "jdbc:";
        }
    };

    private final String label;
    private final int defaultPort;
    private final String driver;
    /** True when this server groups tables into catalogs rather than schemas. */
    private final boolean catalogs;

    DatabaseKind(String label, int defaultPort, String driver, boolean catalogs) {
        this.label = label;
        this.defaultPort = defaultPort;
        this.driver = driver;
        this.catalogs = catalogs;
    }

    public abstract String url(String host, int port, String database);

    public String label() {
        return label;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String driver() {
        return driver;
    }

    public boolean usesCatalogs() {
        return catalogs;
    }

    /** True for a database that lives in a file rather than on a host and port. */
    public boolean isFile() {
        return false;
    }

    public static DatabaseKind of(String name) {
        for (DatabaseKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return MYSQL;
    }

    @Override
    public String toString() {
        return label;
    }
}
