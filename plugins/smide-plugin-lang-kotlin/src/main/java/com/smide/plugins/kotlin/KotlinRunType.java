package com.smide.plugins.kotlin;

import com.smide.api.Ide;
import com.smide.api.execution.BuildStep;
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
import java.util.regex.Pattern;

/**
 * Kotlin outside a build tool: a .kts script run by kotlinc, or a .kt file compiled to a jar
 * and run. Gradle and Maven projects are run through the Java plugin's configurations.
 */
public final class KotlinRunType extends CommandRunType {

    private static final Pattern MAIN = Pattern.compile("\\bfun\\s+main\\s*\\(");
    private static final int MAX_DETECTED = 30;

    private final Function<Ide, Optional<Path>> kotlinc;

    public KotlinRunType(Ide ide, Function<Ide, Optional<Path>> kotlinc) {
        super(ide, "kotlin.run", "Kotlin", "mdi2l-language-kotlin");
        this.kotlinc = kotlinc;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.text("file", "Script or file", "hello.main.kts or Main.kt, relative"),
                Field.text("args", "Arguments", ""));
    }

    @Override
    protected String note() {
        return "A .kts script runs with kotlinc -script. A .kt file is compiled into build/smide first, then run."
                + " For a Gradle or Maven project, use the Gradle or Application configurations instead.";
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) throws Exception {
        Path compiler = kotlinc.apply(ide).orElseThrow(() -> new IllegalStateException(
                "kotlinc was not found. Install it (" + KotlinToolchain.DOWNLOAD
                        + "), or set its folder in Settings > Languages > Kotlin."));
        Path root = c.workspace().root();
        String written = c.get("file", "");
        Path file = root.resolve(written).normalize();
        if (written.isBlank() || !Files.isRegularFile(file)) {
            throw new IllegalStateException("There is no Kotlin file at " + file + ".");
        }
        List<String> args = Forms.splitArgs(c.get("args", ""));
        List<String> cmd = new ArrayList<>();
        if (file.getFileName().toString().endsWith(".kts")) {
            cmd.addAll(List.of(compiler.toString(), "-script", file.toString()));
        } else {
            String base = file.getFileName().toString().replaceFirst("\\.kt$", "");
            Path jar = root.resolve("build").resolve("smide").resolve(base + ".jar");
            Files.createDirectories(jar.getParent());
            BuildStep.run(ide, List.of(compiler.toString(), file.toString(), "-include-runtime", "-d", jar.toString()),
                    file.getParent(), Map.of(), "Compiling " + file.getFileName());
            Path runner = KotlinToolchain.runner(compiler).orElseThrow(() -> new IllegalStateException(
                    "The kotlin runner was not found beside " + compiler + "."));
            cmd.addAll(List.of(runner.toString(), jar.toString()));
        }
        cmd.addAll(args);
        return new Command(cmd, file.getParent());
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        if (KotlinToolchain.builtByTool(root)) {
            return out;
        }
        for (Path file : ProjectFiles.find(root, 3, 3000, KotlinToolchain::isLooseKotlin)) {
            if (out.size() >= MAX_DETECTED) {
                break;
            }
            String relative = ProjectFiles.relative(root, file);
            if (relative.endsWith(".kts")) {
                out.add(new Detected("kotlinc -script " + relative, Map.of("file", relative)));
            } else if (ProjectFiles.contains(file, MAIN)) {
                out.add(new Detected("kotlin " + relative, Map.of("file", relative)));
            }
        }
        return out;
    }
}
