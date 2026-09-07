package com.smide.api.lang;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the editor needs to know about a language, contributed by a plugin.
 *
 * <p>The core never special-cases a language: highlighting, comment toggling, bracket
 * pairs, indentation and the language server all come from here.
 */
public interface LanguageSupport {

    /** Stable id, also the LSP {@code languageId} unless {@link #lspLanguageId()} says otherwise. */
    String id();

    String displayName();

    /** Lower-case extensions without the dot. */
    Set<String> extensions();

    /** Exact file names that belong to this language, e.g. {@code Makefile}. */
    default Set<String> fileNames() {
        return Set.of();
    }

    /** The tokenizer the editor colours with. */
    Highlighter highlighter();

    /** {@code //}, {@code #}, {@code --}; null when the language has none. */
    default String lineComment() {
        return null;
    }

    /** {@code /*} and {@code *}{@code /}; null when the language has none. */
    default BlockComment blockComment() {
        return null;
    }

    default int indentSize() {
        return 4;
    }

    default boolean useTabs() {
        return false;
    }

    /** Pairs the editor closes automatically and matches. */
    default String bracketPairs() {
        return "(){}[]";
    }

    /** Characters that open a nested block, for auto-indent after Enter. */
    default String indentOpeners() {
        return "{([";
    }

    default String lspLanguageId() {
        return id();
    }

    default Optional<LanguageServerLauncher> languageServer() {
        return Optional.empty();
    }

    default boolean matches(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (fileNames().contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && extensions().contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    record BlockComment(String start, String end) {
    }
}
