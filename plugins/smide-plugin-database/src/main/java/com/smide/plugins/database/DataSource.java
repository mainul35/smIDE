package com.smide.plugins.database;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * One saved connection, without its password.
 *
 * <p>The password is deliberately not part of this: it is asked for when the connection
 * is opened and held only for as long as the IDE is running. Writing it into the
 * settings file would put it in plain text on disk, which is not something an editor
 * should do quietly on the user's behalf.
 *
 * @param kind     which database, which decides the URL and the driver
 * @param name     what the user calls it in the tree
 * @param host     the server, or empty for a file database
 * @param port     the port
 * @param database the catalog, schema or file to open
 * @param user     the account to connect as
 * @param customUrl a JDBC URL typed by hand; when set it wins over everything above
 * @param driver   the driver class for a hand-typed URL, or empty for the kind's own
 * @param driverPath jars holding that driver, separated by the path separator; empty to
 *                   use the drivers bundled with the plugin
 */
public record DataSource(DatabaseKind kind, String name, String host, int port, String database,
                         String user, String customUrl, String driver, String driverPath) {

    public static DataSource blank() {
        return new DataSource(DatabaseKind.MYSQL, "New connection", "localhost",
                DatabaseKind.MYSQL.defaultPort(), "", "root", "", "", "");
    }

    /** The JDBC URL this connection opens. */
    public String url() {
        return customUrl == null || customUrl.isBlank() ? kind.url(host, port, database) : customUrl;
    }

    /** The driver class to load: the one typed in, else the kind's own. */
    public String driverClass() {
        return driver == null || driver.isBlank() ? kind.driver() : driver;
    }

    public String describe() {
        if (kind.isFile()) {
            return kind.label() + "  " + (database.isBlank() ? url() : database);
        }
        return kind.label() + "  " + (user.isBlank() ? "" : user + "@") + host + ":" + port
                + (database.isBlank() ? "" : "/" + database);
    }

    /**
     * A single line for the settings file. Each field is percent-encoded, so a name with
     * a separator in it cannot break the record.
     */
    String encode() {
        return String.join("|", kind.name(), escape(name), escape(host), String.valueOf(port),
                escape(database), escape(user), escape(customUrl), escape(driver), escape(driverPath));
    }

    static DataSource decode(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 6) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            port = 0;
        }
        return new DataSource(DatabaseKind.of(parts[0]), unescape(parts[1]), unescape(parts[2]), port,
                unescape(parts[4]), unescape(parts[5]),
                parts.length > 6 ? unescape(parts[6]) : "",
                parts.length > 7 ? unescape(parts[7]) : "",
                parts.length > 8 ? unescape(parts[8]) : "");
    }

    private static String escape(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return name;
    }
}
