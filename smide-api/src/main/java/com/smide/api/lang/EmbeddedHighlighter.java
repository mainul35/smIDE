package com.smide.api.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A highlighter for a language that other languages are written inside.
 *
 * <p>An HTML file is not only HTML: between {@code <script>} and {@code </script>} it is
 * JavaScript, and between {@code <style>} and {@code </style>} it is CSS, and both of
 * those are the part a reader is usually looking at. Painting them in one flat colour
 * says "this is code" to somebody who could already see that, and nothing else - no
 * keywords, no strings, no comments, in the half of the file where they matter most.
 *
 * <p>So the islands are cut out and given to the highlighter for what they actually are.
 * The opening and closing tags stay with the host language, because they are its syntax,
 * and the text in between the islands is given to the host as well. Each island's tokens
 * are shifted to where the island sits in the whole, so what comes out is one ordered,
 * non-overlapping run of tokens for the file, which is what a highlighter promises.
 *
 * <p>Nothing here is specific to HTML: the same shape serves Markdown's fenced blocks,
 * Vue's single-file components, PHP, or a template language with an expression syntax.
 */
public final class EmbeddedHighlighter implements Highlighter {

    /**
     * One kind of island.
     *
     * @param around  matches a whole island, with group 1 the opening, group 2 the body
     *                and group 3 the closing; all three are coloured separately
     * @param inside  the highlighter for the body, looked up when it is needed because a
     *                language plugin may not have started when the host is built
     */
    public record Island(Pattern around, Supplier<Highlighter> inside) {

        /** An island between an opening tag and a closing one, as HTML and XML spell them. */
        public static Island between(String open, String close, Supplier<Highlighter> inside) {
            return new Island(
                    Pattern.compile("(?s)(" + open + ")(.*?)(" + close + "|\\z)"), inside);
        }
    }

    private final Highlighter host;
    private final List<Island> islands;
    /** All the islands at once, so one pass over the text finds them in order. */
    private final Pattern anyIsland;

    public EmbeddedHighlighter(Highlighter host, List<Island> islands) {
        this.host = host;
        this.islands = List.copyOf(islands);
        StringBuilder sb = new StringBuilder();
        for (Island island : this.islands) {
            int groups = island.around().matcher("").groupCount();
            if (groups != 3) {
                throw new IllegalArgumentException("an island is three groups - opening, body,"
                        + " closing - and this one has " + groups + ": " + island.around());
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            // Each island keeps its own three groups; which one matched is worked out by
            // walking the islands and counting, the same way the alternation numbers them.
            sb.append("(?:").append(island.around().pattern()).append(')');
        }
        anyIsland = sb.length() == 0 ? null : Pattern.compile(sb.toString());
    }

    @Override
    public List<Token> tokenize(String text) {
        if (anyIsland == null) {
            return host.tokenize(text);
        }
        List<Token> out = new ArrayList<>();
        Matcher m = anyIsland.matcher(text);
        int done = 0;
        while (m.find()) {
            if (m.end() == m.start()) {
                continue;
            }
            int which = islandOf(m);
            if (which < 0) {
                continue;
            }
            int group = 1 + which * 3;
            hostPart(text, done, m.start(), out);
            hostPart(text, m.start(group), m.end(group), out);
            inner(text, m.start(group + 1), m.end(group + 1), islands.get(which), out);
            hostPart(text, m.start(group + 2), m.end(group + 2), out);
            done = m.end();
        }
        hostPart(text, done, text.length(), out);
        return out;
    }

    /** Which island matched: the first whose opening group took part. */
    private int islandOf(Matcher m) {
        for (int i = 0; i < islands.size(); i++) {
            if (m.group(1 + i * 3) != null) {
                return i;
            }
        }
        return -1;
    }

    private void hostPart(String text, int from, int to, List<Token> out) {
        if (to > from) {
            shift(host.tokenize(text.substring(from, to)), from, to, out);
        }
    }

    private void inner(String text, int from, int to, Island island, List<Token> out) {
        if (to <= from) {
            return;
        }
        Highlighter inside = island.inside().get();
        if (inside == null || inside == Highlighter.NONE) {
            // Nothing can colour it: leave it plain rather than claim it is one word.
            return;
        }
        shift(inside.tokenize(text.substring(from, to)), from, to, out);
    }

    /** Moves a slice's tokens to where the slice sits, dropping anything that escapes it. */
    private static void shift(List<Token> tokens, int from, int to, List<Token> out) {
        for (Token token : tokens) {
            int start = from + token.start();
            int end = Math.min(to, from + token.end());
            if (end > start && start >= from) {
                out.add(new Token(start, end, token.type()));
            }
        }
    }
}
