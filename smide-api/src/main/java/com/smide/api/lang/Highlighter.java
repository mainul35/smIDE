package com.smide.api.lang;

import java.util.List;

/**
 * Splits text into coloured tokens. Runs off the JavaFX thread, so it must be
 * thread-safe and must not touch the scene graph.
 */
@FunctionalInterface
public interface Highlighter {

    /** Tokens in order, non-overlapping. Gaps are plain text. */
    List<Token> tokenize(String text);

    Highlighter NONE = text -> List.of();
}
