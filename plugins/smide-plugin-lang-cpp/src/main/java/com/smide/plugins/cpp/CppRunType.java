package com.smide.plugins.cpp;

import com.smide.api.Ide;
import com.smide.api.execution.BuildStep;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;
import com.smide.api.workspace.Workspace;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * C and C++: a CMake build, make, one source file compiled and run, or a program already
 * built. What a project has decides what is offered - a CMakeLists.txt, a Makefile, or
 * source files with a main - no configuration written.
 */
public final class CppRunType extends CommandRunType {

    private static final Pattern MAIN = Pattern.compile("\\bint\\s+main\\s*\\(");
    private static final int MAX_DETECTED = 30;

    private final Function<Ide, Optional<Path>> cmake;
    private final Function<Ide, Optional<Path>> compiler;

    public CppRunType(Ide ide, Function<Ide, Optional<Path>> cmake, Function<Ide, Optional<Path>> compiler) {
        super(ide, "cpp.run", "C/C++", "mdi2l-language-cpp");
        this.cmake = cmake;
        this.compiler = compiler;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.choice("kind", "Kind", List.of("cmake", "make", "file", "executable")),
                Field.text("target", "Target", "CMake source folder, make target, a source file, or a built program"),
                Field.text("buildDir", "Build folder", "build"),
                Field.text("flags", "Flags", "compiler flags for a file, or build flags"),
                Field.text("args", "Arguments", "passed to the program"));
    }

    @Override
    protected Map<String, String> defaults() {
        return Map.of("kind", "cmake");
    }

    @Override
    protected String note() {
        return "cmake configures its build folder the first time and builds every run. file compiles one source"
                + " into build/smide and runs it. make runs make in the project root.";
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) throws Exception {
        Path root = c.workspace().root();
        String target = c.get("target", "");
        List<String> flags = Forms.splitArgs(c.get("flags", ""));
        List<String> args = Forms.splitArgs(c.get("args", ""));
        switch (c.get("kind", "cmake")) {
            case "make" -> {
                Path make = Executables.onPath("make").or(() -> Executables.onPath("mingw32-make"))
                        .orElseThrow(() -> new IllegalStateException("make was not found on the PATH."));
                List<String> cmd = new ArrayList<>(List.of(make.toString()));
                if (!target.isBlank()) {
                    cmd.add(target);
                }
                cmd.addAll(flags);
                return new Command(cmd, root);
            }
            case "file" -> {
                return compileAndRun(root, target, flags, args);
            }
            case "executable" -> {
                Path program = root.resolve(target).normalize();
                if (target.isBlank() || !Files.isRegularFile(program)) {
                    throw new IllegalStateException("There is no program at " + program + " - build it first.");
                }
                List<String> cmd = new ArrayList<>(List.of(program.toString()));
                cmd.addAll(args);
                return new Command(cmd, program.getParent());
            }
            default -> {
                Path cmakeBinary = cmake.apply(ide).orElseThrow(() -> new IllegalStateException(
                        "CMake was not found. Install it from " + CppToolchains.CMake.DOWNLOAD
                                + ", or set its folder in Settings > Languages > C and C++."));
                Path source = root.resolve(target.isBlank() ? "." : target).normalize();
                if (!Files.isRegularFile(source.resolve("CMakeLists.txt"))) {
                    throw new IllegalStateException("There is no CMakeLists.txt in " + source + ".");
                }
                Path build = source.resolve(c.get("buildDir", "build")).normalize();
                if (!Files.isRegularFile(build.resolve("CMakeCache.txt"))) {
                    BuildStep.run(ide, List.of(cmakeBinary.toString(), "-S", source.toString(), "-B", build.toString()),
                            source, Map.of(), "Configuring " + source.getFileName() + " with CMake");
                }
                List<String> cmd = new ArrayList<>(List.of(cmakeBinary.toString(), "--build", build.toString()));
                cmd.addAll(flags);
                return new Command(cmd, source);
            }
        }
    }

    private Command compileAndRun(Path root, String target, List<String> flags, List<String> args) throws Exception {
        Path source = root.resolve(target).normalize();
        if (target.isBlank() || !Files.isRegularFile(source)) {
            throw new IllegalStateException("There is no source file at " + source + ".");
        }
        Path cc = compiler.apply(ide).orElseThrow(() -> new IllegalStateException(
                "No C/C++ compiler was found. Install gcc or clang (" + CppToolchains.Compiler.DOWNLOAD
                        + "), or set its folder in Settings > Languages > C and C++."));
        boolean cpp = ProjectFiles.hasExtension(source, "cc", "cpp", "cxx", "c++");
        Path driver = cpp ? CppToolchains.Compiler.cxx(cc) : cc;
        String base = source.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path out = root.resolve("build").resolve("smide").resolve(base + (Executables.WINDOWS ? ".exe" : ""));
        Files.createDirectories(out.getParent());
        List<String> compile = new ArrayList<>(List.of(driver.toString()));
        if (CppToolchains.Compiler.isMsvc(driver)) {
            compile.addAll(List.of("/nologo", "/EHsc"));
            compile.addAll(flags);
            compile.addAll(List.of(source.toString(), "/Fe:" + out));
        } else {
            compile.addAll(flags);
            compile.addAll(List.of(source.toString(), "-o", out.toString()));
        }
        // The compiler's own folder on the PATH: MinGW's gcc finds its helpers and runtime DLLs through it.
        String path = System.getenv("PATH");
        Map<String, String> env = Map.of("PATH", cc.getParent() + File.pathSeparator + (path == null ? "" : path));
        BuildStep.run(ide, compile, source.getParent(), env, "Compiling " + source.getFileName());
        List<String> run = new ArrayList<>(List.of(out.toString()));
        run.addAll(args);
        return new Command(run, source.getParent(), env);
    }

    private static final Pattern MAIN_LINE = Pattern.compile("^[ \\t]*int\\s+main\\s*\\(", Pattern.MULTILINE);

    /**
     * The main function of a source file compiled and run on its own. A project CMake or make
     * builds is run through its build, as detection has it, so there is no file to run alone.
     */
    @Override
    public List<com.smide.api.execution.RunMarker> markers(Workspace workspace, Path file, String text) {
        Path root = workspace.root();
        if (!ProjectFiles.hasExtension(file, "c", "cc", "cpp", "cxx") || !file.startsWith(root)) {
            return List.of();
        }
        if (Files.isRegularFile(root.resolve("CMakeLists.txt"))
                || List.of("Makefile", "makefile", "GNUmakefile").stream().anyMatch(n -> Files.isRegularFile(root.resolve(n)))) {
            return List.of();
        }
        java.util.regex.Matcher main = MAIN_LINE.matcher(text);
        if (!main.find()) {
            return List.of();
        }
        String relative = ProjectFiles.relative(root, file);
        return List.of(marker(workspace, com.smide.api.execution.RunMarker.lineOf(text, main.start()),
                "compile and run " + relative, Map.of("kind", "file", "target", relative)));
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("CMakeLists.txt"))) {
            out.add(new Detected("CMake build", Map.of("kind", "cmake", "target", "")));
        }
        if (List.of("Makefile", "makefile", "GNUmakefile").stream().anyMatch(n -> Files.isRegularFile(root.resolve(n)))) {
            out.add(new Detected("make", Map.of("kind", "make", "target", "")));
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (Path source : ProjectFiles.find(root, 3, 3000,
                p -> ProjectFiles.hasExtension(p, "c", "cc", "cpp", "cxx") && ProjectFiles.contains(p, MAIN))) {
            if (out.size() >= MAX_DETECTED) {
                break;
            }
            String relative = ProjectFiles.relative(root, source);
            out.add(new Detected("compile and run " + relative, Map.of("kind", "file", "target", relative)));
        }
        return out;
    }
}
