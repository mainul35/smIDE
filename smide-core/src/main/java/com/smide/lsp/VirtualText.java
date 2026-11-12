package com.smide.lsp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One language's share of a file, as a document in its own right.
 *
 * <p>A language server will check CSS if it is given CSS. It will not check the CSS in
 * the middle of an HTML page, because as far as it is concerned the page is one document
 * in one language. So the page is handed over twice: once as itself, and once as a copy
 * in which everything that is not CSS has been blanked out.
 *
 * <p>Blanked, not cut out. Every character outside the stretch becomes a space and every
 * line ending is kept, so the copy has the same lines of the same lengths as the page
 * does - which means a complaint about line 4, column 12 of the copy is a complaint about
 * line 4, column 12 of the page, and nothing has to be translated on the way back. Cutting
 * the stretch out would have been less to send and a map to maintain for every edit.
 *
 * <p>The opening and closing tags are blanked along with the rest: {@code <style>} is not
 * CSS, and a server given it reports a syntax error on the first line of every page.
 */
final class VirtualText {

    private VirtualText() {
    }

    /**
     * The page with everything but those stretches blanked out, or null when it has none.
     *
     * @param around matches a whole region, group 2 being the part written in that language
     */
    static String of(String page, Pattern around) {
        if (page == null || page.isEmpty()) {
            return null;
        }
        char[] out = new char[page.length()];
        for (int i = 0; i < out.length; i++) {
            char c = page.charAt(i);
            out[i] = c == '\n' || c == '\r' ? c : ' ';
        }
        boolean any = false;
        Matcher m = around.matcher(page);
        while (m.find()) {
            int from = m.start(2);
            int to = m.end(2);
            if (to <= from) {
                continue;
            }
            any = true;
            page.getChars(from, to, out, from);
        }
        return any ? new String(out) : null;
    }
}
