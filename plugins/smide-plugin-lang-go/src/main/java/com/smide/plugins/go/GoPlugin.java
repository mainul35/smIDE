package com.smide.plugins.go;

import com.smide.api.Ide;
import com.smide.api.lang.FileType;
import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.RegexHighlighter;
import com.smide.api.lang.TokenType;
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

/**
 * Go: highlighting, gopls, run configurations for programs and tests, and the toolchain they
 * all need - asked for when a Go project is opened on a machine without it.
 */
public final class GoPlugin implements Plugin {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Override
    public void start(PluginContext context) {
        context.registerFileType(new FileType("go", "Go source", Set.of("go"), Set.of(),
                "mdi2l-language-go", false));
        context.registerFileType(new FileType("gomod", "Go module", Set.of("mod", "sum"),
                Set.of("go.mod", "go.sum", "go.work"), "mdi2l-language-go", false));
        GoToolchain toolchain = new GoToolchain();
        context.registerLanguage(new GoLanguage(new Gopls(toolchain)));
        context.registerLanguage(new GoModLanguage());
        context.registerToolchain(toolchain);
        context.registerRunConfigurationType(new GoRunType(context.ide(), toolchain::locate));
        context.registerDebugger(new GoDebugger());
        context.registerSettingsPage(new GoSettingsPage(context.ide(), toolchain));
    }

    private static final class GoLanguage implements LanguageSupport {

        private static final Highlighter LEXER = GenericLexer.builder()
                .keywords("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
                        "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
                        "return", "select", "struct", "switch", "type", "var")
                .types("bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int", "int8",
                        "int16", "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32",
                        "uint64", "uintptr", "any", "comparable")
                .constants("true", "false", "nil", "iota")
                .lineComment("//")
                .blockComment("/*", "*/")
                .rawString("`", "`")
                .typeByCase(false)
                .build();

        private final LanguageServerLauncher launcher;

        GoLanguage(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "go";
        }

        @Override
        public String displayName() {
            return "Go";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("go");
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

        /** gofmt indents with tabs, so the editor does too. */
        @Override
        public boolean useTabs() {
            return true;
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.of(launcher);
        }
    }

    /** {@code go.mod} and {@code go.sum}: a handful of directives and version strings. */
    private static final class GoModLanguage implements LanguageSupport {

        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?m)//[^\\n]*", TokenType.COMMENT)
                .rule("(?m)^(module|go|require|replace|exclude|retract|toolchain|use)\\b", TokenType.KEYWORD)
                .rule("\\bv\\d+\\.\\d+\\.\\d+[\\w.+-]*", TokenType.NUMBER)
                .rule("\\b(indirect|incompatible)\\b", TokenType.ANNOTATION)
                .rule("h1:[A-Za-z0-9+/=]+", TokenType.STRING);

        @Override
        public String id() {
            return "gomod";
        }

        @Override
        public String displayName() {
            return "Go module";
        }

        @Override
        public Set<String> extensions() {
            return Set.of();
        }

        @Override
        public Set<String> fileNames() {
            return Set.of("go.mod", "go.sum", "go.work");
        }

        @Override
        public Highlighter highlighter() {
            return HIGHLIGHTER;
        }

        @Override
        public String lineComment() {
            return "//";
        }
    }

    /**
     * gopls, installed with {@code go install}. Nothing else can install it sensibly - it
     * has to be built against the toolchain that will use it.
     */
    private static final class Gopls implements LanguageServerLauncher {

        private final GoToolchain toolchain;

        Gopls(GoToolchain toolchain) {
            this.toolchain = toolchain;
        }

        @Override
        public String serverId() {
            return "gopls";
        }

        @Override
        public String displayName() {
            return "gopls";
        }

        @Override
        public boolean isInstalled(Ide ide) {
            return locate().isPresent();
        }

        private static Optional<Path> locate() {
            return GoBinaries.find("gopls");
        }

        @Override
        public Optional<InstallRecipe> installRecipe() {
            return Optional.of(new InstallRecipe() {
                @Override
                public String description() {
                    return "Runs go install golang.org/x/tools/gopls@latest. The Go toolchain must be installed.";
                }

                @Override
                public void run(Ide ide, ProgressReporter progress) throws IOException {
                    String go = toolchain.locate(ide).map(Path::toString).orElse(WINDOWS ? "go.exe" : "go");
                    progress.progress("go install golang.org/x/tools/gopls@latest", -1);
                    ide.downloads().runTool(List.of(go, "install", "golang.org/x/tools/gopls@latest"),
                            ide.downloads().toolsDir(), progress);
                }
            });
        }

        /**
         * gopls runs the go command itself to load packages, so the toolchain found here goes
         * on its PATH - otherwise a Go installed anywhere but the PATH leaves it unable to load
         * a single file.
         */
        @Override
        public java.util.Map<String, String> environment(Ide ide, Workspace workspace) {
            return toolchain.locate(ide)
                    .map(go -> java.util.Map.of("PATH", go.getParent() + File.pathSeparator
                            + java.util.Objects.requireNonNullElse(System.getenv("PATH"), "")))
                    .orElse(java.util.Map.of());
        }

        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            return List.of(locate().orElseThrow(() -> new IllegalStateException("gopls is not installed")).toString());
        }
    }
}
