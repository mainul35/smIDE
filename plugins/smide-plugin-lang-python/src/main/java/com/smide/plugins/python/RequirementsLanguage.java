package com.smide.plugins.python;

import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.RegexHighlighter;
import com.smide.api.lang.TokenType;

import java.util.Set;

/** pip requirements files: package names, version specifiers, options and comments. */
public final class RequirementsLanguage implements LanguageSupport {

    private static final Highlighter HIGHLIGHTER = new RegexHighlighter()
            .rule("(?:^|(?<=\\s))#.*$", TokenType.COMMENT)
            // pip options such as -r other.txt, --index-url and --hash=...
            .rule("(?:^|(?<=\\s))--?[A-Za-z][\\w-]*", TokenType.KEYWORD)
            // environment markers after ';' : python_version >= "3.9"
            .rule("(?<=;)[^#\\n]*", TokenType.ANNOTATION)
            .rule("\"[^\"\\n]*\"|'[^'\\n]*'", TokenType.STRING)
            .rule("(?:==|~=|!=|<=|>=|<|>|===|@)", TokenType.OPERATOR)
            .rule("(?<=[=<>~!@])\\s*[\\w.*+!-]+", TokenType.NUMBER)
            .rule("\\[[^\\]\\n]*\\]", TokenType.ATTRIBUTE)
            .rule("^[A-Za-z0-9][\\w.-]*", TokenType.TYPE);

    @Override
    public String id() {
        return "pip-requirements";
    }

    @Override
    public String displayName() {
        return "pip requirements";
    }

    @Override
    public Set<String> extensions() {
        return Set.of();
    }

    @Override
    public Set<String> fileNames() {
        return Set.of("requirements.txt", "requirements-dev.txt", "requirements-test.txt", "constraints.txt");
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
        return "[]()";
    }

    @Override
    public String indentOpeners() {
        return "";
    }
}
