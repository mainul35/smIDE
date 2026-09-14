package com.smide.plugins.csharp;

import com.smide.api.Ide;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.util.ProjectFiles;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * dotnet run, test and build. Projects that build a program are offered to run, and test
 * projects to test, read from their .csproj - no configuration written.
 */
public final class DotnetRunType extends CommandRunType {

    private static final Pattern EXECUTABLE = Pattern.compile(
            "<OutputType>\\s*(Exe|WinExe)\\s*</OutputType>|Sdk=\"Microsoft\\.NET\\.Sdk\\.(Web|Worker|BlazorWebAssembly)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TEST = Pattern.compile(
            "Microsoft\\.NET\\.Test\\.Sdk|<IsTestProject>\\s*true\\s*</IsTestProject>", Pattern.CASE_INSENSITIVE);

    private final Function<Ide, Optional<Path>> dotnet;

    public DotnetRunType(Ide ide, Function<Ide, Optional<Path>> dotnet) {
        super(ide, "dotnet.run", ".NET", "mdi2l-language-csharp");
        this.dotnet = dotnet;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.choice("kind", "Kind", List.of("run", "test", "build")),
                Field.text("project", "Project", "App/App.csproj or the .sln, relative; empty to let dotnet find it"),
                Field.text("flags", "dotnet flags", "-c Release"),
                Field.text("args", "Arguments", "passed to the program"));
    }

    @Override
    protected Map<String, String> defaults() {
        return Map.of("kind", "run");
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) {
        Path binary = dotnet.apply(ide).orElseThrow(() -> new IllegalStateException(
                "The .NET SDK was not found. Install it from " + DotnetToolchain.DOWNLOAD
                        + ", or set its folder in Settings > Languages > C#."));
        String kind = c.get("kind", "run");
        String project = c.get("project", "");
        List<String> cmd = new ArrayList<>(List.of(binary.toString(), kind));
        if (!project.isBlank()) {
            if (kind.equals("run")) {
                cmd.add("--project");
            }
            cmd.add(project);
        }
        cmd.addAll(Forms.splitArgs(c.get("flags", "")));
        List<String> args = Forms.splitArgs(c.get("args", ""));
        if (kind.equals("run") && !args.isEmpty()) {
            cmd.add("--");
            cmd.addAll(args);
        }
        return new Command(cmd, c.workspace().root(), Map.of("DOTNET_NOLOGO", "true"));
    }

    private static final Pattern MAIN_LINE = Pattern.compile(
            "^[ \\t]*(?:(?:public|private|internal)\\s+)?static\\s+(?:async\\s+)?(?:void|int|Task(?:<int>)?)\\s+Main\\s*\\(",
            Pattern.MULTILINE);

    /** A Main method, run as the project it belongs to - the nearest .csproj above the file. */
    @Override
    public List<com.smide.api.execution.RunMarker> markers(Workspace workspace, Path file, String text) {
        Path root = workspace.root();
        if (!ProjectFiles.hasExtension(file, "cs") || !file.startsWith(root)) {
            return List.of();
        }
        java.util.regex.Matcher main = MAIN_LINE.matcher(text);
        if (!main.find()) {
            return List.of();
        }
        for (Path dir = file.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
                Optional<Path> csproj = files.filter(p -> ProjectFiles.hasExtension(p, "csproj")).sorted().findFirst();
                if (csproj.isPresent()) {
                    String name = csproj.get().getFileName().toString().replaceFirst("\\.csproj$", "");
                    return List.of(marker(workspace, com.smide.api.execution.RunMarker.lineOf(text, main.start()),
                            "dotnet run " + name, Map.of("kind", "run", "project", ProjectFiles.relative(root, csproj.get()))));
                }
            } catch (java.io.IOException e) {
                return List.of();
            }
        }
        return List.of();
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        for (Path csproj : ProjectFiles.find(root, 6, 5000, p -> ProjectFiles.hasExtension(p, "csproj"))) {
            String relative = ProjectFiles.relative(root, csproj);
            String name = csproj.getFileName().toString().replaceFirst("\\.csproj$", "");
            String text = ProjectFiles.head(csproj, 256_000);
            if (TEST.matcher(text).find()) {
                out.add(new Detected("dotnet test " + name, Map.of("kind", "test", "project", relative)));
            } else if (EXECUTABLE.matcher(text).find()) {
                out.add(new Detected("dotnet run " + name, Map.of("kind", "run", "project", relative)));
            }
        }
        return out;
    }
}
