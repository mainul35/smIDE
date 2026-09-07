package com.smide.plugins.web;

import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.RegexHighlighter;
import com.smide.api.lang.TokenType;

import java.util.Optional;
import java.util.Set;

/** The web family: JavaScript, TypeScript, HTML, CSS and JSON. */
final class WebLanguages {

    private WebLanguages() {
    }

    private static final String[] JS_KEYWORDS = {
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "export", "extends", "finally", "for", "function", "get", "if", "import",
            "in", "instanceof", "let", "new", "of", "return", "set", "static", "super", "switch", "this",
            "throw", "try", "typeof", "var", "void", "while", "with", "yield"};

    private static final String[] TS_EXTRA = {
            "abstract", "any", "as", "asserts", "declare", "enum", "implements", "infer", "interface", "is",
            "keyof", "namespace", "never", "override", "private", "protected", "public", "readonly", "satisfies",
            "type", "unknown", "unique"};

    /** A common base so JavaScript and TypeScript differ only by their word lists. */
    private abstract static class ScriptLanguage implements LanguageSupport {
        private final Highlighter highlighter;
        private final LanguageServerLauncher launcher;

        ScriptLanguage(Highlighter highlighter, LanguageServerLauncher launcher) {
            this.highlighter = highlighter;
            this.launcher = launcher;
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
        public int indentSize() {
            return 2;
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.ofNullable(launcher);
        }
    }

    static final class JavaScript extends ScriptLanguage {
        JavaScript(LanguageServerLauncher launcher) {
            super(GenericLexer.builder()
                    .keywords(JS_KEYWORDS)
                    .types("Array", "Object", "String", "Number", "Boolean", "Symbol", "BigInt", "Promise", "Map",
                            "Set", "WeakMap", "WeakSet", "Date", "RegExp", "Error", "JSON", "Math", "console",
                            "document", "window", "globalThis", "process", "require", "module", "exports")
                    .constants("true", "false", "null", "undefined", "NaN", "Infinity")
                    .lineComment("//").blockComment("/*", "*/").docComment("/**")
                    .rawString("`", "`")
                    .identifierDollar(true).typeByCase(true)
                    .build(), launcher);
        }

        @Override
        public String id() {
            return "javascript";
        }

        @Override
        public String displayName() {
            return "JavaScript";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("js", "mjs", "cjs", "jsx");
        }
    }

    static final class TypeScript extends ScriptLanguage {
        TypeScript(LanguageServerLauncher launcher) {
            super(GenericLexer.builder()
                    .keywords(concat(JS_KEYWORDS, TS_EXTRA))
                    .types("Array", "Object", "String", "Number", "Boolean", "Symbol", "BigInt", "Promise", "Map",
                            "Set", "Date", "RegExp", "Error", "JSON", "Math", "console", "string", "number",
                            "boolean", "object", "symbol", "bigint", "void", "Record", "Partial", "Readonly")
                    .constants("true", "false", "null", "undefined", "NaN", "Infinity")
                    .lineComment("//").blockComment("/*", "*/").docComment("/**")
                    .rawString("`", "`")
                    .identifierDollar(true).typeByCase(true)
                    .build(), launcher);
        }

        @Override
        public String id() {
            return "typescript";
        }

        @Override
        public String displayName() {
            return "TypeScript";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("ts", "tsx", "mts", "cts");
        }

        @Override
        public String lspLanguageId() {
            return "typescript";
        }
    }

    private static Set<String> concat(String[] a, String[] b) {
        Set<String> out = new java.util.HashSet<>(Set.of(a));
        out.addAll(Set.of(b));
        return out;
    }

    static final class Html implements LanguageSupport {
        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?s)<!--.*?(-->|\\z)", TokenType.COMMENT)
                .rule("(?s)<script\\b[^>]*>.*?(</script>|\\z)", TokenType.CODE)
                .rule("(?s)<style\\b[^>]*>.*?(</style>|\\z)", TokenType.CODE)
                .rule("<!DOCTYPE[^>]*>", TokenType.PREPROCESSOR)
                .rule("</?[A-Za-z][\\w:-]*", TokenType.TAG)
                .rule("[A-Za-z_:][\\w:.-]*(?=\\s*=)", TokenType.ATTRIBUTE)
                .rule("\"[^\"]*\"|'[^']*'", TokenType.STRING)
                .rule("&[a-zA-Z#0-9]+;", TokenType.ESCAPE)
                .rule("/?>", TokenType.TAG);

        private final LanguageServerLauncher launcher;

        Html(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "html";
        }

        @Override
        public String displayName() {
            return "HTML";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("html", "htm", "xhtml", "vue", "svelte");
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
            return 2;
        }

        @Override
        public String bracketPairs() {
            return "()[]{}";
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.ofNullable(launcher);
        }
    }

    static final class Css implements LanguageSupport {
        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?s)/\\*.*?(\\*/|\\z)", TokenType.COMMENT)
                .rule("\"[^\"\\n]*\"|'[^'\\n]*'", TokenType.STRING)
                .rule("@[a-zA-Z-]+", TokenType.KEYWORD)
                .rule("[.#][A-Za-z_-][\\w-]*", TokenType.TAG)
                .rule("[$@][A-Za-z_-][\\w-]*", TokenType.VARIABLE)
                .rule("--[A-Za-z_-][\\w-]*", TokenType.VARIABLE)
                .rule("[A-Za-z-]+(?=\\s*:)", TokenType.ATTRIBUTE)
                .rule("#[0-9a-fA-F]{3,8}\\b", TokenType.NUMBER)
                .rule("\\b\\d+(\\.\\d+)?(px|em|rem|%|vh|vw|s|ms|fr|pt|deg)?\\b", TokenType.NUMBER)
                .rule("::?[a-zA-Z-]+", TokenType.FUNCTION);

        private final LanguageServerLauncher launcher;

        Css(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "css";
        }

        @Override
        public String displayName() {
            return "CSS";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("css", "scss", "sass", "less");
        }

        @Override
        public Highlighter highlighter() {
            return HIGHLIGHTER;
        }

        @Override
        public BlockComment blockComment() {
            return new BlockComment("/* ", " */");
        }

        @Override
        public int indentSize() {
            return 2;
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.ofNullable(launcher);
        }
    }

    static final class Json implements LanguageSupport {
        private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
                .rule("(?s)/\\*.*?(\\*/|\\z)", TokenType.COMMENT)
                .rule("//[^\\n]*", TokenType.COMMENT)
                .rule("\"(\\\\.|[^\"\\\\])*\"\\s*(?=:)", TokenType.ATTRIBUTE)
                .rule("\"(\\\\.|[^\"\\\\])*\"", TokenType.STRING)
                .rule("\\b(true|false|null)\\b", TokenType.KEYWORD)
                .rule("-?\\b\\d+(\\.\\d+)?([eE][-+]?\\d+)?\\b", TokenType.NUMBER)
                .rule("[{}\\[\\],:]", TokenType.PUNCTUATION);

        private final LanguageServerLauncher launcher;

        Json(LanguageServerLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return "json";
        }

        @Override
        public String displayName() {
            return "JSON";
        }

        @Override
        public Set<String> extensions() {
            return Set.of("json", "jsonc", "json5", "webmanifest");
        }

        @Override
        public Set<String> fileNames() {
            return Set.of("package.json", "tsconfig.json", "jsconfig.json", ".eslintrc", ".babelrc", ".prettierrc",
                    "composer.json", "angular.json", "nest-cli.json");
        }

        @Override
        public Highlighter highlighter() {
            return HIGHLIGHTER;
        }

        @Override
        public int indentSize() {
            return 2;
        }

        @Override
        public String bracketPairs() {
            return "{}[]";
        }

        @Override
        public Optional<LanguageServerLauncher> languageServer() {
            return Optional.ofNullable(launcher);
        }
    }
}
