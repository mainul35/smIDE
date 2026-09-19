package com.smide.plugins.python;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A Python interpreter: the project's own virtual environment first, then one installed on the machine. */
public final class PythonToolchain implements Toolchain {

    public static final String HOME_SETTING = "python.home";
    public static final String DOWNLOAD = "https://www.python.org/downloads/";
    /**
     * Portable CPython builds - python-build-standalone, the ones uv and rye install - whose
     * latest release lists an archive for every platform. python.org publishes installers,
     * which change the machine; these unpack into a folder and are complete where they land.
     */
    static final String RELEASES = "https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest";
    static final String SOURCE = "python-build-standalone (Astral, on GitHub)";

    @Override
    public String id() {
        return "python";
    }

    @Override
    public String displayName() {
        return "Python";
    }

    @Override
    public String purpose() {
        return "Running Python scripts, modules and tests needs an interpreter. A project's own .venv is used when it has one.";
    }

    @Override
    public String downloadUrl() {
        return DOWNLOAD;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    /** A Python project - unless it carries its own interpreter in a virtual environment. */
    @Override
    public boolean isNeededBy(Path root) {
        if (venvInterpreter(root).isPresent()) {
            return false;
        }
        for (String marker : List.of("pyproject.toml", "requirements.txt", "setup.py", "Pipfile")) {
            if (Files.isRegularFile(root.resolve(marker))) {
                return true;
            }
        }
        return ProjectFiles.any(root, 3, p -> ProjectFiles.hasExtension(p, "py"));
    }

    /**
     * An interpreter installed on the machine.
     *
     * <p>Windows's own python.exe alias is passed over: until Python is installed from the
     * Store it opens the Store instead of running anything.
     */
    @Override
    public Optional<Path> locate(Ide ide) {
        String program = Executables.WINDOWS ? "python" : "python3";
        Optional<Path> found = Executables.find(ide, HOME_SETTING, program, usual(), p -> !Executables.isStoreAlias(p));
        if (found.isPresent()) {
            return found;
        }
        if (!Executables.WINDOWS) {
            return Executables.find(ide, null, "python", List.of(), p -> true);
        }
        // python.org's installer adds the py launcher, which runs the newest Python it knows.
        return Executables.onPath("py", p -> !Executables.isStoreAlias(p));
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, Executables.WINDOWS ? "python" : "python3").isPresent()
                || Executables.in(home, "python").isPresent();
    }

    @Override
    public boolean runsFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.endsWith(".py") || name.endsWith(".pyw");
    }

    @Override
    public Optional<Download> latestDownload(Ide ide) throws java.io.IOException {
        String release = ide.downloads().fetchText(RELEASES);
        Optional<Asset> asset = pick(release, Executables.WINDOWS ? "windows" : com.smide.api.util.Machine.os(),
                com.smide.api.util.Machine.arch());
        if (asset.isEmpty()) {
            return Optional.empty();
        }
        String sha = asset.get().sha256();
        if (sha == null) {
            String sums = assetUrl(release, "SHA256SUMS");
            sha = sums == null ? null : checksum(ide.downloads().fetchText(sums), asset.get().name());
        }
        return Optional.of(new Download(asset.get().version(), asset.get().url(), sha, asset.get().size(), SOURCE));
    }

    /** One archive in a release. */
    record Asset(String name, String version, String url, long size, String sha256) {
    }

    /** The build target a release names its archives after, for this machine; null where it builds none. */
    static String triple(String os, String arch) {
        String cpu = switch (arch) {
            case "amd64" -> "x86_64";
            case "arm64" -> "aarch64";
            default -> null;
        };
        if (cpu == null) {
            return null;
        }
        return switch (os) {
            case "windows" -> cpu + "-pc-windows-msvc";
            case "linux" -> cpu + "-unknown-linux-gnu";
            case "darwin" -> cpu + "-apple-darwin";
            default -> null;
        };
    }

    /**
     * The newest stable Python in the release for this machine.
     *
     * <p>A release carries every supported Python at once - 3.9 to the newest - and pre-releases
     * beside them. Only plain versions count ({@code 3.13.7}, not {@code 3.14.0rc2}), the ordinary
     * build rather than the free-threaded one, and the "install only" archive: stripped of debug
     * symbols where that is offered, which is a third of the size and runs the same.
     */
    static Optional<Asset> pick(String releaseJson, String os, String arch) {
        String triple = triple(os, arch);
        if (triple == null) {
            return Optional.empty();
        }
        java.util.regex.Pattern name = java.util.regex.Pattern.compile("cpython-(\\d+)\\.(\\d+)\\.(\\d+)\\+\\d+-"
                + java.util.regex.Pattern.quote(triple) + "-install_only(_stripped)?\\.tar\\.gz");
        com.google.gson.JsonObject release = com.google.gson.JsonParser.parseString(releaseJson).getAsJsonObject();
        Asset best = null;
        int[] bestVersion = null;
        boolean bestStripped = false;
        for (com.google.gson.JsonElement e : release.getAsJsonArray("assets")) {
            com.google.gson.JsonObject a = e.getAsJsonObject();
            java.util.regex.Matcher m = name.matcher(a.get("name").getAsString());
            if (!m.matches()) {
                continue;
            }
            int[] version = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            boolean stripped = m.group(4) != null;
            int order = bestVersion == null ? 1 : java.util.Arrays.compare(version, bestVersion);
            if (order > 0 || (order == 0 && stripped && !bestStripped)) {
                String digest = a.has("digest") && !a.get("digest").isJsonNull() ? a.get("digest").getAsString() : null;
                best = new Asset(a.get("name").getAsString(), version[0] + "." + version[1] + "." + version[2],
                        a.get("browser_download_url").getAsString(), a.has("size") ? a.get("size").getAsLong() : -1,
                        digest != null && digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null);
                bestVersion = version;
                bestStripped = stripped;
            }
        }
        return Optional.ofNullable(best);
    }

    private static String assetUrl(String releaseJson, String assetName) {
        com.google.gson.JsonObject release = com.google.gson.JsonParser.parseString(releaseJson).getAsJsonObject();
        for (com.google.gson.JsonElement e : release.getAsJsonArray("assets")) {
            com.google.gson.JsonObject a = e.getAsJsonObject();
            if (assetName.equals(a.get("name").getAsString())) {
                return a.get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    /** A file's line in a SHA256SUMS: its checksum, spaces, its name. */
    static String checksum(String sums, String file) {
        for (String line : sums.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2 && parts[1].equals(file)) {
                return parts[0];
            }
        }
        return null;
    }

    /** The interpreter of the project's virtual environment, if it has one. */
    public static Optional<Path> venvInterpreter(Path root) {
        for (String name : List.of(".venv", "venv", "env")) {
            Path interpreter = Executables.WINDOWS
                    ? root.resolve(name).resolve("Scripts").resolve("python.exe")
                    : root.resolve(name).resolve("bin").resolve("python");
            if (Files.isRegularFile(interpreter)) {
                return Optional.of(interpreter);
            }
        }
        return Optional.empty();
    }

    private static List<Path> usual() {
        List<Path> folders = new ArrayList<>();
        if (Executables.WINDOWS) {
            Path local = Executables.env("LOCALAPPDATA");
            if (local != null) {
                folders.addAll(Executables.subfolders(local.resolve("Programs").resolve("Python"), "Python3"));
            }
            folders.addAll(Executables.subfolders(Path.of("C:\\"), "Python3"));
            Path programFiles = Executables.env("ProgramFiles");
            if (programFiles != null) {
                folders.addAll(Executables.subfolders(programFiles, "Python3"));
            }
        } else {
            folders.add(Path.of("/usr/bin"));
            folders.add(Path.of("/usr/local/bin"));
            folders.add(Path.of("/opt/homebrew/bin"));
            folders.add(Executables.home().resolve(".pyenv").resolve("shims"));
        }
        return folders;
    }
}
