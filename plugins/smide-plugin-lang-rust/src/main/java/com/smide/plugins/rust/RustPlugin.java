package com.smide.plugins.rust;

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

/** Rust, with rust-analyzer. */
public final class RustPlugin implements Plugin {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Override
    public void start(PluginContext context) {
        context.registerFileType(new FileType("rust", "Rust source", Set.of("rs"), Set.of(),
                "mdi2l-language-rust", false));
        context.registerLanguage(new RustLanguage(new RustAnalyzer()));
    }

    private static final class RustLanguage implements LanguageSupport {

        private static final Highlighter LEXER = GenericLexer.builder()
                .keywords("as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
                        "extern", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut",
                        "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait", "type",
                        "unsafe", "use", "where", "while", "union")
                .types("bool", "char", "f32", "f64", "i8", "i16", "i32", "i64", "i128", "isize", "str", "u8",
                        "u16", "u32", "u64", "u128", "usize", "String", "Vec", "Option", "Result", "Box", "Rc",
                        "Arc", "HashMap", "HashSet", "BTreeMap")
                .constants("true", "false", "None", "Some", "Ok", "Err")
                .lineComment("//")
                .blockComment("/*", "*/")
                .docComment("/**")
                // #[derive(...)] and #![no_std] are attributes; the preprocessor rule paints
                // the whole line, which is what they occupy.
                .preprocessorPrefix('#')
                .typeByCase(true)
                .build();

        private final LanguageServerLauncher launcher;

        RustLanguage(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "rust";
        }

        @Override
        public String displayName() {
            return "Rust";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("rs");
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

    /** rust-analyzer, which rustup installs as a component of the active toolchain. */
    private static final class RustAnalyzer implements LanguageServerLauncher {

        @Override
        public String serverId() {
            return "rust-analyzer";
        }

        @Override
        public String displayName() {
            return "rust-analyzer";
        }

        private static Optional<Path> locate() {
            return onPathOrInCargo(WINDOWS ? "rust-analyzer.exe" : "rust-analyzer");
        }

        /**
         * A rustup-installed executable, by ~/.cargo/bin first and PATH second.
         *
         * <p>PATH second on purpose. rustup puts its bin directory on the PATH of a login
         * shell, and an application started from a desktop menu does not have one - so an
         * IDE launched by clicking its icon cannot see rust-analyzer or rustup at all,
         * while the same IDE launched from a terminal can. Looking where rustup actually
         * puts things is the difference between the Install button working and it
         * reporting that rustup is not installed on a machine that has it.
         */
        private static Optional<Path> onPathOrInCargo(String exe) {
            Path cargo = Path.of(System.getProperty("user.home", "."), ".cargo", "bin", exe);
            if (Files.isRegularFile(cargo)) {
                return Optional.of(cargo);
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
                    return "Runs rustup component add rust-analyzer. Rust must be installed with rustup.";
                }

                @Override
                public void run(Ide ide, ProgressReporter progress) throws IOException {
                    String rustup = onPathOrInCargo(WINDOWS ? "rustup.exe" : "rustup")
                            .map(Path::toString)
                            .orElseThrow(() -> new IOException(
                                    "rustup is not installed. rust-analyzer is a component of a"
                                    + " rustup toolchain: install Rust from https://rustup.rs"
                                    + " and try again."));
                    progress.progress("rustup component add rust-analyzer", -1);
                    ide.downloads().runTool(List.of(rustup, "component", "add", "rust-analyzer"),
                            ide.downloads().toolsDir(), progress);
                }
            });
        }

        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            return List.of(locate()
                    .orElseThrow(() -> new IllegalStateException("rust-analyzer is not installed")).toString());
        }
    }
}
