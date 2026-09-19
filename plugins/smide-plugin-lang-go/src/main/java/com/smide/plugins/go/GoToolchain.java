package com.smide.plugins.go;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** The Go toolchain: the go command, which builds, runs and tests Go code and installs gopls. */
public final class GoToolchain implements Toolchain {

    public static final String HOME_SETTING = "go.home";
    public static final String DOWNLOAD = "https://go.dev/dl/";
    /** Go's releases, newest first, each file with its size and checksum. */
    static final String RELEASES = "https://go.dev/dl/?mode=json";

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String EXE = WINDOWS ? "go.exe" : "go";
    private static final Set<String> SKIPPED = Set.of("vendor", "node_modules", "testdata");

    @Override
    public String id() {
        return "go";
    }

    @Override
    public String displayName() {
        return "Go toolchain";
    }

    @Override
    public String purpose() {
        return "Running, testing and building Go code need it, and so does installing gopls.";
    }

    @Override
    public String downloadUrl() {
        return DOWNLOAD;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    /** A module file at the root, or a .go file within a few folders of it. */
    @Override
    public boolean isNeededBy(Path root) {
        if (Files.isRegularFile(root.resolve("go.mod")) || Files.isRegularFile(root.resolve("go.work"))) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(root, 4)) {
            return walk.anyMatch(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".go")
                    && !skipped(root, p) && Files.isRegularFile(p));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean skipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString();
            if (name.startsWith(".") || SKIPPED.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The go command: from the setting, GOROOT, the PATH, or where installers put it.
     *
     * <p>Looked for, not just taken from the PATH, because an IDE started from a desktop menu
     * often has a shorter PATH than a terminal - Go installed and working in a shell, and
     * invisible to the application.
     */
    @Override
    public Optional<Path> locate(Ide ide) {
        String home = ide.settings().get(HOME_SETTING, "");
        if (!home.isBlank()) {
            Path bin = binaryIn(Path.of(home));
            if (bin != null) {
                return Optional.of(bin);
            }
        }
        String goroot = System.getenv("GOROOT");
        if (goroot != null && !goroot.isBlank()) {
            Path bin = binaryIn(Path.of(goroot));
            if (bin != null) {
                return Optional.of(bin);
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                try {
                    Path candidate = Path.of(dir.isBlank() ? "." : dir, EXE);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (RuntimeException ignored) {
                    // A malformed PATH entry.
                }
            }
        }
        for (Path usual : usualHomes()) {
            Path bin = binaryIn(usual);
            if (bin != null) {
                return Optional.of(bin);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean accepts(Path home) {
        return binaryIn(home) != null;
    }

    @Override
    public boolean runsFile(Path file) {
        return file.getFileName() != null && file.getFileName().toString().endsWith(".go");
    }

    @Override
    public Optional<Download> latestDownload(Ide ide) throws IOException {
        return pick(ide.downloads().fetchText(RELEASES), com.smide.api.util.Machine.os(),
                com.smide.api.util.Machine.arch());
    }

    /**
     * The newest stable release's archive for this machine, from go.dev's own list - which
     * carries a checksum for every file, so what is downloaded can be checked against it.
     * An archive, not an installer: unpacked, it is a complete Go that touches nothing else.
     */
    static Optional<Download> pick(String json, String os, String arch) {
        com.google.gson.JsonArray releases = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
        for (com.google.gson.JsonElement r : releases) {
            com.google.gson.JsonObject release = r.getAsJsonObject();
            if (!release.has("stable") || !release.get("stable").getAsBoolean()) {
                continue;
            }
            for (com.google.gson.JsonElement f : release.getAsJsonArray("files")) {
                com.google.gson.JsonObject file = f.getAsJsonObject();
                if (os.equals(text(file, "os")) && arch.equals(text(file, "arch")) && "archive".equals(text(file, "kind"))) {
                    return Optional.of(new Download(text(release, "version").replaceFirst("^go", ""),
                            DOWNLOAD + text(file, "filename"), text(file, "sha256"),
                            file.has("size") ? file.get("size").getAsLong() : -1, "go.dev"));
                }
            }
        }
        return Optional.empty();
    }

    private static String text(com.google.gson.JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** The go command under a folder, whether that folder is the Go root or its bin. */
    static Path binaryIn(Path home) {
        for (Path candidate : List.of(home.resolve("bin").resolve(EXE), home.resolve(EXE))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> usualHomes() {
        String user = System.getProperty("user.home", ".");
        List<Path> homes = new ArrayList<>();
        if (WINDOWS) {
            homes.add(Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Go"));
            homes.add(Path.of("C:\\Go"));
            homes.add(Path.of(user, "scoop", "apps", "go", "current"));
        } else {
            homes.add(Path.of("/usr/local/go"));
            homes.add(Path.of("/usr/lib/go"));
            homes.add(Path.of("/opt/homebrew/opt/go/libexec"));
            homes.add(Path.of("/usr/local/opt/go/libexec"));
            homes.add(Path.of("/snap/go/current"));
        }
        // Versions installed with "go install golang.org/dl/go1.x" live under ~/sdk, newest first.
        try (Stream<Path> sdks = Files.list(Path.of(user, "sdk"))) {
            sdks.filter(p -> p.getFileName().toString().startsWith("go"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(homes::add);
        } catch (IOException | RuntimeException ignored) {
            // No ~/sdk.
        }
        return homes;
    }
}
