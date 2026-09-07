package com.smide.plugins.markdown;

import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.RegexHighlighter;
import com.smide.api.lang.TokenType;

import java.util.Set;

/**
 * Markdown for the raw editor. Rules are ordered so that fenced code wins over
 * everything inside it and inline markers never swallow a whole line.
 */
public final class MarkdownLanguage implements LanguageSupport {

    /** Shared with the editor provider, which decides which files it claims. */
    public static final Set<String> EXTENSIONS = Set.of("md", "markdown", "mdx", "mdown", "mkd");

    private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
            // Fenced blocks first: whatever is inside them is code, not markup.
            .rule("(?s)```.*?(```|\\z)", TokenType.CODE)
            .rule("(?s)~~~.*?(~~~|\\z)", TokenType.CODE)
            .rule("(?m)^ {0,3}#{1,6} .*$", TokenType.HEADING)
            .rule("(?m)^ {0,3}(=+|-{2,})\\s*$", TokenType.HEADING)
            .rule("(?m)^ {0,3}> ?.*$", TokenType.COMMENT)
            .rule("(?s)<!--.*?(-->|\\z)", TokenType.COMMENT)
            .rule("!?\\[[^\\]\\n]*\\]\\([^)\\n]*\\)", TokenType.LINK)
            .rule("(?m)^ {0,3}\\[[^\\]\\n]+\\]:\\s*\\S+", TokenType.LINK)
            .rule("<[/!]?[A-Za-z][\\w-]*(\\s[^<>]*)?/?>", TokenType.TAG)
            .rule("`[^`\\n]+`", TokenType.CODE)
            .rule("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__", TokenType.BOLD)
            .rule("(?<![*\\w])\\*[^*\\n]+\\*(?!\\*)|(?<![_\\w])_[^_\\n]+_(?!_)", TokenType.ITALIC)
            .rule("~~[^~\\n]+~~", TokenType.STRING)
            .rule("(?m)^ {0,6}([-*+]|\\d+[.)])\\s", TokenType.PUNCTUATION)
            .rule("(?m)^ {0,3}(\\*{3,}|-{3,}|_{3,})\\s*$", TokenType.PUNCTUATION)
            .rule("(?m)^\\|.*\\|\\s*$", TokenType.PUNCTUATION);

    @Override
    public String id() {
        return "markdown";
    }

    @Override
    public String displayName() {
        return "Markdown";
    }

    @Override
    public Set<String> extensions() {
        return EXTENSIONS;
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
        return "()[]";
    }

    @Override
    public String indentOpeners() {
        return "";
    }
}
