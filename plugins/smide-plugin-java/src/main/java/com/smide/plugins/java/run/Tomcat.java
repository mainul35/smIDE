package com.smide.plugins.java.run;

import com.smide.api.Ide;
import com.smide.plugins.java.JavaTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finding a Tomcat, giving a run configuration its own copy of one, and putting a web
 * application into it.
 *
 * <p>The installation itself is never written to. A configuration gets a private
 * {@code CATALINA_BASE} - its own conf, logs, temp, work and webapps - and Tomcat is
 * started with {@code catalina.home} pointing at the installation and {@code catalina.base}
 * at that copy. Two configurations can then run different applications on different ports
 * from one install, and nothing the IDE does can leave a shared Tomcat in a state the
 * user did not ask for.
 *
 * <p>TomEE has the same layout and works the same way. A full Jakarta EE server with its
 * own deployment model - WildFly, GlassFish, Payara - does not, and is not handled here.
 */
public final class Tomcat {

    public static final String HOME_SETTING = "java.tomcatHome";

    /** Files copied into a fresh base; the rest of conf is Tomcat's own defaults. */
    private static final List<String> CONF_FILES = List.of(
            "server.xml", "web.xml", "context.xml", "tomcat-users.xml", "catalina.policy",
            "catalina.properties", "logging.properties", "jaspic-providers.xml");

    private Tomcat() {
    }

    // ------------------------------------------------------------ the install

    /** A Tomcat: the setting, then {@code CATALINA_HOME}, then the usual places. */
    public static Optional<Path> home(Ide ide) {
        String configured = ide.settings().get(HOME_SETTING, "");
        if (!configured.isBlank() && isTomcat(Path.of(configured))) {
            return Optional.of(Path.of(configured));
        }
        for (String variable : List.of("CATALINA_HOME", "TOMCAT_HOME")) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank() && isTomcat(Path.of(value))) {
                return Optional.of(Path.of(value));
            }
        }
        return discover();
    }

    /** True when the directory has the two files every Tomcat has. */
    public static boolean isTomcat(Path dir) {
        return Files.isRegularFile(dir.resolve("bin").resolve("bootstrap.jar"))
                && Files.isRegularFile(dir.resolve("conf").resolve("server.xml"));
    }

    /** Looks one level inside the directories people unpack Tomcat into. */
    private static Optional<Path> discover() {
        List<Path> roots = new ArrayList<>();
        if (JavaTools.isWindows()) {
            for (String drive : List.of("C:", "D:")) {
                roots.add(Path.of(drive, "Program Files", "Apache Software Foundation"));
                roots.add(Path.of(drive, "Program Files (x86)", "Apache Software Foundation"));
                roots.add(Path.of(drive, "opt"));
                roots.add(Path.of(drive, "tools"));
                roots.add(Path.of(drive + java.io.File.separator));
            }
        } else {
            roots.add(Path.of("/opt"));
            roots.add(Path.of("/usr/local"));
            roots.add(Path.of("/usr/share"));
        }
        roots.add(Path.of(System.getProperty("user.home")));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            if (isTomcat(root)) {
                return Optional.of(root);
            }
            try (Stream<Path> children = Files.list(root)) {
                Optional<Path> hit = children.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains("tomcat"))
                        .filter(Tomcat::isTomcat)
                        // The highest version, so a machine with several gets the newest.
                        .max(Comparator.comparing(p -> p.getFileName().toString()));
                if (hit.isPresent()) {
                    return hit;
                }
            } catch (IOException | RuntimeException ignored) {
                // Not readable; try the next place.
            }
        }
        return Optional.empty();
    }

    /** The version, read from the jar Tomcat keeps it in; empty when it cannot be told. */
    public static String version(Path home) {
        Path release = home.resolve("RELEASE-NOTES");
        try {
            if (Files.isRegularFile(release)) {
                for (String line : Files.readAllLines(release, StandardCharsets.ISO_8859_1).subList(0, 40)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("Apache Tomcat Version")) {
                        return trimmed.substring("Apache Tomcat Version".length()).strip();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // The version is a nicety, not a requirement.
        }
        return "";
    }

    // -------------------------------------------------------------- the base

    /**
     * Creates or refreshes a private CATALINA_BASE and points its connector at the ports
     * this configuration wants.
     */
    public static void prepareBase(Path base, Path home, int httpPort, int shutdownPort) throws IOException {
        for (String dir : List.of("conf", "logs", "temp", "work", "webapps")) {
            Files.createDirectories(base.resolve(dir));
        }
        Path homeConf = home.resolve("conf");
        for (String file : CONF_FILES) {
            Path source = homeConf.resolve(file);
            Path target = base.resolve("conf").resolve(file);
            // Everything but server.xml is copied once and then left alone, so edits a
            // user makes in the base survive; server.xml is rewritten because the ports
            // in it are ours to set.
            if (Files.isRegularFile(source) && (!Files.exists(target) || file.equals("server.xml"))) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path serverXml = base.resolve("conf").resolve("server.xml");
        if (Files.isRegularFile(serverXml)) {
            String xml = Files.readString(serverXml, StandardCharsets.UTF_8);
            xml = withPort(xml, "<Server", shutdownPort);
            xml = withPort(xml, "<Connector", httpPort);
            Files.writeString(serverXml, xml, StandardCharsets.UTF_8);
        }
    }

    /**
     * Replaces the {@code port} attribute of the first element with this tag.
     *
     * <p>Done by hand rather than with a parser: server.xml is full of comments that a
     * round trip through a DOM would reformat, and the file belongs to the user even
     * when the copy is ours.
     */
    private static String withPort(String xml, String tag, int port) {
        int element = xml.indexOf(tag);
        if (element < 0) {
            return xml;
        }
        int end = xml.indexOf('>', element);
        int attribute = xml.indexOf("port=\"", element);
        if (attribute < 0 || (end > 0 && attribute > end)) {
            return xml;
        }
        int from = attribute + "port=\"".length();
        int to = xml.indexOf('"', from);
        if (to < 0) {
            return xml;
        }
        return xml.substring(0, from) + port + xml.substring(to);
    }

    // ------------------------------------------------------------ deployment

    /** The built web application: a war, or an exploded directory when there is no war yet. */
    public static Optional<Path> artifact(Path moduleDir) {
        for (String dir : List.of("target", "build/libs", "build/lib")) {
            Path candidate = newestWar(moduleDir.resolve(dir));
            if (candidate != null) {
                return Optional.of(candidate);
            }
        }
        Path target = moduleDir.resolve("target");
        if (Files.isDirectory(target)) {
            try (Stream<Path> children = Files.list(target)) {
                return children.filter(p -> Files.isRegularFile(p.resolve("WEB-INF").resolve("web.xml"))
                                || Files.isDirectory(p.resolve("WEB-INF")))
                        .findFirst();
            } catch (IOException | RuntimeException ignored) {
                // Nothing readable there.
            }
        }
        return Optional.empty();
    }

    private static Path newestWar(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".war"))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }))
                    .orElse(null);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** Puts the artifact into the base's webapps under the name the context path implies. */
    public static void deploy(Path base, Path artifact, String contextPath) throws IOException {
        String name = contextName(contextPath);
        Path webapps = base.resolve("webapps");
        Files.createDirectories(webapps);
        // Both forms of a previous deployment have to go, or Tomcat serves the old one.
        deleteTree(webapps.resolve(name));
        Files.deleteIfExists(webapps.resolve(name + ".war"));
        if (Files.isDirectory(artifact)) {
            copyTree(artifact, webapps.resolve(name));
        } else {
            Files.copy(artifact, webapps.resolve(name + ".war"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code /} is Tomcat's ROOT application; anything else is its own directory. */
    public static String contextName(String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "ROOT" : path.replace('/', '#');
    }

    public static String url(int port, String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://localhost:" + port + ("/".equals(path) ? "" : path) + "/";
    }

    // --------------------------------------------------------------- process

    /**
     * The command that starts Tomcat.
     *
     * <p>The bootstrap class is started directly instead of {@code catalina.bat} so that
     * the process the IDE owns is the JVM itself: Stop ends the server rather than a
     * script that has already exited, and a debug agent can be put on the command line.
     */
    public static List<String> command(Path jdk, Path home, Path base, List<String> vmArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(JavaTools.javaExecutable(jdk));
        cmd.add("-Dcatalina.home=" + home);
        cmd.add("-Dcatalina.base=" + base);
        cmd.add("-Djava.io.tmpdir=" + base.resolve("temp"));
        cmd.add("-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager");
        Path logging = base.resolve("conf").resolve("logging.properties");
        if (Files.isRegularFile(logging)) {
            cmd.add("-Djava.util.logging.config.file=" + logging);
        }
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.addAll(vmArgs);
        cmd.add("-cp");
        cmd.add(home.resolve("bin").resolve("bootstrap.jar")
                + java.io.File.pathSeparator + home.resolve("bin").resolve("tomcat-juli.jar"));
        cmd.add("org.apache.catalina.startup.Bootstrap");
        cmd.add("start");
        return cmd;
    }

    /**
     * Opens the application in a browser once the port answers.
     *
     * <p>Waiting for the port rather than opening straight away is the difference between
     * the application and an error page: Tomcat takes seconds to come up, longer with a
     * framework on top of it.
     */
    public static void openWhenUp(Ide ide, String url, int port, int secondsToWait) {
        ide.window().runInBackground(() -> {
            long deadline = System.currentTimeMillis() + secondsToWait * 1000L;
            while (System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("localhost", port), 500);
                    ide.window().runLater(() -> ide.window().browse(url));
                    return;
                } catch (IOException notYet) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    // ----------------------------------------------------------------- files

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> tree = Files.walk(from)) {
            for (Path source : tree.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(dir)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
