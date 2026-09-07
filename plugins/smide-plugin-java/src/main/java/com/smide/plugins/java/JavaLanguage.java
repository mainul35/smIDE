package com.smide.plugins.java;

import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;

import java.util.Optional;
import java.util.Set;

/** Java source: the tokenizer and the JDT language server. */
public final class JavaLanguage implements LanguageSupport {

    private static final Highlighter LEXER = GenericLexer.builder()
            .keywords("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
                    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
                    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
                    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
                    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
                    "volatile", "while", "var", "record", "sealed", "permits", "yield", "non-sealed", "module",
                    "requires", "exports", "opens", "uses", "provides", "to", "with", "transitive", "when")
            .types("String", "Object", "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short",
                    "List", "Map", "Set", "Optional", "Collection", "Iterable", "Iterator", "Stream", "Path", "File",
                    "Exception", "RuntimeException", "Throwable", "Error", "System", "Math", "StringBuilder",
                    "Thread", "Runnable", "Class", "Void", "Number", "CharSequence", "Comparable", "Override")
            .constants("true", "false", "null")
            .lineComment("//")
            .blockComment("/*", "*/")
            .docComment("/**")
            .annotationPrefix('@')
            .tripleQuotes(true)
            .typeByCase(true)
            .build();

    private final LanguageServerLauncher launcher;

    public JavaLanguage(LanguageServerLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public String id() {
        return "java";
    }

    @Override
    public String displayName() {
        return "Java";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("java", "jav");
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
    public Optional<LanguageServerLauncher> languageServer() {
        return Optional.ofNullable(launcher);
    }
}
