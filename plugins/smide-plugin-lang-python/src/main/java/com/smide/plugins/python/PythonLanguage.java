package com.smide.plugins.python;

import com.smide.api.lang.GenericLexer;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageServerLauncher;
import com.smide.api.lang.LanguageSupport;

import java.util.Optional;
import java.util.Set;

/** Python source ({@code .py}, {@code .pyi}, {@code .pyw}): the tokenizer and pyright. */
public final class PythonLanguage implements LanguageSupport {

    /**
     * Every reserved word of Python 3. The soft keywords {@code match}, {@code case} and
     * {@code type} are omitted: they are far more often plain names ({@code re.match(...)}).
     * Built-in types and exception classes get the type colour; built-in functions such as
     * {@code print} and {@code len} are coloured as calls by the lexer itself.
     */
    private static final Highlighter LEXER = GenericLexer.builder()
            .keywords("and", "as", "assert", "async", "await", "break", "class", "continue", "def",
                    "del", "elif", "else", "except", "finally", "for", "from", "global", "if",
                    "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
                    "return", "try", "while", "with", "yield")
            .types("int", "float", "complex", "str", "bytes", "bytearray", "memoryview", "bool",
                    "list", "tuple", "dict", "set", "frozenset", "object", "type", "range", "slice",
                    "property", "classmethod", "staticmethod", "super", "enumerate", "zip", "map",
                    "filter", "reversed", "iter", "BaseException", "Exception", "ArithmeticError",
                    "AssertionError", "AttributeError", "EOFError", "ImportError", "IndexError",
                    "KeyError", "KeyboardInterrupt", "LookupError", "MemoryError", "NameError",
                    "NotImplementedError", "OSError", "OverflowError", "RecursionError",
                    "RuntimeError", "StopIteration", "SyntaxError", "SystemExit", "TypeError",
                    "ValueError", "ZeroDivisionError", "FileNotFoundError", "PermissionError",
                    "TimeoutError", "UnicodeError", "Warning", "DeprecationWarning")
            .constants("True", "False", "None", "Ellipsis", "NotImplemented", "self", "cls",
                    "__name__", "__file__", "__doc__", "__all__", "__init__", "__main__")
            .hashComment(true)
            .tripleQuotes(true)
            .stringQuotes("\"'")
            .charQuotes("")
            .annotationPrefix('@')
            .typeByCase(true)
            .build();

    private final LanguageServerLauncher launcher = new PyrightLanguageServer();

    @Override
    public String id() {
        return "python";
    }

    @Override
    public String displayName() {
        return "Python";
    }

    @Override
    public Set<String> extensions() {
        return Set.of("py", "pyi", "pyw");
    }

    @Override
    public Highlighter highlighter() {
        return LEXER;
    }

    @Override
    public String lineComment() {
        return "#";
    }

    @Override
    public int indentSize() {
        return 4;
    }

    /** A colon ends a compound statement header, so Enter after it indents the body. */
    @Override
    public String indentOpeners() {
        return "{([:";
    }

    @Override
    public Optional<LanguageServerLauncher> languageServer() {
        return Optional.of(launcher);
    }
}
