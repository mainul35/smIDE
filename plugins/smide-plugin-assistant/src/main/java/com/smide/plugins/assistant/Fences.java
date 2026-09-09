package com.smide.plugins.assistant;

import com.smide.api.Ide;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What the word after a fence means.
 *
 * <p>A model writes ```` ```sql ```` and the IDE knows about a language called sql through
 * a plugin registered for {@code .sql} files. Turning the first into the second is a
 * lookup table of the names that do not match, and it is wanted in two places - the answer
 * editor, which colours what is being written, and the transcript, which colours what came
 * back - so it lives here rather than in either of them.
 */
final class Fences {

    /** Fence words that are not the extension a language plugin registered for. */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("sql", "sql"), Map.entry("java", "java"), Map.entry("python", "py"),
            Map.entry("py", "py"), Map.entry("css", "css"), Map.entry("scss", "scss"),
            Map.entry("html", "html"), Map.entry("xhtml", "html"),
            Map.entry("javascript", "js"), Map.entry("js", "js"), Map.entry("jsx", "jsx"),
            Map.entry("typescript", "ts"), Map.entry("ts", "ts"), Map.entry("tsx", "tsx"),
            Map.entry("kotlin", "kt"), Map.entry("kt", "kt"), Map.entry("go", "go"),
            Map.entry("golang", "go"), Map.entry("rust", "rs"), Map.entry("rs", "rs"),
            Map.entry("bash", "sh"), Map.entry("shell", "sh"), Map.entry("sh", "sh"),
            Map.entry("zsh", "sh"), Map.entry("console", "sh"), Map.entry("csharp", "cs"),
            Map.entry("cs", "cs"), Map.entry("cpp", "cpp"), Map.entry("c++", "cpp"),
            Map.entry("c", "c"), Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"),
            Map.entry("json", "json"), Map.entry("xml", "xml"), Map.entry("toml", "toml"),
            Map.entry("properties", "properties"), Map.entry("dockerfile", "Dockerfile"),
            Map.entry("docker", "Dockerfile"), Map.entry("groovy", "gradle"),
            Map.entry("text", "md"), Map.entry("markdown", "md"), Map.entry("md", "md"),
            Map.entry("theory", "md"));

    private Fences() {
    }

    /** The fence word, tidied: lower case, no attributes, empty when there was none. */
    static String name(String info) {
        if (info == null) {
            return "";
        }
        String word = info.strip();
        int space = word.indexOf(' ');
        if (space > 0) {
            word = word.substring(0, space);
        }
        return word.toLowerCase(Locale.ROOT);
    }

    /** The language the IDE knows by that name, if any language plugin claims it. */
    static Optional<LanguageSupport> language(Ide ide, String fence) {
        String name = name(fence);
        if (ide == null || name.isEmpty()) {
            return Optional.empty();
        }
        Optional<LanguageSupport> byId = ide.languages().byId(name);
        if (byId.isPresent()) {
            return byId;
        }
        String extension = EXTENSIONS.get(name);
        if (extension == null) {
            return Optional.empty();
        }
        // By a file name a language plugin would recognise. The file never exists.
        String file = extension.contains(".") || Character.isUpperCase(extension.charAt(0))
                ? extension : "snippet." + extension;
        return ide.languages().forFile(Path.of(file));
    }

    /** The colouring for a fence word, or {@link Highlighter#NONE} when nothing claims it. */
    static Highlighter highlighter(Ide ide, String fence) {
        return language(ide, fence).map(LanguageSupport::highlighter).orElse(Highlighter.NONE);
    }

    /** The name to put on a block, which is the language's own if the IDE has one. */
    static String label(Ide ide, String fence) {
        String name = name(fence);
        if (name.isEmpty()) {
            return "";
        }
        return language(ide, fence).map(LanguageSupport::displayName).orElse(name);
    }
}
