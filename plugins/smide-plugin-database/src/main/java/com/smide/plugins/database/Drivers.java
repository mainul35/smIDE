package com.smide.plugins.database;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher.ProgressReporter;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drivers that did not come with the plugin: a jar the user points at, or one fetched
 * from Maven Central by its coordinates.
 *
 * <p>Six databases have their drivers bundled, and the rest of the world does not.
 * Oracle, DB2, Snowflake, an internal fork of something - each is one jar, and this is
 * how that jar gets in: the same two ways a JDBC tool always offers, browse to it or
 * name it.
 *
 * <p>A driver loaded this way cannot go through {@code DriverManager}, which refuses
 * drivers it did not load itself, so it is instantiated and asked to connect directly.
 */
final class Drivers {

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";

    /** One loader per set of jars, so opening a connection twice does not read them twice. */
    private static final Map<String, URLClassLoader> LOADERS = new LinkedHashMap<>();

    private Drivers() {
    }

    /** Where a downloaded driver is kept: beside the other tools the IDE fetches. */
    static Path directory(Ide ide) {
        return ide.downloads().toolsDir().getParent().resolve("drivers");
    }

    /**
     * Downloads {@code group:artifact:version} from Maven Central and returns the jar.
     *
     * <p>Blocks; callers run it in the background.
     */
    static Path fetch(Ide ide, String coordinates, ProgressReporter progress) throws IOException {
        String[] parts = parse(coordinates);
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String jar = artifact + "-" + version + ".jar";
        Path target = directory(ide).resolve(jar);
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        ide.downloads().download(CENTRAL + group + "/" + artifact + "/" + version + "/" + jar,
                target, progress);
        return target;
    }

    /**
     * Reads coordinates in either of the two forms a driver's page offers.
     *
     * <p>{@code group:artifact:version} is what a build file's short form looks like;
     * the {@code <dependency>} block is what the Maven tab on the page gives you, and
     * pasting that straight in is one step fewer than picking it apart by hand.
     */
    static String[] parse(String text) throws IOException {
        String input = text == null ? "" : text.strip();
        if (input.contains("<groupId>")) {
            String group = between(input, "<groupId>", "</groupId>");
            String artifact = between(input, "<artifactId>", "</artifactId>");
            String version = between(input, "<version>", "</version>");
            if (group == null || artifact == null || version == null) {
                throw new IOException("That dependency has no groupId, artifactId and version."
                        + " Paste the whole <dependency> block, or type group:artifact:version.");
            }
            return new String[]{group, artifact, version};
        }
        String[] parts = input.split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IOException("Expected group:artifact:version, or a pasted <dependency> block."
                    + " For example com.oracle.database.jdbc:ojdbc11:23.6.0.24.10");
        }
        return new String[]{parts[0].strip(), parts[1].strip(), parts[2].strip()};
    }

    private static String between(String text, String open, String close) {
        int from = text.indexOf(open);
        if (from < 0) {
            return null;
        }
        int to = text.indexOf(close, from + open.length());
        if (to < 0) {
            return null;
        }
        String value = text.substring(from + open.length(), to).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Opens a connection using a driver from the given jars.
     *
     * @param driverClass the class to load, or empty to take the first driver the jars offer
     */
    static java.sql.Connection connect(String driverPath, String driverClass, String url,
                                       String user, String password) throws SQLException {
        URLClassLoader loader = loaderFor(driverPath);
        try {
            Driver driver = driverClass == null || driverClass.isBlank()
                    ? firstDriver(loader, driverPath)
                    : (Driver) Class.forName(driverClass, true, loader)
                            .getDeclaredConstructor().newInstance();
            java.util.Properties properties = new java.util.Properties();
            if (user != null && !user.isBlank()) {
                properties.put("user", user);
            }
            if (password != null) {
                properties.put("password", password);
            }
            java.sql.Connection connection = driver.connect(url, properties);
            if (connection == null) {
                throw new SQLException("The driver in " + driverPath + " does not handle " + url);
            }
            return connection;
        } catch (ClassNotFoundException e) {
            throw new SQLException("No class " + driverClass + " in " + driverPath, e);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Cannot create " + driverClass + ": " + e.getMessage(), e);
        }
    }

    /** The driver a jar declares in its service file, for when no class name was given. */
    private static Driver firstDriver(URLClassLoader loader, String driverPath) throws SQLException {
        for (Driver driver : java.util.ServiceLoader.load(Driver.class, loader)) {
            return driver;
        }
        throw new SQLException("No JDBC driver found in " + driverPath
                + ". Name the driver class if the jar does not declare one.");
    }

    private static synchronized URLClassLoader loaderFor(String driverPath) throws SQLException {
        URLClassLoader existing = LOADERS.get(driverPath);
        if (existing != null) {
            return existing;
        }
        List<URL> urls = new ArrayList<>();
        for (String entry : driverPath.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path jar = Path.of(entry.strip());
            if (!Files.isRegularFile(jar)) {
                throw new SQLException("No driver jar at " + jar);
            }
            try {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new SQLException("Cannot read " + jar, e);
            }
        }
        if (urls.isEmpty()) {
            throw new SQLException("No driver jars given.");
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), Drivers.class.getClassLoader());
        LOADERS.put(driverPath, loader);
        return loader;
    }
}
