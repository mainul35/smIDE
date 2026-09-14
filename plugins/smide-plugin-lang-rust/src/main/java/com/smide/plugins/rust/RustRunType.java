package com.smide.plugins.rust;

import com.smide.api.Ide;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.util.ProjectFiles;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * cargo run, test and build. Each crate is offered with its binaries and its tests, found
 * from Cargo.toml, src/main.rs and src/bin - no configuration written.
 */
public final class RustRunType extends CommandRunType {

    private static final Pattern PACKAGE_NAME =
            Pattern.compile("\\[package\\][^\\[]*?\\bname\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern BIN_NAME =
            Pattern.compile("\\[\\[bin\\]\\][^\\[]*?\\bname\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    private final Function<Ide, Optional<Path>> cargo;

    public RustRunType(Ide ide, Function<Ide, Optional<Path>> cargo) {
        super(ide, "rust.cargo", "Cargo", "mdi2l-language-rust");
        this.cargo = cargo;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.choice("kind", "Kind", List.of("run", "test", "build")),
                Field.text("crate", "Crate folder", "the folder holding Cargo.toml, relative; empty for the root"),
                Field.text("bin", "Binary", "for a crate with several: its name"),
                Field.text("flags", "Cargo flags", "--release"),
                Field.text("args", "Arguments", "passed to the program or the test harness"));
    }

    @Override
    protected Map<String, String> defaults() {
        return Map.of("kind", "run");
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) {
        Path cargoBinary = cargo.apply(ide).orElseThrow(() -> new IllegalStateException(
                "cargo was not found. Install Rust from " + RustToolchain.DOWNLOAD
                        + ", or set its folder in Settings > Languages > Rust."));
        Path crate = c.workspace().root().resolve(c.get("crate", ".")).normalize();
        if (!Files.isRegularFile(crate.resolve("Cargo.toml"))) {
            throw new IllegalStateException("There is no Cargo.toml in " + crate + ".");
        }
        String kind = c.get("kind", "run");
        List<String> cmd = new ArrayList<>(List.of(cargoBinary.toString(), kind));
        String bin = c.get("bin", "");
        if (kind.equals("run") && !bin.isBlank()) {
            cmd.addAll(List.of("--bin", bin));
        }
        cmd.addAll(Forms.splitArgs(c.get("flags", "")));
        List<String> args = Forms.splitArgs(c.get("args", ""));
        if (!args.isEmpty() && !kind.equals("build")) {
            cmd.add("--");
            cmd.addAll(args);
        }
        return new Command(cmd, crate);
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        for (Path manifest : ProjectFiles.find(root, 4, 4000, p -> p.getFileName().toString().equals("Cargo.toml"))) {
            Path crate = manifest.getParent();
            String relative = ProjectFiles.relative(root, crate);
            String suffix = relative.isEmpty() ? "" : " (" + relative + ")";
            String text = ProjectFiles.head(manifest, 256_000);
            if (text.contains("[package]")) {
                List<String> bins = binaries(crate, text);
                boolean main = Files.isRegularFile(crate.resolve("src").resolve("main.rs"));
                if (main && bins.isEmpty()) {
                    out.add(new Detected("cargo run" + suffix, Map.of("kind", "run", "crate", relative)));
                } else {
                    if (main) {
                        Matcher name = PACKAGE_NAME.matcher(text);
                        String packageName = name.find() ? name.group(1) : crate.getFileName().toString();
                        bins.add(0, packageName);
                    }
                    for (String bin : bins) {
                        out.add(new Detected("cargo run --bin " + bin + suffix,
                                Map.of("kind", "run", "crate", relative, "bin", bin)));
                    }
                }
            }
            if (text.contains("[package]") || text.contains("[workspace]")) {
                out.add(new Detected("cargo test" + suffix, Map.of("kind", "test", "crate", relative)));
            }
        }
        return out;
    }

    /** Binaries besides src/main.rs: src/bin/*.rs, src/bin/NAME/main.rs, and [[bin]] sections. */
    private static List<String> binaries(Path crate, String manifest) {
        List<String> out = new ArrayList<>();
        Path binDir = crate.resolve("src").resolve("bin");
        if (Files.isDirectory(binDir)) {
            try (Stream<Path> children = Files.list(binDir)) {
                for (Path child : children.sorted().toList()) {
                    String name = child.getFileName().toString();
                    if (name.endsWith(".rs")) {
                        out.add(name.substring(0, name.length() - 3));
                    } else if (Files.isRegularFile(child.resolve("main.rs"))) {
                        out.add(name);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Nothing readable.
            }
        }
        Matcher bins = BIN_NAME.matcher(manifest);
        while (bins.find()) {
            if (!out.contains(bins.group(1))) {
                out.add(bins.group(1));
            }
        }
        return out;
    }
}
