package com.smide.plugins.go;

import com.smide.api.Ide;
import com.smide.api.ui.StatusBar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Delve, chosen for the Go toolchain that builds what it debugs.
 *
 * <p>Each Delve release debugs a window of Go versions - 1.25 takes Go 1.22 to 1.25, 1.27
 * starts at 1.25 - and refuses anything outside it: "Go version go1.22.2 is too old for this
 * version of Delve". {@code go install ...@latest} therefore gives a machine on an older Go a
 * debugger that will not debug. So the IDE keeps a Delve for each Go minor version in its
 * tools folder, the newest release whose window holds that version, and uses one already on
 * the machine only when its window holds it too. The windows come from each release's own
 * {@code pkg/goversion/compat.go}, fetched once and remembered.
 */
final class Delve {

    static final String MODULE = "github.com/go-delve/delve";
    private static final String VERSIONS = "https://proxy.golang.org/github.com/go-delve/delve/@v/list";
    private static final String COMPAT = "https://raw.githubusercontent.com/go-delve/delve/%s/pkg/goversion/compat.go";
    private static final Pattern MIN = Pattern.compile("MinSupportedVersionOfGoMinor\\s*=\\s*(\\d+)");
    private static final Pattern MAX = Pattern.compile("MaxSupportedVersionOfGoMinor\\s*=\\s*(\\d+)");
    private static final Pattern GO_VERSION = Pattern.compile("go1\\.(\\d+)");
    private static final Pattern DLV_VERSION = Pattern.compile("Version:\\s*v?(\\d+\\.\\d+\\.\\d+)");
    private static final Pattern RELEASE = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final Map<String, Integer> GO_MINORS = new ConcurrentHashMap<>();

    private Delve() {
    }

    /** The Go minor versions a Delve release debugs. */
    record Window(int min, int max) {
        boolean includes(int minor) {
            return min <= minor && minor <= max;
        }

        String text() {
            return "Go 1." + min + " to 1." + max;
        }
    }

    /**
     * What to debug with, or why there is nothing to.
     *
     * @param binary       the dlv to run, or null
     * @param problem      why there is none, for the refusal
     * @param installLabel the button that fixes it
     */
    record Choice(Path binary, String problem, String installLabel) {
    }

    /** The Delve to debug what this go command builds. */
    static Choice forGo(Ide ide, Path go) {
        return forMinor(ide, goMinor(go));
    }

    /** The Delve for a Go minor version; a negative one means the version could not be read. */
    static Choice forMinor(Ide ide, int minor) {
        if (minor >= 0) {
            Path managed = managed(ide, minor);
            if (Files.isRegularFile(managed)) {
                return new Choice(managed, null, null);
            }
        }
        Optional<Path> found = GoBinaries.find("dlv");
        if (found.isEmpty()) {
            return new Choice(null, "Debugging Go needs Delve, the Go debugger, which is not installed.",
                    minor >= 0 ? "Install Delve for Go 1." + minor : "Install Delve");
        }
        String version = minor < 0 ? null : dlvVersion(found.get());
        Window window = version == null ? null : window(ide, "v" + version);
        // When nothing can be read about either side, the Delve there is the best guess.
        if (window == null || window.includes(minor)) {
            return new Choice(found.get(), null, null);
        }
        return new Choice(null, "Delve " + version + " at " + found.get() + " debugs " + window.text()
                + ", and this project builds with Go 1." + minor + ".", "Install Delve for Go 1." + minor);
    }

    /** Installs the newest Delve that debugs this go command's version, into the IDE's tools folder. */
    static void install(Ide ide, Path go) {
        StatusBar.Progress progress = ide.statusBar().progress("Installing Delve", false);
        ide.window().runInBackground(() -> {
            try {
                int minor = goMinor(go);
                if (minor < 0) {
                    throw new IOException(go + " did not say which version of Go it is.");
                }
                progress.update("Choosing the Delve release that debugs Go 1." + minor, -1);
                String version = choose(ide, minor, progress);
                Path dir = managed(ide, minor).getParent();
                Files.createDirectories(dir);
                String module = MODULE + "/cmd/dlv@" + version;
                progress.update("go install " + module + " into " + dir, -1);
                // -v names each package as it is built, so the task shows where it has got to.
                run(List.of(go.toString(), "install", "-v", module), dir, Map.of("GOBIN", dir.toString()), progress);
                if (!Files.isRegularFile(managed(ide, minor))) {
                    throw new IOException("go install finished, but there is no dlv in " + dir + ".");
                }
                ide.notifications().info("Delve " + version + " installed",
                        "It debugs Go 1." + minor + ". Debug the configuration again to start debugging.");
            } catch (IOException | RuntimeException e) {
                ide.notifications().error("Delve was not installed", String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                progress.done();
            }
        });
    }

    /** The newest release whose window holds a Go minor version, or "latest" when none can be found. */
    static String choose(Ide ide, int minor, StatusBar.Progress progress) {
        List<String> releases;
        try {
            releases = newestPatches(ide.downloads().fetchText(VERSIONS));
        } catch (IOException e) {
            progress.update("Could not list Delve releases (" + e.getMessage() + "); installing the latest", -1);
            return "latest";
        }
        for (String tag : releases) {
            Window window = window(ide, tag);
            if (window == null) {
                continue;
            }
            progress.update("Delve " + tag + " debugs " + window.text(), -1);
            if (window.includes(minor)) {
                return tag;
            }
            if (window.max() < minor) {
                // Newer than every release knows; older releases only know less.
                break;
            }
        }
        return "latest";
    }

    /** The newest patch of each release line, newest line first: v1.27.2, v1.26.3, v1.25.2... */
    static List<String> newestPatches(String list) {
        Map<String, int[]> newest = new LinkedHashMap<>();
        for (String line : list.split("\\R")) {
            Matcher m = RELEASE.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            newest.merge(v[0] + "." + v[1], v, (a, b) -> a[2] >= b[2] ? a : b);
        }
        List<int[]> versions = new ArrayList<>(newest.values());
        versions.sort(Comparator.<int[]>comparingInt(v -> v[0]).thenComparingInt(v -> v[1]).reversed());
        return versions.stream().map(v -> "v" + v[0] + "." + v[1] + "." + v[2]).toList();
    }

    /** A release's window, from its compat.go - remembered in the tools folder - or null when it cannot be read. */
    static Window window(Ide ide, String tag) {
        Path remembered = ide.downloads().toolsDir().resolve("delve").resolve("windows").resolve(tag + ".txt");
        try {
            if (Files.isRegularFile(remembered)) {
                String[] parts = Files.readString(remembered).strip().split(" ");
                return new Window(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            String source = ide.downloads().fetchText(String.format(COMPAT, tag));
            Matcher min = MIN.matcher(source);
            Matcher max = MAX.matcher(source);
            if (!min.find() || !max.find()) {
                return null;
            }
            Window window = new Window(Integer.parseInt(min.group(1)), Integer.parseInt(max.group(1)));
            Files.createDirectories(remembered.getParent());
            Files.writeString(remembered, window.min() + " " + window.max());
            return window;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Where the IDE keeps the Delve for a Go minor version. */
    static Path managed(Ide ide, int minor) {
        return ide.downloads().toolsDir().resolve("delve").resolve("go1." + minor).resolve(WINDOWS ? "dlv.exe" : "dlv");
    }

    /** The minor version of Go a go command is, 22 for go1.22.2, or -1. */
    static int goMinor(Path go) {
        return GO_MINORS.computeIfAbsent(go.toString() + "@" + lastModified(go), key -> {
            String out = output(List.of(go.toString(), "env", "GOVERSION"));
            Matcher m = out == null ? null : GO_VERSION.matcher(out);
            return m != null && m.find() ? Integer.parseInt(m.group(1)) : -1;
        });
    }

    private static String dlvVersion(Path dlv) {
        String out = output(List.of(dlv.toString(), "version"));
        Matcher m = out == null ? null : DLV_VERSION.matcher(out);
        return m != null && m.find() ? m.group(1) : null;
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** What a short command printed, or null when it failed or took too long. */
    private static String output(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? text : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Runs a command, reporting each line; throws with the last lines when it fails. */
    private static void run(List<String> command, Path dir, Map<String, String> env, StatusBar.Progress progress)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        Process process = builder.start();
        List<String> tail = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                progress.update(line, -1);
                tail.add(line);
                if (tail.size() > 15) {
                    tail.remove(0);
                }
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException(String.join(" ", command) + " exited with " + code
                    + (tail.isEmpty() ? "." : ":\n" + String.join("\n", tail)));
        }
    }
}
