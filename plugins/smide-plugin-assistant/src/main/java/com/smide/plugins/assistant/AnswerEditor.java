package com.smide.plugins.assistant;

import com.smide.api.Ide;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.Token;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where the developer writes their answer.
 *
 * <p>The same editor component the IDE uses, coloured by whichever language the question
 * asked for: answering a SQL problem in a box with no highlighting is a worse exercise
 * than answering it in something that looks like the editor next door. A theory answer
 * gets the Markdown colouring, since it is Markdown that is being written.
 *
 * <p>Highlighting runs on the typing thread here, which the editor proper would not do -
 * an answer is a few hundred characters, and a background pass plus a generation counter
 * would be more machinery than the whole panel.
 */
final class AnswerEditor extends VirtualizedScrollPane<CodeArea> {

    /** Question languages that are not the IDE's own id for the same thing. */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("sql", "sql"), Map.entry("java", "java"), Map.entry("python", "py"),
            Map.entry("css", "css"), Map.entry("scss", "scss"), Map.entry("html", "html"),
            Map.entry("javascript", "js"), Map.entry("js", "js"), Map.entry("typescript", "ts"),
            Map.entry("ts", "ts"), Map.entry("kotlin", "kt"), Map.entry("go", "go"),
            Map.entry("golang", "go"), Map.entry("rust", "rs"), Map.entry("bash", "sh"),
            Map.entry("shell", "sh"), Map.entry("sh", "sh"), Map.entry("csharp", "cs"),
            Map.entry("cs", "cs"), Map.entry("cpp", "cpp"), Map.entry("c", "c"),
            Map.entry("yaml", "yaml"), Map.entry("json", "json"), Map.entry("xml", "xml"),
            Map.entry("text", "md"), Map.entry("markdown", "md"), Map.entry("theory", "md"));

    private final Ide ide;
    private final CodeArea area;
    private Highlighter highlighter = Highlighter.NONE;

    AnswerEditor(Ide ide) {
        super(new CodeArea());
        this.ide = ide;
        this.area = getContent();
        area.getStyleClass().add("code-area");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.setWrapText(false);
        area.textProperty().addListener((o, was, now) -> restyle(now));
    }

    CodeArea area() {
        return area;
    }

    String text() {
        return area.getText();
    }

    /** Points the editor at a language and replaces whatever it held with {@code initial}. */
    void reset(String language, String initial) {
        highlighter = languageFor(language).map(LanguageSupport::highlighter).orElse(Highlighter.NONE);
        area.setWrapText("md".equals(EXTENSIONS.getOrDefault(
                language == null ? "" : language.toLowerCase(Locale.ROOT), "")));
        area.replaceText(initial == null ? "" : initial);
        area.moveTo(area.getLength());
        restyle(area.getText());
    }

    void setEditable(boolean editable) {
        area.setEditable(editable);
    }

    void dispose() {
        area.dispose();
    }

    private Optional<LanguageSupport> languageFor(String language) {
        if (language == null || language.isBlank()) {
            return Optional.empty();
        }
        String name = language.toLowerCase(Locale.ROOT).strip();
        Optional<LanguageSupport> byId = ide.languages().byId(name);
        if (byId.isPresent()) {
            return byId;
        }
        String extension = EXTENSIONS.get(name);
        // By a name a language plugin would recognise: the file never exists.
        return extension == null ? Optional.empty()
                : ide.languages().forFile(Path.of("answer." + extension));
    }

    private void restyle(String text) {
        if (highlighter == Highlighter.NONE || text.isEmpty()) {
            return;
        }
        try {
            area.setStyleSpans(0, spans(text.length(), highlighter.tokenize(text)));
        } catch (RuntimeException e) {
            // A tokenizer that trips over half-typed input leaves the text uncoloured,
            // which is the correct amount of consequence for that.
        }
    }

    /** Tokens as the one span list a CodeArea takes; gaps carry no class. */
    private static StyleSpans<Collection<String>> spans(int length, List<Token> tokens) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int at = 0;
        for (Token token : tokens) {
            int start = Math.max(token.start(), at);
            int end = Math.min(token.end(), length);
            if (end <= start) {
                continue;
            }
            if (start > at) {
                builder.add(List.of(), start - at);
            }
            builder.add(List.of(token.type().styleClass()), end - start);
            at = end;
        }
        if (at < length) {
            builder.add(List.of(), length - at);
        }
        return builder.create();
    }
}
