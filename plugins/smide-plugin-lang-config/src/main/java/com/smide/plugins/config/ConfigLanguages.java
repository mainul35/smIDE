package com.smide.plugins.config;

import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.RegexHighlighter;
import com.smide.api.lang.TokenType;

import java.util.Optional;
import java.util.Set;

/** YAML, XML, TOML, properties, INI and dotenv: the files that configure everything else. */
final class ConfigLanguages {

    private ConfigLanguages() {
    }

    static final class Yaml implements LanguageSupport {
        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?m)#[^\\n]*", TokenType.COMMENT)
                .rule("(?m)^---\\s*$|^\\.\\.\\.\\s*$", TokenType.PREPROCESSOR)
                .rule("\"(\\\\.|[^\"\\\\])*\"|'([^']|'')*'", TokenType.STRING)
                .rule("(?m)^\\s*-?\\s*[A-Za-z_][\\w.-]*(?=\\s*:)", TokenType.ATTRIBUTE)
                .rule("\\$\\{[^}]*\\}|\\$[A-Za-z_]\\w*", TokenType.VARIABLE)
                .rule("&[A-Za-z_][\\w-]*|\\*[A-Za-z_][\\w-]*", TokenType.ANNOTATION)
                .rule("(?<=:\\s)(true|false|null|yes|no|on|off)\\b", TokenType.KEYWORD)
                .rule("(?<=:\\s)-?\\d+(\\.\\d+)?\\b", TokenType.NUMBER)
                .rule("(?m)^\\s*-\\s", TokenType.PUNCTUATION);

        private final LanguageServerLauncher launcher;

        Yaml(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "yaml";
        }

        @Override
        public String displayName() {
            return "YAML";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("yml", "yaml");
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
            return 2;
        }

        /** YAML is indentation-sensitive, so nothing auto-closes and nothing auto-indents. */
        @Override
        public String bracketPairs() {
            return "[]{}";
        }

        @Override
        public String indentOpeners() {
            return "";
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.ofNullable(launcher);
        }
    }

    static final class Xml implements LanguageSupport {
        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?s)<!--.*?(-->|\\z)", TokenType.COMMENT)
                .rule("(?s)<!\\[CDATA\\[.*?(\\]\\]>|\\z)", TokenType.STRING)
                .rule("<\\?[^?]*\\?>", TokenType.PREPROCESSOR)
                .rule("<!DOCTYPE[^>]*>", TokenType.PREPROCESSOR)
                .rule("</?[A-Za-z_][\\w:.-]*", TokenType.TAG)
                .rule("[A-Za-z_:][\\w:.-]*(?=\\s*=)", TokenType.ATTRIBUTE)
                .rule("\"[^\"]*\"|'[^']*'", TokenType.STRING)
                .rule("&[a-zA-Z#0-9]+;", TokenType.ESCAPE)
                .rule("\\$\\{[^}]*\\}", TokenType.VARIABLE)
                .rule("/?>", TokenType.TAG);

        private final LanguageServerLauncher launcher;

        Xml(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "xml";
        }

        @Override
        public String displayName() {
            return "XML";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("xml", "xsd", "xsl", "xslt", "fxml", "svg", "pom", "csproj", "props", "targets", "plist");
        }

        @Override
        public Set<String> fileNames() {
            return Set.of("pom.xml", "web.xml", "persistence.xml", "logback.xml", "settings.xml");
        }

        @Override
        public Highlighter highlighter() {
            return HIGHLIGHTER;
        }

        @Override
        public BlockComment blockComment() {
            return new BlockComment("<!-- ", " -->");
        }

        @Override
        public int indentSize() {
            return 4;
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.ofNullable(launcher);
        }
    }

    static final class Toml implements LanguageSupport {
        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?m)#[^\\n]*", TokenType.COMMENT)
                .rule("(?m)^\\s*\\[\\[?[^\\]]+\\]\\]?", TokenType.HEADING)
                .rule("\"\"\"(?s).*?\"\"\"|'''(?s).*?'''", TokenType.STRING)
                .rule("\"(\\\\.|[^\"\\\\])*\"|'[^']*'", TokenType.STRING)
                .rule("(?m)^\\s*[A-Za-z_][\\w.-]*(?=\\s*=)", TokenType.ATTRIBUTE)
                .rule("\\b(true|false)\\b", TokenType.KEYWORD)
                .rule("\\b\\d{4}-\\d{2}-\\d{2}([Tt][\\d:.+Zz-]+)?\\b", TokenType.NUMBER)
                .rule("-?\\b\\d[\\d_]*(\\.\\d+)?([eE][-+]?\\d+)?\\b", TokenType.NUMBER);

        @Override
        public String id() {
            return "toml";
        }

        @Override
        public String displayName() {
            return "TOML";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("toml");
        }

        @Override
        public Set<String> fileNames() {
            return Set.of("Cargo.toml", "pyproject.toml", "Pipfile", "poetry.lock");
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
            return 2;
        }
    }

    /** Java properties and INI files, which differ only in whether sections appear. */
    static final class Properties implements LanguageSupport {
        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?m)^\\s*[#;][^\\n]*", TokenType.COMMENT)
                .rule("(?m)^\\s*\\[[^\\]]+\\]", TokenType.HEADING)
                .rule("(?m)^\\s*[A-Za-z_][\\w.$-]*(?=\\s*[=:])", TokenType.ATTRIBUTE)
                .rule("\\$\\{[^}]*\\}", TokenType.VARIABLE)
                .rule("(?m)[=:]", TokenType.OPERATOR);

        private final String id;
        private final String displayName;
        private final Set<String> extensions;
        private final Set<String> fileNames;

        Properties(String id, String displayName, Set<String> extensions, Set<String> fileNames) {
            this.id = id;
            this.displayName = displayName;
            this.extensions = extensions;
            this.fileNames = fileNames;
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
        public Set<String> fileNames() {
            return fileNames;
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
            return "";
        }

        @Override
        public String indentOpeners() {
            return "";
        }
    }
}
