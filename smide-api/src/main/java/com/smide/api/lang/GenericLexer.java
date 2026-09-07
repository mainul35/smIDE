package com.smide.api.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A configurable tokenizer for the C family and its relatives: keywords, types, line and
 * block comments, strings with escapes, numbers, annotations and preprocessor lines.
 *
 * <p>Most language plugins need nothing more than an instance of this with their own
 * word lists. It is a hand-written scanner rather than one big regex because a regex
 * over a whole file re-matches from the start on every keystroke and cannot tell a
 * string from a comment that contains a quote.
 */
public final class GenericLexer implements Highlighter {

    public static final class Builder {
        private Set<String> keywords = Set.of();
        private Set<String> types = Set.of();
        private Set<String> constants = Set.of();
        private String lineComment;
        private String blockCommentStart;
        private String blockCommentEnd;
        private String docCommentStart;
        private String stringQuotes = "\"";
        private String charQuotes = "'";
        private String rawStringStart;
        private String rawStringEnd;
        private boolean tripleQuotes;
        private char annotationPrefix;
        private char preprocessorPrefix;
        private boolean hashComment;
        private boolean functionCalls = true;
        private boolean identifierDollar;
        private boolean typeByCase;

        public Builder keywords(String... words) {
            keywords = Set.of(words);
            return this;
        }

        public Builder keywords(Set<String> words) {
            keywords = Set.copyOf(words);
            return this;
        }

        public Builder types(String... words) {
            types = Set.of(words);
            return this;
        }

        public Builder constants(String... words) {
            constants = Set.of(words);
            return this;
        }

        public Builder lineComment(String prefix) {
            lineComment = prefix;
            return this;
        }

        public Builder blockComment(String start, String end) {
            blockCommentStart = start;
            blockCommentEnd = end;
            return this;
        }

        /** A block comment opener that marks documentation, e.g. {@code /**}. */
        public Builder docComment(String start) {
            docCommentStart = start;
            return this;
        }

        public Builder stringQuotes(String quotes) {
            stringQuotes = quotes;
            return this;
        }

        public Builder charQuotes(String quotes) {
            charQuotes = quotes;
            return this;
        }

        /** A raw string form with no escapes, e.g. Go's backticks or Rust's {@code r"..."} is not covered. */
        public Builder rawString(String start, String end) {
            rawStringStart = start;
            rawStringEnd = end;
            return this;
        }

        /** Python and Kotlin style {@code """ ... """}. */
        public Builder tripleQuotes(boolean enabled) {
            tripleQuotes = enabled;
            return this;
        }

        public Builder annotationPrefix(char prefix) {
            annotationPrefix = prefix;
            return this;
        }

        public Builder preprocessorPrefix(char prefix) {
            preprocessorPrefix = prefix;
            return this;
        }

        /** Treat {@code #} to end of line as a comment (shell, Python, YAML). */
        public Builder hashComment(boolean enabled) {
            hashComment = enabled;
            return this;
        }

        /** Colour {@code name(} as a function call. */
        public Builder functionCalls(boolean enabled) {
            functionCalls = enabled;
            return this;
        }

        public Builder identifierDollar(boolean enabled) {
            identifierDollar = enabled;
            return this;
        }

        /** Colour capitalised identifiers as types, as Java and Kotlin conventions allow. */
        public Builder typeByCase(boolean enabled) {
            typeByCase = enabled;
            return this;
        }

        public GenericLexer build() {
            return new GenericLexer(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final Builder c;

    private GenericLexer(Builder builder) {
        this.c = builder;
    }

    @Override
    public List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char ch = text.charAt(i);

            // Comments -------------------------------------------------------
            if (c.docCommentStart != null && text.startsWith(c.docCommentStart, i)
                    && c.blockCommentEnd != null) {
                int end = text.indexOf(c.blockCommentEnd, i + c.docCommentStart.length());
                end = end < 0 ? n : end + c.blockCommentEnd.length();
                out.add(new Token(i, end, TokenType.DOC_COMMENT));
                i = end;
                continue;
            }
            if (c.blockCommentStart != null && text.startsWith(c.blockCommentStart, i)) {
                int end = text.indexOf(c.blockCommentEnd, i + c.blockCommentStart.length());
                end = end < 0 ? n : end + c.blockCommentEnd.length();
                out.add(new Token(i, end, TokenType.COMMENT));
                i = end;
                continue;
            }
            if (c.lineComment != null && text.startsWith(c.lineComment, i)) {
                int end = lineEnd(text, i);
                out.add(new Token(i, end, TokenType.COMMENT));
                i = end;
                continue;
            }
            if (c.hashComment && ch == '#') {
                int end = lineEnd(text, i);
                out.add(new Token(i, end, TokenType.COMMENT));
                i = end;
                continue;
            }

            // Preprocessor ---------------------------------------------------
            if (c.preprocessorPrefix != 0 && ch == c.preprocessorPrefix && atLineStart(text, i)) {
                int end = lineEnd(text, i);
                out.add(new Token(i, end, TokenType.PREPROCESSOR));
                i = end;
                continue;
            }

            // Strings --------------------------------------------------------
            if (c.tripleQuotes && (text.startsWith("\"\"\"", i) || text.startsWith("'''", i))) {
                String q = text.substring(i, i + 3);
                int end = text.indexOf(q, i + 3);
                end = end < 0 ? n : end + 3;
                out.add(new Token(i, end, TokenType.STRING));
                i = end;
                continue;
            }
            if (c.rawStringStart != null && text.startsWith(c.rawStringStart, i)) {
                int end = text.indexOf(c.rawStringEnd, i + c.rawStringStart.length());
                end = end < 0 ? n : end + c.rawStringEnd.length();
                out.add(new Token(i, end, TokenType.STRING));
                i = end;
                continue;
            }
            if (c.stringQuotes.indexOf(ch) >= 0 || c.charQuotes.indexOf(ch) >= 0) {
                int end = scanQuoted(text, i, ch, out);
                i = end;
                continue;
            }

            // Annotations ----------------------------------------------------
            if (c.annotationPrefix != 0 && ch == c.annotationPrefix && i + 1 < n
                    && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                int end = i + 1;
                while (end < n && (Character.isJavaIdentifierPart(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                out.add(new Token(i, end, TokenType.ANNOTATION));
                i = end;
                continue;
            }

            // Numbers --------------------------------------------------------
            if (Character.isDigit(ch) || (ch == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1))
                    && (i == 0 || !isIdent(text.charAt(i - 1))))) {
                int end = scanNumber(text, i);
                out.add(new Token(i, end, TokenType.NUMBER));
                i = end;
                continue;
            }

            // Identifiers and keywords --------------------------------------
            if (isIdentStart(ch)) {
                int end = i + 1;
                while (end < n && isIdent(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(i, end);
                TokenType type = null;
                if (c.keywords.contains(word)) {
                    type = TokenType.KEYWORD;
                } else if (c.types.contains(word)) {
                    type = TokenType.TYPE;
                } else if (c.constants.contains(word)) {
                    type = TokenType.CONSTANT;
                } else if (c.functionCalls && nextNonSpace(text, end) == '(') {
                    type = TokenType.FUNCTION;
                } else if (c.typeByCase && Character.isUpperCase(word.charAt(0)) && !allUpper(word)) {
                    type = TokenType.TYPE;
                } else if (c.typeByCase && allUpper(word) && word.length() > 1) {
                    type = TokenType.CONSTANT;
                }
                if (type != null) {
                    out.add(new Token(i, end, type));
                }
                i = end;
                continue;
            }

            // Operators and punctuation -------------------------------------
            if ("(){}[];,.".indexOf(ch) >= 0) {
                out.add(new Token(i, i + 1, TokenType.PUNCTUATION));
                i++;
                continue;
            }
            if ("+-*/%=<>!&|^~?:".indexOf(ch) >= 0) {
                int end = i + 1;
                while (end < n && "+-*/%=<>!&|^~?:".indexOf(text.charAt(end)) >= 0 && end - i < 3) {
                    end++;
                }
                out.add(new Token(i, end, TokenType.OPERATOR));
                i = end;
                continue;
            }
            i++;
        }
        return out;
    }

    private int scanQuoted(String text, int start, char quote, List<Token> out) {
        int n = text.length();
        int i = start + 1;
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < n) {
                out.add(new Token(start, i, TokenType.STRING));
                int escEnd = i + 2;
                if (text.charAt(i + 1) == 'u') {
                    escEnd = Math.min(n, i + 6);
                }
                out.add(new Token(i, escEnd, TokenType.ESCAPE));
                start = escEnd;
                i = escEnd;
                continue;
            }
            if (ch == quote) {
                out.add(new Token(start, i + 1, TokenType.STRING));
                return i + 1;
            }
            if (ch == '\n') {
                // An unterminated string stops at the line end so one missing quote does
                // not paint the rest of the file.
                out.add(new Token(start, i, TokenType.STRING));
                return i;
            }
            i++;
        }
        out.add(new Token(start, n, TokenType.STRING));
        return n;
    }

    private static int scanNumber(String text, int start) {
        int n = text.length();
        int i = start;
        if (text.charAt(i) == '0' && i + 1 < n && "xXbBoO".indexOf(text.charAt(i + 1)) >= 0) {
            i += 2;
            while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
            return i;
        }
        while (i < n && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        if (i < n && text.charAt(i) == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1))) {
            i++;
            while (i < n && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
        }
        if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
                j++;
            }
            if (j < n && Character.isDigit(text.charAt(j))) {
                i = j;
                while (i < n && Character.isDigit(text.charAt(i))) {
                    i++;
                }
            }
        }
        while (i < n && "fFdDlLuUn".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }

    private boolean isIdentStart(char ch) {
        return Character.isLetter(ch) || ch == '_' || (c.identifierDollar && ch == '$');
    }

    private boolean isIdent(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || (c.identifierDollar && ch == '$');
    }

    private static boolean allUpper(String word) {
        boolean sawLetter = false;
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (Character.isLetter(ch)) {
                sawLetter = true;
                if (!Character.isUpperCase(ch)) {
                    return false;
                }
            }
        }
        return sawLetter;
    }

    private static char nextNonSpace(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != ' ' && ch != '\t') {
                return ch;
            }
        }
        return 0;
    }

    private static int lineEnd(String text, int from) {
        int end = text.indexOf('\n', from);
        return end < 0 ? text.length() : end;
    }

    private static boolean atLineStart(String text, int i) {
        for (int j = i - 1; j >= 0; j--) {
            char ch = text.charAt(j);
            if (ch == '\n') {
                return true;
            }
            if (ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return true;
    }
}
