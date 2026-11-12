package com.smide.plugins.web;

import com.smide.api.lang.Highlighter;
import com.smide.api.lang.Token;
import com.smide.api.lang.TokenType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an HTML file actually looks like.
 *
 * <p>Reported as colouring that "doesn't work well" in a file of this shape: a page with
 * its script and its style in it. Two things were wrong. The whole of {@code <script>} to
 * {@code </script>} was painted one flat colour - which is most of the file, and the part
 * being read - and the markup's own rules were reading each other's colours, so a doctype
 * came out as a string and an attribute name as a tag.
 */
class WebLanguagesTest {

    private static final WebLanguages.Html HTML = new WebLanguages.Html(null);
    private static final WebLanguages.Css CSS = new WebLanguages.Css(null);

    private static final String PAGE = """
            <!DOCTYPE html>
            <!-- a note -->
            <div class="panel" id='x'>&amp;</div>
            <style>
              .panel { color: #ff0000; margin: 4px; }
            </style>
            <script>
              // counting
              const n = 42;
              let s = "hi";
            </script>
            """;

    private static TokenType typeOf(Highlighter highlighter, String source, String text) {
        int at = source.indexOf(text);
        assertEquals(true, at >= 0, "the sample does not contain " + text);
        for (Token token : highlighter.tokenize(source)) {
            if (token.start() <= at && token.end() >= at + text.length()) {
                return token.type();
            }
        }
        return null;
    }

    private static TokenType inPage(String text) {
        return typeOf(HTML.highlighter(), PAGE, text);
    }

    @Test
    void theMarkupIsColouredAsMarkup() {
        assertEquals(TokenType.PREPROCESSOR, inPage("<!DOCTYPE html>"));
        assertEquals(TokenType.COMMENT, inPage("<!-- a note -->"));
        assertEquals(TokenType.TAG, inPage("<div"));
        assertEquals(TokenType.ATTRIBUTE, inPage("class"));
        assertEquals(TokenType.STRING, inPage("\"panel\""));
        assertEquals(TokenType.STRING, inPage("'x'"));
        assertEquals(TokenType.ESCAPE, inPage("&amp;"));
    }

    @Test
    void whatIsInsideAScriptIsJavaScript() {
        assertEquals(TokenType.COMMENT, inPage("// counting"));
        assertEquals(TokenType.KEYWORD, inPage("const"));
        assertEquals(TokenType.NUMBER, inPage("42"));
        assertEquals(TokenType.STRING, inPage("\"hi\""));
    }

    @Test
    void andWhatIsInsideAStyleIsCss() {
        assertEquals(TokenType.ATTRIBUTE, inPage("color"));
        assertEquals(TokenType.NUMBER, inPage("4px"));
        assertEquals(TokenType.TAG, inPage(".panel"));
    }

    @Test
    void theScriptAndStyleTagsThemselvesStayHtml() {
        assertEquals(TokenType.TAG, inPage("<script"));
        assertEquals(TokenType.TAG, inPage("</script"));
        assertEquals(TokenType.TAG, inPage("<style"));
    }

    @Test
    void aHexColourIsAColourWhicheverDigitItStartsWith() {
        // #ff0000 begins with a letter and was being taken by the rule for #id selectors,
        // while #4a9eff - the same kind of thing - fell through to the number rule.
        String css = "a { color: #ff0000; background: #4a9eff; border-color: #fff; }";

        assertEquals(TokenType.NUMBER, typeOf(CSS.highlighter(), css, "#ff0000"));
        assertEquals(TokenType.NUMBER, typeOf(CSS.highlighter(), css, "#4a9eff"));
        assertEquals(TokenType.NUMBER, typeOf(CSS.highlighter(), css, "#fff"));
    }

    @Test
    void anIdSelectorIsStillASelector() {
        String css = "#panel, .row { color: red; }";

        assertEquals(TokenType.TAG, typeOf(CSS.highlighter(), css, "#panel"));
        assertEquals(TokenType.TAG, typeOf(CSS.highlighter(), css, ".row"));
    }

    @Test
    void jsonKeysAndValuesAreToldApart() {
        WebLanguages.Json json = new WebLanguages.Json(null);
        String source = "{ \"name\": \"smIDE\", \"n\": 42, \"ok\": true }";

        assertEquals(TokenType.ATTRIBUTE, typeOf(json.highlighter(), source, "\"name\""));
        assertEquals(TokenType.STRING, typeOf(json.highlighter(), source, "\"smIDE\""));
        assertEquals(TokenType.NUMBER, typeOf(json.highlighter(), source, "42"));
        assertEquals(TokenType.KEYWORD, typeOf(json.highlighter(), source, "true"));
    }
}
