package com.smide.plugins.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading what a search engine sent back, and what a page says under its markup. */
class AskWebTest {

    @Test
    void tavilysResultsAreRead() {
        String json = """
                {"answer":"Use try-with-resources.",
                 "results":[
                   {"title":"AutoCloseable","url":"https://docs.example/ac","content":"Closes the resource."},
                   {"title":"Try","url":"https://docs.example/try","content":"The statement."}]}""";

        String read = AskWeb.readResults(json);

        assertTrue(read.contains("AutoCloseable"), read);
        assertTrue(read.contains("https://docs.example/ac"), read);
        assertTrue(read.contains("Closes the resource."), read);
        assertTrue(read.contains("https://docs.example/try"), read);
    }

    @Test
    void bravesAreTooEvenThoughTheyAreNestedDifferently() {
        String json = """
                {"web":{"results":[
                   {"title":"Gradle toolchains","url":"https://gradle.example/t",
                    "description":"Pick a JDK per project."}]}}""";

        String read = AskWeb.readResults(json);

        assertTrue(read.contains("Gradle toolchains"), read);
        assertTrue(read.contains("Pick a JDK per project."), read);
    }

    @Test
    void nothingFoundIsSaidRatherThanShownAsEmpty() {
        assertEquals("The search came back with nothing.", AskWeb.readResults("{\"results\":[]}"));
    }

    @Test
    void aPageComesBackAsWordsWithoutItsMarkup() {
        String html = """
                <html><head><style>p { color: red }</style><script>var a = 1;</script></head>
                <body><h1>Title</h1><p>First&nbsp;line &amp; more.</p><p>Second line.</p></body></html>""";

        String text = AskWeb.stripMarkup(html);

        assertTrue(text.contains("Title"), text);
        assertTrue(text.contains("First line & more."), text);
        assertTrue(text.contains("Second line."), text);
        assertFalse(text.contains("color: red"), text);
        assertFalse(text.contains("var a"), text);
        assertFalse(text.contains("<"), text);
    }

    @Test
    void withoutAKeyItSaysSoRatherThanFailingQuietly() {
        AskWeb web = new AskWeb("tavily", "");

        assertFalse(web.canSearch());
        assertTrue(web.search("anything").contains("not set up"));
        assertTrue(web.whyNotSearching().contains("search.key"));
    }
}
