package com.smide.plugins.assistant;

import com.mdviewer.MainController;
import com.mdviewer.service.MarkdownService;
import com.smide.api.ui.Theme;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.nio.file.Path;

/**
 * A panel that shows Markdown, the way the Markdown editor shows it.
 *
 * <p>The same renderer and the same stylesheet as the preview, so a review reads like a
 * document rather than like log output: headings, lists, tables and fenced code all come
 * out looking like the rest of the IDE.
 *
 * <p>The page is loaded once and written into afterwards. Loading a page per answer would
 * lose the scroll position and flash white between them, which - while a long review is
 * streaming in a sentence at a time - is the whole reading experience.
 */
final class MarkdownView extends StackPane {

    private final WebView view = new WebView();
    private final WebEngine engine = view.getEngine();
    private final MarkdownService markdown = new MarkdownService();
    private final Theme theme;

    private boolean ready;
    private String pending = "";
    /** Set while a reply streams, so the view can follow the text down the page. */
    private boolean follow = true;

    MarkdownView(Theme theme) {
        this.theme = theme;
        getChildren().add(view);
        view.setContextMenuEnabled(false);
        engine.getLoadWorker().stateProperty().addListener((o, was, now) -> {
            if (now == Worker.State.SUCCEEDED) {
                ready = true;
                applyTheme();
                write(pending);
            } else if (now == Worker.State.FAILED || now == Worker.State.CANCELLED) {
                ready = false;
            }
        });
        // Single-argument overload on purpose: the two-argument one is rejected by WebKit
        // and leaves the page permanently blank.
        engine.loadContent(shell());
        theme.darkProperty().addListener((o, was, now) -> applyTheme());
    }

    /** Replaces what is shown. Markdown; anything not Markdown still reads as text. */
    void show(String text) {
        pending = text == null ? "" : text;
        if (ready) {
            write(pending);
        }
    }

    /** Whether new text scrolls the page down with it. Off once the reader scrolls up. */
    void setFollow(boolean follow) {
        this.follow = follow;
    }

    void clear() {
        show("");
    }

    private void write(String text) {
        try {
            MarkdownService.Result rendered = markdown.render(text, Path.of("."));
            engine.executeScript("window.__show(" + js(rendered.html()) + ", "
                    + (follow ? "true" : "false") + ");");
        } catch (RuntimeException e) {
            // A half-written fenced block during streaming can upset the parser; the next
            // fragment almost always fixes it, so this is not worth reporting.
        }
    }

    private void applyTheme() {
        if (!ready) {
            return;
        }
        try {
            engine.executeScript("window.__theme(" + js(theme.isDark() ? "dark" : "light") + ");");
        } catch (RuntimeException e) {
            // The page has gone; nothing to theme.
        }
    }

    private static String js(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '<' -> out.append("\\x3c"); // Never closes the script element early.
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String shell() {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>\n"
                + MainController.previewCss()
                + "\n" + OVERRIDES + "\n</style><script>\n" + SCRIPT + "\n</script></head>"
                + "<body><div id=\"content\"></div></body></html>";
    }

    /**
     * The preview is a printed page with a wide margin; this is a side panel.
     *
     * <p>Headings especially. The preview sizes them for a page a reader has chosen to
     * look at, and at a third of that width "Code smells" alone fills two lines and reads
     * like a poster. Here they are barely larger than the text and carry a rule instead,
     * which is enough to find them by while scrolling.
     */
    private static final String OVERRIDES = """
            body { padding: 12px 16px 28px; font-size: 13.5px; line-height: 1.6; }
            #content > :first-child { margin-top: 0; }
            h1 { font-size: 1.2em; margin: 18px 0 8px; }
            h2 {
              font-size: 1.1em; margin: 18px 0 8px; padding-bottom: 4px;
              border-bottom: 1px solid var(--rule);
            }
            h3 { font-size: 1em; margin: 14px 0 6px; }
            h4, h5, h6 { font-size: 0.95em; margin: 12px 0 4px; }
            li { margin: 4px 0; }
            li code { font-size: 0.92em; }
            blockquote { color: var(--ink-soft); }
            .placeholder { color: var(--ink-soft); font-style: italic; }
            """;

    private static final String SCRIPT = """
            window.__theme = function (name) {
              document.documentElement.setAttribute('data-theme', name);
            };
            /* Follows the text while a reply streams, and stops the moment the reader
               scrolls up to re-read something - which is exactly when being dragged back
               to the bottom is most annoying. */
            window.__pinned = true;
            window.addEventListener('scroll', function () {
              var bottom = window.innerHeight + window.pageYOffset
                    >= document.body.scrollHeight - 40;
              window.__pinned = bottom;
            });
            window.__show = function (html, follow) {
              document.getElementById('content').innerHTML = html;
              if (follow && window.__pinned) { window.scrollTo(0, document.body.scrollHeight); }
            };
            """;
}
