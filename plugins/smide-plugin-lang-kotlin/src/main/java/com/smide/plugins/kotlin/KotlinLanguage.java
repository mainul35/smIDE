package com.smide.plugins.kotlin;

import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;

import java.util.Optional;
import java.util.Set;

/** Kotlin source and scripts ({@code .kt}, {@code .kts}): the tokenizer and the language server. */
public final class KotlinLanguage implements LanguageSupport {

    /**
     * Hard keywords, modifier keywords and the soft keywords that read as keywords in
     * practice. Soft keywords that double as everyday identifiers ({@code field},
     * {@code file}, {@code param}, {@code receiver}) are left out so they keep their
     * plain colour outside the rare positions where they are keywords.
     */
    private static final Highlighter LEXER = GenericLexer.builder()
            .keywords(
                    // hard keywords
                    "as", "break", "class", "continue", "do", "else", "for", "fun", "if", "in",
                    "interface", "is", "object", "package", "return", "super", "this", "throw",
                    "try", "typealias", "typeof", "val", "var", "when", "while",
                    // soft keywords
                    "by", "catch", "constructor", "delegate", "dynamic", "finally", "get", "import",
                    "init", "set", "where",
                    // modifier keywords
                    "abstract", "actual", "annotation", "companion", "const", "crossinline", "data",
                    "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
                    "lateinit", "noinline", "open", "operator", "out", "override", "private",
                    "protected", "public", "reified", "sealed", "suspend", "tailrec", "value",
                    "vararg")
            .types("Any", "Unit", "Nothing", "Boolean", "Byte", "Short", "Int", "Long", "Float",
                    "Double", "Char", "String", "CharSequence", "Number", "Array", "BooleanArray",
                    "ByteArray", "ShortArray", "IntArray", "LongArray", "FloatArray", "DoubleArray",
                    "CharArray", "UByte", "UShort", "UInt", "ULong", "List", "MutableList", "Set",
                    "MutableSet", "Map", "MutableMap", "Collection", "MutableCollection", "Iterable",
                    "Iterator", "Sequence", "Pair", "Triple", "Result", "Throwable", "Exception",
                    "Comparable", "Enum", "Lazy", "Regex", "StringBuilder", "Function")
            .constants("true", "false", "null")
            .lineComment("//")
            .blockComment("/*", "*/")
            .docComment("/**")
            .annotationPrefix('@')
            .tripleQuotes(true)
            .typeByCase(true)
            .build();

    private final LanguageServerLauncher launcher = new KotlinLanguageServer();

    @Override
    public String id() {
        return "kotlin";
    }

    @Override
    public String displayName() {
        return "Kotlin";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("kt", "kts");
    }

    @Override
    public Highlighter highlighter() {
        return LEXER;
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
        return 4;
    }

    @Override
    public Optional<LanguageServerLauncher> languageServer() {
        return Optional.of(launcher);
    }
}
