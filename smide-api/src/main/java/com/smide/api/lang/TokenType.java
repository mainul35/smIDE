package com.smide.api.lang;

import java.util.Locale;

/**
 * The kinds of token the theme knows how to colour. Each maps to a style class
 * {@code tok-<name>} in the editor stylesheet, so a plugin never chooses colours.
 */
public enum TokenType {
    KEYWORD,
    TYPE,
    STRING,
    NUMBER,
    COMMENT,
    DOC_COMMENT,
    ANNOTATION,
    OPERATOR,
    PUNCTUATION,
    FUNCTION,
    VARIABLE,
    CONSTANT,
    TAG,
    ATTRIBUTE,
    PREPROCESSOR,
    ESCAPE,
    REGEX,
    HEADING,
    BOLD,
    ITALIC,
    LINK,
    CODE,
    INVALID,
    TEXT;

    public String styleClass() {
        return "tok-" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
