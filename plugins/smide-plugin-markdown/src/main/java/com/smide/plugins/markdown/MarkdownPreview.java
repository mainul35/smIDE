package com.smide.plugins.markdown;

import com.mdviewer.MainController;
import com.mdviewer.service.DiagramService;
import com.mdviewer.service.MarkdownService;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The rendered document: a {@link WebView} holding MDViewer's preview "shell", into which
 * each render is pushed as a body rather than as a page load, so the reader's scroll
 * position survives typing.
 *
 * <p>The shell is a compact re-implementation of MDViewer's, with the same contract
 * between the page and Java:
 * <ul>
 *   <li>{@code __mdSetBody(html)} replaces the body, then runs mermaid, the chart
 *       compiler and highlight.js over it;</li>
 *   <li>{@code __mdSetDiagram(id, svg)} fills the PlantUML placeholder {@code div#id}
 *       (class {@code mdv-diagram mdv-diagram-pending}) that {@link MarkdownService}
 *       emitted, making it a plain {@code mdv-diagram};</li>
 *   <li>{@code __mdSetTheme("dark"|"light")} sets {@code data-theme} on the root element,
 *       which is what MDViewer's stylesheet switches on;</li>
 *   <li>{@code __mdScrollY()}, {@code __mdScrollTo(y)} and {@code __mdKeepScroll(y)} are
 *       the scroll bookkeeping: a late diagram re-anchors the page unless the reader has
 *       scrolled since the body went in.</li>
 * </ul>
 * The mermaid, mdchart and highlight.js sources are evaluated with {@code executeScript}
 * rather than linked in {@code <script>} tags: the page has no base URL to resolve a tag
 * against, and the minified bundles contain {@code </script>} inside string literals.
 */
final class MarkdownPreview {

    private static final String HLJS_RESOURCE =
            "/META-INF/resources/webjars/highlightjs/11.11.1/highlight.min.js";

    private final WebView view = new WebView();

    {
        // A WebView's default preferred size is 800x600 and it does not shrink below it,
        // which squeezes the Project panel to a sliver when a preview is open. The
        // preview is always inside a layout that gives it a size, so let it be told one.
        view.setMinSize(0, 0);
        view.setPrefSize(0, 0);
    }
    private final WebEngine engine = view.getEngine();
    private final DiagramService diagramService;
    private final Consumer<Path> openLocal;
    private final Consumer<String> openExternal;

    private MarkdownService.Result current = new MarkdownService.Result("", List.of());
    private boolean ready;
    private boolean dark;
    private int generation;
    private double pendingScrollY;
    private boolean disposed;

    /**
     * @param diagramService renders PlantUML placeholders off the FX thread
     * @param openLocal      called with the target of a clicked {@code file:} link
     * @param openExternal   called with a clicked web or mailto link
     */
    MarkdownPreview(DiagramService diagramService, boolean dark,
                    Consumer<Path> openLocal, Consumer<String> openExternal) {
        this.diagramService = diagramService;
        this.dark = dark;
        this.openLocal = openLocal;
        this.openExternal = openExternal;
        view.setContextMenuEnabled(false);

        engine.getLoadWorker().stateProperty().addListener((obs, was, now) -> {
            if (now == Worker.State.SUCCEEDED) {
                inject("/js/mermaid.min.js", "mermaid");
                run("mermaid.initialize({startOnLoad:false, securityLevel:'strict', theme:'default', "
                        + "fontFamily:'Segoe UI, Helvetica, Arial, sans-serif'});");
                inject("/js/mdchart.js", "mdchart");
                inject(HLJS_RESOURCE, "highlight.js");
                run("hljs.configure({ignoreUnescapedHTML:true, languages:[]});");
                ready = true;
                applyTheme();
                applyBody();
                pushDiagrams(current.diagrams(), generation);
            } else if (now == Worker.State.FAILED || now == Worker.State.CANCELLED) {
                ready = false;
            }
        });

        // A click on a link must not navigate the preview away from the document: cancel
        // the load, hand the URL to whoever handles it, and rebuild the shell (the
        // cancelled navigation may already have torn the page down).
        engine.locationProperty().addListener((obs, was, now) -> {
            if (now == null || disposed) {
                return;
            }
            String loc = now.toLowerCase(Locale.ROOT);
            if (loc.startsWith("file:")) {
                rememberScroll();
                engine.getLoadWorker().cancel();
                Path target = localPath(now);
                Platform.runLater(() -> {
                    loadShell();
                    if (target != null) {
                        openLocal.accept(target);
                    }
                });
            } else if (loc.startsWith("http://") || loc.startsWith("https://") || loc.startsWith("mailto:")) {
                rememberScroll();
                engine.getLoadWorker().cancel();
                openExternal.accept(now);
                Platform.runLater(this::loadShell);
            }
        });

        loadShell();
    }

    Node node() {
        return view;
    }

    /** The last result shown, for the exporter. */
    MarkdownService.Result current() {
        return current;
    }

    // ------------------------------------------------------------------ content

    /**
     * Shows a new render. The body is replaced in place, the page is scrolled back to
     * where it was, and PlantUML placeholders are filled as their SVG arrives; results
     * from an earlier render are dropped when a newer one has been shown since.
     */
    void show(MarkdownService.Result result) {
        if (disposed) {
            return;
        }
        rememberScroll();
        current = result;
        int gen = ++generation;
        applyBody();
        pushDiagrams(result.diagrams(), gen);
    }

    private void applyBody() {
        if (!ready) {
            return; // Re-applied by the load listener once the shell is up.
        }
        try {
            engine.executeScript("window.__mdSetBody(" + js(current.html()) + ");");
            engine.executeScript("window.__mdScrollTo(" + (long) pendingScrollY + ");");
        } catch (RuntimeException e) {
            // The hooks are gone (page replaced): rebuild the shell, which re-applies.
            loadShell();
        }
    }

    /** Cached diagrams land at once so an unchanged one never flickers; the rest arrive later. */
    private void pushDiagrams(List<MarkdownService.Diagram> diagrams, int gen) {
        for (MarkdownService.Diagram diagram : diagrams) {
            String cached = diagramService.cached(diagram.source());
            if (cached != null) {
                setDiagram(diagram.id(), cached, gen);
                continue;
            }
            diagramService.renderAsync(diagram.source())
                    .thenAccept(svg -> Platform.runLater(() -> setDiagram(diagram.id(), svg, gen)));
        }
    }

    private void setDiagram(String id, String svg, int gen) {
        if (gen != generation || !ready || disposed) {
            return; // The document moved on while this diagram was rendering.
        }
        try {
            engine.executeScript("window.__mdSetDiagram(" + js(id) + "," + js(svg) + ");");
            // A diagram is far taller than its placeholder; re-anchor unless the reader
            // has scrolled since the body was applied.
            engine.executeScript("window.__mdKeepScroll(" + (long) pendingScrollY + ");");
        } catch (RuntimeException e) {
            // Page replaced mid-flight; the reload path pushes the diagrams again.
        }
    }

    // -------------------------------------------------------------------- theme

    void setDark(boolean dark) {
        this.dark = dark;
        applyTheme();
    }

    private void applyTheme() {
        if (!ready) {
            return;
        }
        try {
            engine.executeScript("window.__mdSetTheme(" + js(dark ? "dark" : "light") + ");");
        } catch (RuntimeException e) {
            // Re-applied when the shell reloads.
        }
    }

    // ------------------------------------------------------------------- scroll

    private void rememberScroll() {
        double y = readScrollY();
        if (y >= 0) {
            pendingScrollY = y;
        }
    }

    /** The page's current offset, or -1 when it cannot be queried. */
    private double readScrollY() {
        if (!ready) {
            return -1;
        }
        try {
            Object value = engine.executeScript("window.__mdScrollY()");
            return value instanceof Number n ? n.doubleValue() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------- links

    /** The path behind a {@code file:} URL, without its fragment; null if unusable. */
    private static Path localPath(String url) {
        try {
            int hash = url.indexOf('#');
            String bare = hash >= 0 ? url.substring(0, hash) : url;
            return Path.of(URI.create(bare)).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------- shell

    private void loadShell() {
        if (disposed) {
            return;
        }
        ready = false;
        // Single-argument overload on purpose: loadContent(html, "text/html; charset=UTF-8")
        // is rejected by WebKit and leaves the page permanently blank.
        engine.loadContent(shell());
    }

    void dispose() {
        disposed = true;
        ready = false;
        try {
            engine.getLoadWorker().cancel();
            engine.loadContent("");
        } catch (RuntimeException ignored) {
            // Already torn down.
        }
    }

    private void inject(String resource, String name) {
        try (InputStream in = MarkdownService.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("smIDE markdown: " + name + " not found at " + resource);
                return;
            }
            engine.executeScript(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE markdown: " + name + " unavailable - " + e);
        }
    }

    private void run(String script) {
        try {
            engine.executeScript(script);
        } catch (RuntimeException e) {
            // The library it configures did not load; the page works without it.
        }
    }

    /** The shell page: MDViewer's stylesheet plus the hooks above. Loaded once per editor. */
    static String shell() {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>\n"
                + MainController.previewCss()
                + "\n</style><script>\n" + SHELL_JS + "\n</script></head><body></body></html>";
    }

    private static final String SHELL_JS = """
            window.__mdSetTheme = function (theme) {
              document.documentElement.setAttribute('data-theme', theme);
            };
            /* Scroll bookkeeping: __mdScrolled records whether the reader moved the page
               themselves, so a diagram arriving late can restore the intended position
               without fighting someone who has already scrolled on. */
            window.__mdScrolled = false;
            window.__mdSuppress = false;
            window.addEventListener('scroll', function () {
              if (!window.__mdSuppress) { window.__mdScrolled = true; }
            });
            window.__mdScrollY = function () {
              return window.pageYOffset || document.documentElement.scrollTop || 0;
            };
            window.__mdScrollTo = function (y) {
              window.__mdSuppress = true;
              window.scrollTo(0, y);
              window.__mdScrolled = false;
              setTimeout(function () { window.__mdSuppress = false; }, 0);
            };
            window.__mdKeepScroll = function (y) {
              if (!window.__mdScrolled) { window.__mdScrollTo(y); }
            };
            window.__mdRunMermaid = function () {
              if (!window.mermaid) { return; }
              try {
                var p = mermaid.run({ querySelector: '.mermaid' });
                if (p && p.catch) { p.catch(function () {}); }
              } catch (e) {}
            };
            window.__mdRunCharts = function () {
              if (!window.MdChart) { return; }
              try { MdChart.renderAll(document); } catch (e) {}
            };
            window.__mdHighlight = function () {
              if (!window.hljs) { return; }
              var blocks = document.querySelectorAll('.mdv-code pre code[class*="language-"]');
              for (var i = 0; i < blocks.length; i++) {
                var block = blocks[i];
                if (block.getAttribute('data-highlighted') === 'yes') { continue; }
                try {
                  hljs.highlightElement(block);
                } catch (e) {
                  block.setAttribute('data-highlighted', 'yes');
                }
              }
            };
            window.__mdSetBody = function (html) {
              document.body.innerHTML = html;
              window.__mdRunMermaid();
              window.__mdRunCharts();
              window.__mdHighlight();
            };
            window.__mdSetDiagram = function (id, svg) {
              var el = document.getElementById(id);
              if (!el) { return; }
              el.className = 'mdv-diagram';
              el.innerHTML = svg;
            };
            """;

    /** Escapes a Java string into a double-quoted JavaScript string literal. */
    static String js(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // Control characters, and U+2028/U+2029 which JS treats as line ends.
                    if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static boolean isMarkdown(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && MarkdownLanguage.EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }
}
