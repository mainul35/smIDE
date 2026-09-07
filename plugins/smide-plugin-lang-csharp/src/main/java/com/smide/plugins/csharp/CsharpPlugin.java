package com.smide.plugins.csharp;

import com.smide.api.Ide;
import com.smide.api.lang.FileType;
import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.workspace.Workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** C#, with csharp-ls. */
public final class CsharpPlugin implements Plugin {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Override
    public void start(PluginContext context) {
        context.registerFileType(new FileType("csharp", "C# source", Set.of("cs", "csx"), Set.of(),
                "mdi2l-language-csharp", false));
        context.registerLanguage(new CsharpLanguage(new CsharpLs()));
    }

    private static final class CsharpLanguage implements LanguageSupport {

        private static final Highlighter LEXER = GenericLexer.builder()
                .keywords("abstract", "as", "async", "await", "base", "break", "case", "catch", "checked",
                        "class", "const", "continue", "default", "delegate", "do", "else", "enum", "event",
                        "explicit", "extern", "finally", "fixed", "for", "foreach", "get", "goto", "if",
                        "implicit", "in", "init", "interface", "internal", "is", "lock", "namespace", "new",
                        "operator", "out", "override", "params", "partial", "private", "protected", "public",
                        "readonly", "record", "ref", "return", "sealed", "set", "sizeof", "stackalloc", "static",
                        "struct", "switch", "this", "throw", "try", "typeof", "unchecked", "unsafe", "using",
                        "value", "var", "virtual", "volatile", "when", "where", "while", "yield")
                .types("bool", "byte", "char", "decimal", "double", "dynamic", "float", "int", "long", "nint",
                        "nuint", "object", "sbyte", "short", "string", "uint", "ulong", "ushort", "void",
                        "List", "Dictionary", "Task", "IEnumerable", "Span", "DateTime", "Guid")
                .constants("true", "false", "null")
                .lineComment("//")
                .blockComment("/*", "*/")
                .docComment("/**")
                .preprocessorPrefix('#')
                .typeByCase(true)
                .build();

        private final LanguageServerLauncher launcher;

        CsharpLanguage(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "csharp";
        }

        @Override
        public String displayName() {
            return "C#";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("cs", "csx");
        }

        @Override
        public Highlighter highlighter() {
            return LEXER;
        }

        @Override
        public String lineComment() {
            return "//";
        }

        @Override
        public BlockComment blockComment() {
            return new BlockComment("/*", "*/");
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.of(launcher);
        }
    }

    /** csharp-ls, a dotnet global tool. */
    private static final class CsharpLs implements LanguageServerLauncher {

        /* Pinned. The newest package on nuget.org is missing its DotnetToolSettings.xml,
           so "dotnet tool install csharp-ls" fails outright with a message about the
           tool's author; this version installs and runs. */
        private static final String VERSION = "0.17.0";

        @Override
        public String serverId() {
            return "csharp-ls";
        }

        @Override
        public String displayName() {
            return "csharp-ls";
        }

        private static Optional<Path> locate() {
            String exe = WINDOWS ? "csharp-ls.exe" : "csharp-ls";
            Path tools = Path.of(System.getProperty("user.home", "."), ".dotnet", "tools", exe);
            if (Files.isRegularFile(tools)) {
                return Optional.of(tools);
            }
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(File.pathSeparator)) {
                    Path candidate = Path.of(dir.isBlank() ? "." : dir, exe);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean isInstalled(Ide ide) {
            return locate().isPresent();
        }

        @Override
        public Optional<InstallRecipe> installRecipe() {
            return Optional.of(new InstallRecipe() {
                @Override
                public String description() {
                    return "Runs dotnet tool install --global csharp-ls " + VERSION
                            + ". The .NET SDK must be installed.";
                }

                @Override
                public void run(Ide ide, ProgressReporter progress) throws IOException {
                    String dotnet = WINDOWS ? "dotnet.exe" : "dotnet";
                    progress.progress("dotnet tool install --global csharp-ls " + VERSION, -1);
                    ide.downloads().runTool(List.of(dotnet, "tool", "install", "--global", "csharp-ls",
                            "--version", VERSION), ide.downloads().toolsDir(), progress);
                }
            });
        }

        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            return List.of(locate()
                    .orElseThrow(() -> new IllegalStateException("csharp-ls is not installed")).toString());
        }

        /**
         * Points the server at the .NET SDK.
         *
         * <p>csharp-ls loads MSBuild, which finds the SDK through DOTNET_ROOT or PATH and
         * refuses to initialise without it - "Path to dotnet executable is not set". The
         * IDE's own PATH is whatever it was started with, and on a machine where .NET was
         * installed afterwards that is not enough, so the location is passed explicitly.
         */
        @Override
        public java.util.Map<String, String> environment(Ide ide, Workspace workspace) {
            Optional<Path> dotnet = dotnetHome();
            if (dotnet.isEmpty()) {
                return java.util.Map.of();
            }
            String home = dotnet.get().toString();
            String path = System.getenv("PATH");
            return java.util.Map.of(
                    "DOTNET_ROOT", home,
                    "DOTNET_HOST_PATH", dotnet.get().resolve(WINDOWS ? "dotnet.exe" : "dotnet").toString(),
                    "PATH", path == null || path.isBlank() ? home : home + File.pathSeparator + path);
        }

        /** Where the SDK is: the environment, then PATH, then where the installer puts it. */
        private static Optional<Path> dotnetHome() {
            String root = System.getenv("DOTNET_ROOT");
            if (root != null && !root.isBlank() && Files.isDirectory(Path.of(root))) {
                return Optional.of(Path.of(root));
            }
            String exe = WINDOWS ? "dotnet.exe" : "dotnet";
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(File.pathSeparator)) {
                    Path candidate = Path.of(dir.isBlank() ? "." : dir, exe);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate.getParent());
                    }
                }
            }
            for (Path candidate : List.of(
                    Path.of("C:", "Program Files", "dotnet"),
                    Path.of(System.getProperty("user.home", "."), ".dotnet"),
                    Path.of("/usr/share/dotnet"),
                    Path.of("/usr/local/share/dotnet"))) {
                if (Files.isRegularFile(candidate.resolve(exe))) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }
}
