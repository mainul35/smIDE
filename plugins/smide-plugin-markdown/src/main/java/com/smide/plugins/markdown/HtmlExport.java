package com.smide.plugins.markdown;

import com.mdviewer.MainController;
import com.mdviewer.service.DiagramService;
import com.mdviewer.service.MarkdownService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the preview as one self-contained HTML file: MDViewer's stylesheet inlined,
 * PlantUML placeholders replaced by their rendered SVG, and the mermaid, chart and
 * highlight.js bundles embedded so those blocks render when the file is opened in a
 * browser. Runs off the FX thread; the caller hands over the render result.
 */
final class HtmlExport {

    private static final String HLJS_RESOURCE =
            "/META-INF/resources/webjars/highlightjs/11.11.1/highlight.min.js";

    private HtmlExport() {
    }

    /**
     * @param result   the render to export
     * @param title    the document title for the {@code <title>} element
     * @param dark     which theme to bake into the root element
     * @param diagrams renders any diagram not already cached; waits up to 30 s each
     */
    static void write(Path target, MarkdownService.Result result, String title, boolean dark,
                      DiagramService diagrams) throws IOException {
        String body = result.html();
        for (MarkdownService.Diagram diagram : result.diagrams()) {
            String svg = diagrams.cached(diagram.source());
            if (svg == null) {
                try {
                    svg = diagrams.renderAsync(diagram.source()).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    svg = null; // Leave the placeholder; the rest of the document still exports.
                }
            }
            if (svg != null) {
                body = fillPlaceholder(body, diagram.id(), svg);
            }
        }

        StringBuilder html = new StringBuilder(body.length() + 4_000_000);
        html.append("<!DOCTYPE html>\n<html data-theme=\"").append(dark ? "dark" : "light").append("\">\n")
                .append("<head>\n<meta charset=\"utf-8\">\n<title>").append(escape(title)).append("</title>\n")
                .append("<style>\n").append(MainController.previewCss()).append("\n</style>\n</head>\n<body>\n")
                .append(body).append("\n");
        appendScript(html, "/js/mermaid.min.js");
        appendScript(html, "/js/mdchart.js");
        appendScript(html, HLJS_RESOURCE);
        html.append("<script>\n").append(BOOTSTRAP).append("</script>\n</body>\n</html>\n");

        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, html.toString(), StandardCharsets.UTF_8);
    }

    /** Replaces the pending placeholder {@code div#id} with the finished diagram. */
    private static String fillPlaceholder(String html, String id, String svg) {
        Pattern p = Pattern.compile("<div\\b[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>[^<]*</div>");
        Matcher m = p.matcher(html);
        if (!m.find()) {
            return html;
        }
        return html.substring(0, m.start()) + "<div class=\"mdv-diagram\">" + svg + "</div>" + html.substring(m.end());
    }

    /**
     * Embeds a bundled script. The minified sources contain {@code </script>} inside
     * string literals, which would end the tag early; {@code <\/script>} is the same
     * text to JavaScript and invisible to the HTML parser.
     */
    private static void appendScript(StringBuilder html, String resource) {
        try (InputStream in = MarkdownService.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            html.append("<script>\n").append(source.replace("</script", "<\\/script")).append("\n</script>\n");
        } catch (IOException e) {
            // The block stays as text, which is what MDViewer does without the script too.
        }
    }

    private static final String BOOTSTRAP = """
            (function () {
              if (window.mermaid) {
                try {
                  mermaid.initialize({startOnLoad:false, securityLevel:'strict', theme:'default',
                    fontFamily:'Segoe UI, Helvetica, Arial, sans-serif'});
                  var p = mermaid.run({ querySelector: '.mermaid' });
                  if (p && p.catch) { p.catch(function () {}); }
                } catch (e) {}
              }
              if (window.MdChart) {
                try { MdChart.renderAll(document); } catch (e) {}
              }
              if (window.hljs) {
                try {
                  hljs.configure({ignoreUnescapedHTML:true, languages:[]});
                  var blocks = document.querySelectorAll('.mdv-code pre code[class*="language-"]');
                  for (var i = 0; i < blocks.length; i++) {
                    try { hljs.highlightElement(blocks[i]); } catch (e) {}
                  }
                } catch (e) {}
              }
            })();
            """;

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
