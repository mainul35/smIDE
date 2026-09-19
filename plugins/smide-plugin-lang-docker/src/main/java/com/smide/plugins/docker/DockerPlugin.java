package com.smide.plugins.docker;

import com.smide.api.Ide;
import com.smide.api.lang.FileType;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.RegexHighlighter;
import com.smide.api.lang.TokenType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.workspace.Workspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Dockerfiles, with the Dockerfile language server. Compose files are YAML. */
public final class DockerPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        // What running containers needs comes with the plugin: docker, compose and build configurations, settings.
        DockerToolchain docker = new DockerToolchain();
        context.registerToolchain(docker);
        context.registerRunConfigurationType(new DockerRunType(context.ide(), docker::locate));
        context.registerSettingsPage(new com.smide.api.lang.ToolchainSettingsPage(context.ide(), "Tools/Docker", docker));

        context.registerFileType(new FileType("dockerfile", "Dockerfile",
                Set.of("dockerfile"),
                Set.of("Dockerfile", "Containerfile", "Dockerfile.dev", "Dockerfile.prod"),
                "mdi2d-docker", false));
        context.registerLanguage(new DockerfileLanguage(new DockerServer()));
    }

    private static final class DockerfileLanguage implements LanguageSupport {

        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?m)^\\s*#[^\\n]*", TokenType.COMMENT)
                .rule("(?im)^\\s*(ADD|ARG|CMD|COPY|ENTRYPOINT|ENV|EXPOSE|FROM|HEALTHCHECK|LABEL|MAINTAINER"
                        + "|ONBUILD|RUN|SHELL|STOPSIGNAL|USER|VOLUME|WORKDIR)\\b", TokenType.KEYWORD)
                .rule("(?i)\\b(AS)\\b", TokenType.KEYWORD)
                .rule("\\$\\{[^}]*\\}|\\$[A-Za-z_]\\w*", TokenType.VARIABLE)
                .rule("\"(\\\\.|[^\"\\\\])*\"|'[^']*'", TokenType.STRING)
                .rule("--[a-z-]+", TokenType.ANNOTATION);

        private final LanguageServerLauncher launcher;

        DockerfileLanguage(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "dockerfile";
        }

        @Override
        public String displayName() {
            return "Dockerfile";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("dockerfile");
        }

        @Override
        public Set<String> fileNames() {
            return Set.of("Dockerfile", "Containerfile", "Dockerfile.dev", "Dockerfile.prod", "Dockerfile.test");
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
        public int indentSize() {
            return 4;
        }

        @Override
        public String bracketPairs() {
            return "[]";
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.of(launcher);
        }
    }

    private static final class DockerServer implements LanguageServerLauncher {

        @Override
        public String serverId() {
            return "docker-langserver";
        }

        @Override
        public String displayName() {
            return "Dockerfile Language Server";
        }

        @Override
        public boolean isInstalled(Ide ide) {
            return NpmTools.locate(ide, "docker-langserver").isPresent();
        }

        @Override
        public Optional<InstallRecipe> installRecipe() {
            return Optional.of(NpmTools.install(
                    "Installs dockerfile-language-server-nodejs with npm. Node.js must be installed.",
                    "dockerfile-language-server-nodejs"));
        }

        @Override
        public java.util.Map<String, String> environment(Ide ide, Workspace workspace) {
            // Its launcher runs "node" by name: the Node.js the IDE downloaded or was pointed at goes first.
            return NpmTools.environment(ide);
        }
        
        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            Path exe = NpmTools.locate(ide, "docker-langserver")
                    .orElseThrow(() -> new IllegalStateException("docker-langserver is not installed"));
            List<String> command = new ArrayList<>();
            if (NpmTools.WINDOWS && exe.toString().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                command.add(System.getenv().getOrDefault("COMSPEC", "cmd.exe"));
                command.add("/c");
            }
            command.add(exe.toString());
            command.add("--stdio");
            return command;
        }
    }
}
