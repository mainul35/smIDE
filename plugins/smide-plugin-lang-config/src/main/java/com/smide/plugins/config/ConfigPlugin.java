package com.smide.plugins.config;

import com.smide.api.Ide;
import com.smide.api.lang.FileType;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The configuration formats: YAML, XML, TOML, properties, INI and dotenv. */
public final class ConfigPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        LanguageServerLauncher yamlServer = new NodeServer("yaml-language-server", "YAML Language Server",
                "yaml-language-server", List.of("yaml-language-server"), List.of("--stdio"));
        LanguageServerLauncher xmlServer = new LemminxServer();

        context.registerFileType(new FileType("yaml", "YAML", Set.of("yml", "yaml"), Set.of(), "fth-settings", false));
        context.registerFileType(new FileType("xml", "XML",
                Set.of("xml", "xsd", "xsl", "xslt", "fxml", "svg", "csproj", "props", "targets"),
                Set.of("pom.xml"), "fth-code", false));
        context.registerFileType(new FileType("toml", "TOML", Set.of("toml"),
                Set.of("Cargo.toml", "pyproject.toml"), "fth-settings", false));
        context.registerFileType(new FileType("properties", "Properties",
                Set.of("properties", "props"), Set.of(), "fth-sliders", false));
        context.registerFileType(new FileType("ini", "INI", Set.of("ini", "cfg", "conf", "editorconfig"),
                Set.of(".editorconfig"), "fth-sliders", false));
        context.registerFileType(new FileType("dotenv", "Environment file", Set.of("env"),
                Set.of(".env", ".env.local", ".env.example", ".env.production"), "fth-key", false));
        context.registerFileType(new FileType("ignore", "Ignore rules", Set.of("gitignore", "dockerignore"),
                Set.of(".gitignore", ".dockerignore", ".npmignore", ".gcloudignore", ".prettierignore"),
                "fth-eye-off", false));

        context.registerLanguage(new ConfigLanguages.Yaml(yamlServer));
        context.registerLanguage(new ConfigLanguages.Xml(xmlServer));
        context.registerLanguage(new ConfigLanguages.Toml());
        context.registerLanguage(new ConfigLanguages.Properties("properties", "Properties",
                Set.of("properties"), Set.of()));
        context.registerLanguage(new ConfigLanguages.Properties("ini", "INI",
                Set.of("ini", "cfg", "conf", "editorconfig"), Set.of(".editorconfig")));
        context.registerLanguage(new ConfigLanguages.Properties("dotenv", "Environment file",
                Set.of("env"), Set.of(".env", ".env.local", ".env.example", ".env.production")));
        context.registerLanguage(new IgnoreLanguage());
    }

    /** {@code .gitignore} and its relatives: comments, negations and glob patterns. */
    private static final class IgnoreLanguage implements LanguageSupport {
        private static final com.smide.api.lang.Highlighter HIGHLIGHTER = new com.smide.api.lang.RegexHighlighter()
                .rule("(?m)^\\s*#[^\\n]*", com.smide.api.lang.TokenType.COMMENT)
                .rule("(?m)^\\s*!", com.smide.api.lang.TokenType.KEYWORD)
                .rule("[*?\\[\\]]", com.smide.api.lang.TokenType.OPERATOR);

        @Override
        public String id() {
            return "ignore";
        }

        @Override
        public String displayName() {
            return "Ignore rules";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("gitignore", "dockerignore", "npmignore");
        }

        @Override
        public Set<String> fileNames() {
            return Set.of(".gitignore", ".dockerignore", ".npmignore", ".gcloudignore", ".prettierignore", ".eslintignore");
        }

        @Override
        public com.smide.api.lang.Highlighter highlighter() {
            return HIGHLIGHTER;
        }

        @Override
        public String lineComment() {
            return "#";
        }

        @Override
        public String bracketPairs() {
            return "";
        }

        @Override
        public String indentOpeners() {
            return "";
        }
    }

    /** An npm-published server, run through its {@code node_modules/.bin} launcher. */
    private record NodeServer(String serverId, String displayName, String executable, List<String> packages,
                              List<String> arguments) implements LanguageServerLauncher {

        @Override
        public String serverId() {
            return serverId;
        }

        @Override
        public String displayName() {
            return displayName;
        }

        @Override
        public boolean isInstalled(Ide ide) {
            return NpmTools.locate(ide, executable).isPresent();
        }

        @Override
        public Optional<InstallRecipe> installRecipe() {
            return Optional.of(NpmTools.install("Installs " + String.join(", ", packages)
                    + " with npm. Node.js must be installed.", packages.toArray(String[]::new)));
        }

        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            Path exe = NpmTools.locate(ide, executable)
                    .orElseThrow(() -> new IllegalStateException(displayName + " is not installed"));
            List<String> command = new java.util.ArrayList<>();
            if (NpmTools.WINDOWS && exe.toString().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                command.add(System.getenv().getOrDefault("COMSPEC", "cmd.exe"));
                command.add("/c");
            }
            command.add(exe.toString());
            command.addAll(arguments);
            return command;
        }
    }

    /**
     * LemMinX, the Eclipse XML language server: one uber jar, run with {@code java -jar}.
     * It gives schema-aware completion in {@code pom.xml}, which is where an XML server
     * earns its keep in a Java project.
     */
    private static final class LemminxServer implements LanguageServerLauncher {

        private static final String VERSION = "0.28.0";
        private static final String URL = "https://repo.eclipse.org/content/repositories/lemminx-releases/"
                + "org/eclipse/lemminx/org.eclipse.lemminx/" + VERSION
                + "/org.eclipse.lemminx-" + VERSION + "-uber.jar";

        @Override
        public String serverId() {
            return "lemminx";
        }

        @Override
        public String displayName() {
            return "XML Language Server";
        }

        private static Path jar(Ide ide) {
            return ide.downloads().toolsDir().resolve("lemminx").resolve("lemminx-" + VERSION + "-uber.jar");
        }

        @Override
        public boolean isInstalled(Ide ide) {
            return Files.isRegularFile(jar(ide));
        }

        @Override
        public Optional<InstallRecipe> installRecipe() {
            return Optional.of(new InstallRecipe() {
                @Override
                public String description() {
                    return "Downloads the Eclipse LemMinX XML server (about 20 MB).";
                }

                @Override
                public void run(Ide ide, ProgressReporter progress) throws IOException {
                    ide.downloads().download(URL, jar(ide), progress);
                }
            });
        }

        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                    NpmTools.WINDOWS ? "java.exe" : "java").toString();
            return List.of(java, "-jar", jar(ide).toString());
        }
    }
}
