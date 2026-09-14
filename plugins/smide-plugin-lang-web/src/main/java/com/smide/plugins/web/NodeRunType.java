package com.smide.plugins.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smide.api.Ide;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.util.ProjectFiles;
import com.smide.api.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * npm scripts and Node files. Every script in every package.json is offered in the run list
 * as {@code npm run NAME} - no configuration written.
 */
public final class NodeRunType extends CommandRunType {

    private static final int MAX_DETECTED = 60;

    private final Function<Ide, Optional<Path>> node;

    public NodeRunType(Ide ide, Function<Ide, Optional<Path>> node) {
        super(ide, "node.run", "Node.js", "mdi2l-language-javascript");
        this.node = node;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.choice("kind", "Kind", List.of("script", "file")),
                Field.text("target", "Script or file", "an npm script name, or server.js"),
                Field.text("package", "Package folder", "the folder holding package.json, relative; empty for the root"),
                Field.text("args", "Arguments", ""));
    }

    @Override
    protected Map<String, String> defaults() {
        return Map.of("kind", "script");
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) {
        Path nodeBinary = node.apply(ide).orElseThrow(() -> new IllegalStateException(
                "Node.js was not found. Install it from " + NodeToolchain.DOWNLOAD
                        + ", or set its folder in Settings > Languages > Node.js."));
        Path folder = c.workspace().root().resolve(c.get("package", ".")).normalize();
        String target = c.get("target", "");
        if (target.isBlank()) {
            throw new IllegalStateException("Nothing to run: set an npm script name or a file.");
        }
        List<String> args = Forms.splitArgs(c.get("args", ""));
        List<String> cmd = new ArrayList<>();
        if (c.get("kind", "script").equals("file")) {
            cmd.add(nodeBinary.toString());
            cmd.add(target);
            cmd.addAll(args);
        } else {
            Path npm = NodeToolchain.npmBeside(nodeBinary).orElseThrow(() -> new IllegalStateException(
                    "npm was not found beside " + nodeBinary + " or on the PATH."));
            cmd.addAll(List.of(npm.toString(), "run", target));
            if (!args.isEmpty()) {
                cmd.add("--");
                cmd.addAll(args);
            }
        }
        // npm starts node by name; the one found goes first on the PATH so it is that one.
        String path = System.getenv("PATH");
        return new Command(cmd, folder, Map.of("PATH", nodeBinary.getParent() + java.io.File.pathSeparator
                + (path == null ? "" : path)));
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        List<Path> manifests = new ArrayList<>(
                ProjectFiles.find(root, 3, 4000, p -> p.getFileName().toString().equals("package.json")));
        // The project's own scripts first, then its sub-packages': sorted by path alone, apps/ came before them.
        manifests.sort(java.util.Comparator.comparingInt((Path p) -> root.relativize(p).getNameCount())
                .thenComparing(Path::toString));
        for (Path manifest : manifests) {
            Path folder = manifest.getParent();
            String relative = ProjectFiles.relative(root, folder);
            String suffix = relative.isEmpty() ? "" : " (" + relative + ")";
            for (String script : scripts(manifest)) {
                if (out.size() >= MAX_DETECTED) {
                    return out;
                }
                out.add(new Detected("npm run " + script + suffix,
                        Map.of("kind", "script", "target", script, "package", relative)));
            }
        }
        return out;
    }

    /** The script names of a package.json, in the order written; none when it cannot be read. */
    static List<String> scripts(Path manifest) {
        List<String> out = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("scripts")
                    && parsed.getAsJsonObject().get("scripts").isJsonObject()) {
                JsonObject scripts = parsed.getAsJsonObject().getAsJsonObject("scripts");
                out.addAll(scripts.keySet());
            }
        } catch (Exception e) {
            // A package.json being edited is often not valid JSON for a moment; offer nothing from it.
        }
        return out;
    }
}
