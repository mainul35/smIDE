package com.smide.lang;

import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;

import java.util.Set;

/** What a file gets when no plugin claims it. */
public final class PlainTextLanguage implements LanguageSupport {

    @Override
    public String id() {
        return "plaintext";
    }

    @Override
    public String displayName() {
        return "Plain text";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("txt", "text", "log");
    }

    @Override
    public Highlighter highlighter() {
        return Highlighter.NONE;
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
