package com.smide.plugins.shell;

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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Shell, PowerShell and batch scripts. */
public final class ShellPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        context.registerFileType(new FileType("shell", "Shell script",
                Set.of("sh", "bash", "zsh", "ksh", "fish"),
                Set.of(".bashrc", ".bash_profile", ".zshrc", ".profile", ".bash_aliases"), "fth-terminal", false));
        context.registerFileType(new FileType("powershell", "PowerShell script",
                Set.of("ps1", "psm1", "psd1"), Set.of(), "fth-terminal", false));
        context.registerFileType(new FileType("batch", "Batch script",
                Set.of("bat", "cmd"), Set.of(), "fth-terminal", false));

        context.registerLanguage(new ShellLanguage(new BashServer()));
        context.registerLanguage(new PowerShellLanguage());
        context.registerLanguage(new BatchLanguage());
    }

    private static final class ShellLanguage implements LanguageSupport {

        private static final Highlighter LEXER = GenericLexer.builder()
                .keywords("if", "then", "elif", "else", "fi", "for", "while", "until", "do", "done", "case",
                        "esac", "function", "in", "select", "return", "break", "continue", "local", "export",
                        "readonly", "declare", "typeset", "unset", "shift", "source", "trap", "set", "eval",
                        "exec", "exit", "test")
                .types("echo", "printf", "read", "cd", "pwd", "ls", "cat", "grep", "sed", "awk", "curl", "git",
                        "docker", "kubectl", "npm", "mvn", "java", "python", "make")
                .constants("true", "false")
                .hashComment(true)
                .identifierDollar(true)
                .functionCalls(false)
                .build();

        private final LanguageServerLauncher launcher;

        ShellLanguage(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "shellscript";
        }

        @Override
        public String displayName() {
            return "Shell script";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("sh", "bash", "zsh", "ksh", "fish");
        }

        @Override
        public Set<String> fileNames() {
            return Set.of(".bashrc", ".bash_profile", ".zshrc", ".profile", ".bash_aliases");
        }

        @Override
        public Highlighter highlighter() {
            return LEXER;
        }

        @Override
        public String lineComment() {
            return "#";
        }

        @Override
        public int indentSize() {
            return 2;
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.of(launcher);
        }
    }

    private static final class PowerShellLanguage implements LanguageSupport {

        private static final Highlighter LEXER = GenericLexer.builder()
                .keywords("begin", "break", "catch", "class", "continue", "data", "do", "dynamicparam", "else",
                        "elseif", "end", "enum", "exit", "filter", "finally", "for", "foreach", "from",
                        "function", "hidden", "if", "in", "param", "process", "return", "static", "switch",
                        "throw", "trap", "try", "until", "using", "while")
                .types("Get-ChildItem", "Set-Location", "Write-Output", "Write-Host", "Get-Content",
                        "Set-Content", "New-Item", "Remove-Item", "Select-Object", "Where-Object",
                        "ForEach-Object", "Invoke-WebRequest", "Start-Process", "Test-Path")
                .constants("$true", "$false", "$null")
                .hashComment(true)
                .blockComment("<#", "#>")
                .identifierDollar(true)
                .typeByCase(false)
                .build();

        @Override
        public String id() {
            return "powershell";
        }

        @Override
        public String displayName() {
            return "PowerShell";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("ps1", "psm1", "psd1");
        }

        @Override
        public Highlighter highlighter() {
            return LEXER;
        }

        @Override
        public String lineComment() {
            return "#";
        }

        @Override
        public BlockComment blockComment() {
            return new BlockComment("<# ", " #>");
        }

        @Override
        public int indentSize() {
            return 4;
        }
    }

    private static final class BatchLanguage implements LanguageSupport {

        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?im)^\\s*(rem|::)[^\\n]*", TokenType.COMMENT)
                .rule("(?i)\\b(call|echo|else|endlocal|exit|for|goto|if|pause|popd|pushd|set|setlocal|shift|start"
                        + "|cd|copy|del|dir|md|move|rd|ren|type|where)\\b", TokenType.KEYWORD)
                .rule("%[\\w~]+%|%%?[A-Za-z]\\b|![\\w]+!", TokenType.VARIABLE)
                .rule("(?m)^\\s*:[A-Za-z_]\\w*", TokenType.HEADING)
                .rule("\"[^\"\\n]*\"", TokenType.STRING)
                .rule("(?i)\\b(nul|con|prn)\\b", TokenType.CONSTANT);

        @Override
        public String id() {
            return "batch";
        }

        @Override
        public String displayName() {
            return "Batch script";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("bat", "cmd");
        }

        @Override
        public Highlighter highlighter() {
            return HIGHLIGHTER;
        }

        @Override
        public String lineComment() {
            return "REM ";
        }

        @Override
        public String bracketPairs() {
            return "()";
        }
    }

    /** bash-language-server, published on npm. */
    private static final class BashServer implements LanguageServerLauncher {

        @Override
        public String serverId() {
            return "bash-language-server";
        }

        @Override
        public String displayName() {
            return "Bash Language Server";
        }

        @Override
        public boolean isInstalled(Ide ide) {
            return NpmTools.locate(ide, "bash-language-server").isPresent();
        }

        @Override
        public Optional<InstallRecipe> installRecipe() {
            return Optional.of(NpmTools.install(
                    "Installs bash-language-server with npm. Node.js must be installed.",
                    "bash-language-server"));
        }

        @Override
        public List<String> command(Ide ide, Workspace workspace) {
            Path exe = NpmTools.locate(ide, "bash-language-server")
                    .orElseThrow(() -> new IllegalStateException("bash-language-server is not installed"));
            List<String> command = new ArrayList<>();
            if (NpmTools.WINDOWS && exe.toString().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                command.add(System.getenv().getOrDefault("COMSPEC", "cmd.exe"));
                command.add("/c");
            }
            command.add(exe.toString());
            command.add("start");
            return command;
        }
    }
}
