package com.smide.plugins.cpp;

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
import java.util.stream.Stream;

/** C, C++ and CMake, with clangd. */
public final class CppPlugin implements Plugin {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String[] SHARED_KEYWORDS = {
            "auto", "break", "case", "const", "continue", "default", "do", "else", "enum", "extern", "for",
            "goto", "if", "inline", "register", "restrict", "return", "sizeof", "static", "struct", "switch",
            "typedef", "union", "volatile", "while"};

    private static final String[] CPP_KEYWORDS = {
            "alignas", "alignof", "and", "asm", "catch", "class", "co_await", "co_return", "co_yield",
            "concept", "constexpr", "consteval", "constinit", "const_cast", "decltype", "delete", "dynamic_cast",
            "explicit", "export", "friend", "mutable", "namespace", "new", "noexcept", "not", "operator", "or",
            "private", "protected", "public", "reinterpret_cast", "requires", "static_assert", "static_cast",
            "template", "this", "thread_local", "throw", "try", "typeid", "typename", "using", "virtual", "xor"};

    private static final String[] TYPES = {
            "bool", "char", "char8_t", "char16_t", "char32_t", "double", "float", "int", "long", "short",
            "signed", "unsigned", "void", "wchar_t", "size_t", "ptrdiff_t", "int8_t", "int16_t", "int32_t",
            "int64_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "string", "vector", "map", "set",
            "unique_ptr", "shared_ptr", "optional", "span"};

    @Override
    public void start(PluginContext context) {
        Clangd clangd = new Clangd();
        context.registerFileType(new FileType("c", "C source", Set.of("c", "h"), Set.of(),
                "mdi2l-language-c", false));
        context.registerFileType(new FileType("cpp", "C++ source",
                Set.of("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "inl", "ipp"), Set.of(),
                "mdi2l-language-cpp", false));
        context.registerFileType(new FileType("cmake", "CMake", Set.of("cmake"),
                Set.of("CMakeLists.txt"), "fth-tool", false));
        context.registerLanguage(new CFamily("c", "C", Set.of("c", "h"), false, clangd));
        context.registerLanguage(new CFamily("cpp", "C++",
                Set.of("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "inl", "ipp"), true, clangd));
        context.registerLanguage(new CMakeLanguage());
    }

    private static final class CFamily implements LanguageSupport {

        private final String id;
        private final String displayName;
        private final Set<String> extensions;
        private final Highlighter highlighter;
        private final LanguageServerLauncher launcher;

        CFamily(String id, String displayName, Set<String> extensions, boolean cpp, LanguageServerLauncher launcher) {
            this.id = id;
            this.displayName = displayName;
            this.extensions = extensions;
            this.launcher = launcher;
            java.util.Set<String> keywords = new java.util.HashSet<>(Set.of(SHARED_KEYWORDS));
            if (cpp) {
                keywords.addAll(Set.of(CPP_KEYWORDS));
            }
            this.highlighter = GenericLexer.builder()
                    .keywords(keywords)
                    .types(TYPES)
                    .constants("true", "false", "NULL", "nullptr")
                    .lineComment("//")
                    .blockComment("/*", "*/")
                    .docComment("/**")
                    .preprocessorPrefix('#')
                    .build();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        @Override
        public Set<String> extensions() {
            return extensions;
        }

        @Override
        public Highlighter highlighter() {
            return highlighter;
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

    private static final class CMakeLanguage implements LanguageSupport {

        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?m)#[^\\n]*", TokenType.COMMENT)
                .rule("\"(\\\\.|[^\"\\\\])*\"", TokenType.STRING)
                .rule("\\$\\{[^}]*\\}|\\$<[^>]*>", TokenType.VARIABLE)
                .rule("(?i)\\b(add_executable|add_library|add_subdirectory|cmake_minimum_required|find_package"
                        + "|include|install|option|project|set|target_compile_definitions|target_compile_options"
                        + "|target_include_directories|target_link_libraries|if|elseif|else|endif|foreach|endforeach"
                        + "|function|endfunction|macro|endmacro|message|file|list)\\b", TokenType.KEYWORD)
                .rule("\\b(PUBLIC|PRIVATE|INTERFACE|REQUIRED|QUIET|STATIC|SHARED|MODULE|CACHE|FORCE)\\b",
                        TokenType.ANNOTATION);

        @Override
        public String id() {
            return "cmake";
        }

        @Override
        public String displayName() {
            return "CMake";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("cmake");
        }

        @Override
        public Set<String> fileNames() {
            return Set.of("CMakeLists.txt");
        }

        @Override
        public Highlighter highlighter() {
            return HIGHLIGHTER;
        }

        @Override
        public String lineComment() {
            return "#";
        }

        @Override
        public String bracketPairs() {
            return "()";
        }
    }

    /**
     * clangd, taken from its GitHub release.
     *
     * <p>It reads {@code compile_commands.json}; without one it still parses a file but
     * cannot resolve project includes, which is a limitation of the tool rather than of
     * this plugin. CMake writes that file with {@code -DCMAKE_EXPORT_COMPILE_COMMANDS=ON}.
     */
    private static final class Clangd implements LanguageServerLauncher {

        private static final String VERSION = "18.1.3";

        @Override
        public String serverId() {
            return "clangd";
        }

        @Override
        public String displayName() {
            return "clangd";
        }

        private static Optional<Path> onPath() {
            String exe = WINDOWS ? "clangd.exe" : "clangd";
            String path = System.getenv("PATH");
            if (path == null) {
                return Optional.empty();
            }
            for (String dir : path.split(File.pathSeparator)) {
                Path candidate = Path.of(dir.isBlank() ? "." : dir, exe);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }

        private static Optional<Path> downloaded(Ide ide) {
            Path home = ide.downloads().toolsDir().resolve("clangd");
            if (!Files.isDirectory(home)) {
                return Optional.empty();
            }
            // The archive unpacks as clangd_<version>/bin/clangd(.exe).
            try (Stream<Path> walk = Files.walk(home, 3)) {
                return walk.filter(p -> p.getFileName().toString().equals(WINDOWS ? "clangd.exe" : "clangd"))
                        .filter(Files::isRegularFile).findFirst();
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean isInstalled(Ide ide) {
            return downloaded(ide).isPresent() || onPath().isPresent();
        }

        @Override
        public Optional<InstallRecipe> installRecipe() {
            return Optional.of(new InstallRecipe() {
                @Override
                public String description() {
                    return "Downloads clangd " + VERSION + " (about 50 MB) from the LLVM releases on GitHub.";
                }

                @Override
                public void run(Ide ide, ProgressReporter progress) throws IOException {
                    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                    String platform = os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
                    String name = "clangd-" + platform + "-" + VERSION + ".zip";
                    String url = "https://github.com/clangd/clangd/releases/download/" + VERSION + "/" + name;
                    Path home = ide.downloads().toolsDir().resolve("clangd");
                    Path archive = home.resolve(name);
                    ide.downloads().download(url, archive, progress);
                    ide.downloads().extract(archive, home, progress);
                    Files.deleteIfExists(archive);
                }
            });
        }

        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            Path exe = downloaded(ide).or(Clangd::onPath)
                    .orElseThrow(() -> new IllegalStateException("clangd is not installed"));
            return List.of(exe.toString(), "--background-index", "--clang-tidy");
        }
    }
}
