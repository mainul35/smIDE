package com.smide.plugins.web;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Node.js, with the npm beside it. Needed by projects with a package.json - not by a folder of static pages. */
public final class NodeToolchain implements Toolchain {

    public static final String HOME_SETTING = "node.home";
    public static final String DOWNLOAD = "https://nodejs.org/";
    /** Every Node.js release, newest first, marked with its long-term-support line if it has one. */
    static final String INDEX = "https://nodejs.org/dist/index.json";
    static final String DIST = "https://nodejs.org/dist/";

    @Override
    public String id() {
        return "node";
    }

    @Override
    public String displayName() {
        return "Node.js";
    }

    @Override
    public String purpose() {
        return "Running npm scripts and JavaScript needs Node.js and npm, and so do the TypeScript, HTML and CSS language servers.";
    }

    @Override
    public String downloadUrl() {
        return DOWNLOAD;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    /** A package.json near the top: a site of plain .js files runs in a browser and needs no Node. */
    @Override
    public boolean isNeededBy(Path root) {
        return Files.isRegularFile(root.resolve("package.json"))
                || ProjectFiles.any(root, 2, p -> p.getFileName().toString().equals("package.json"));
    }

    @Override
    public Optional<Path> locate(Ide ide) {
        List<Path> usual = new ArrayList<>(Executables.programFiles("nodejs"));
        usual.add(Executables.home().resolve(".volta"));
        usual.addAll(Executables.subfolders(Executables.home().resolve(".nvm").resolve("versions").resolve("node"), "v"));
        Path appData = Executables.env("APPDATA");
        if (appData != null) {
            usual.addAll(Executables.subfolders(appData.resolve("nvm"), "v"));
        }
        usual.add(Path.of("/usr/local"));
        usual.add(Path.of("/opt/homebrew"));
        usual.add(Path.of("/usr"));
        return Executables.find(ide, HOME_SETTING, "node", usual, p -> true);
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, "node").isPresent();
    }

    @Override
    public boolean runsFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs");
    }

    /**
     * The newest long-term-support release, which is what nodejs.org tells people to install,
     * as the portable archive for this machine, with the checksum nodejs.org publishes beside
     * it.
     */
    @Override
    public Optional<Download> latestDownload(Ide ide) throws java.io.IOException {
        String version = newestLts(ide.downloads().fetchText(INDEX));
        String file = version == null ? null
                : fileName(version, com.smide.api.util.Machine.os(), com.smide.api.util.Machine.arch());
        if (file == null) {
            return Optional.empty();
        }
        String sha = checksum(ide.downloads().fetchText(DIST + version + "/SHASUMS256.txt"), file);
        return Optional.of(new Download(version.substring(1), DIST + version + "/" + file, sha, -1, "nodejs.org"));
    }

    /** The newest release on a long-term-support line: {@code lts} is the line's name then, and false otherwise. */
    static String newestLts(String index) {
        for (com.google.gson.JsonElement e : com.google.gson.JsonParser.parseString(index).getAsJsonArray()) {
            com.google.gson.JsonObject release = e.getAsJsonObject();
            com.google.gson.JsonElement lts = release.get("lts");
            if (lts != null && lts.isJsonPrimitive() && lts.getAsJsonPrimitive().isString()) {
                return release.get("version").getAsString();
            }
        }
        return null;
    }

    /** The archive's name for this machine, as nodejs.org names it; null where it builds none. */
    static String fileName(String version, String os, String arch) {
        String system = switch (os) {
            case "windows" -> "win";
            case "linux" -> "linux";
            case "darwin" -> "darwin";
            default -> null;
        };
        String processor = switch (arch) {
            case "amd64" -> "x64";
            case "arm64" -> "arm64";
            default -> null;
        };
        if (system == null || processor == null) {
            return null;
        }
        return "node-" + version + "-" + system + "-" + processor + (os.equals("windows") ? ".zip" : ".tar.gz");
    }

    /** A file's line in a SHASUMS256.txt: its checksum, two spaces, its name. */
    static String checksum(String sums, String file) {
        for (String line : sums.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2 && parts[1].equals(file)) {
                return parts[0];
            }
        }
        return null;
    }

    /** npm: beside the node found, else on the PATH. */
    public static Optional<Path> npmBeside(Path node) {
        return Executables.in(node.getParent(), "npm").or(() -> Executables.onPath("npm"));
    }
}
