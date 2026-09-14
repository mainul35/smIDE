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

    /** npm: beside the node found, else on the PATH. */
    public static Optional<Path> npmBeside(Path node) {
        return Executables.in(node.getParent(), "npm").or(() -> Executables.onPath("npm"));
    }
}
