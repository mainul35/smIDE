package com.smide.editor;

import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * The bracket on the other end of the one beside the caret.
 *
 * <p>Put the caret against a bracket and the editor says where the other one is. In a
 * line like {@code str(userinfo.get(settings.sso_claim_id()) or "")} the question is
 * which {@code (} the {@code )} under the caret belongs to, and counting them by eye is
 * exactly the kind of work an editor should be doing.
 *
 * <p>Brackets inside a string or a comment do not count, and the caller says which
 * positions those are - the highlighter has already worked it out for the colouring, so
 * nothing is parsed twice. Without that, {@code print("(")} would pair the bracket in the
 * string with the one in the code and point at the wrong place with confidence.
 */
final class Brackets {

    /**
     * A bracket and its partner.
     *
     * @param at      the bracket the caret is beside
     * @param partner the other end, or -1 when there is none
     */
    record Match(int at, int partner) {

        boolean found() {
            return partner >= 0;
        }
    }

    /** Beyond this many characters of scanning, the answer is not worth the wait. */
    private static final int FAR_ENOUGH = 500_000;

    private Brackets() {
    }

    /**
     * The match for whichever bracket the caret is beside, if it is beside one.
     *
     * <p>The character before the caret is looked at first and the one after it second,
     * which is what makes typing a closing bracket show you what you just closed.
     *
     * @param pairs openers and closers in turn, as a language lists them: {@code "()[]{}"}
     * @param skip  true for a position that is inside a string or a comment
     */
    static Optional<Match> beside(String text, int caret, String pairs, IntPredicate skip) {
        if (pairs == null || pairs.isEmpty() || text == null) {
            return Optional.empty();
        }
        for (int at : new int[] {caret - 1, caret}) {
            if (at < 0 || at >= text.length() || skip.test(at)) {
                continue;
            }
            int index = pairs.indexOf(text.charAt(at));
            if (index < 0) {
                continue;
            }
            boolean opening = index % 2 == 0;
            char mine = pairs.charAt(index);
            char theirs = pairs.charAt(opening ? index + 1 : index - 1);
            return Optional.of(new Match(at, scan(text, at, mine, theirs, opening, skip)));
        }
        return Optional.empty();
    }

    /** Walks outwards counting its own kind, so a nested pair does not end the search. */
    private static int scan(String text, int from, char mine, char theirs, boolean forwards,
                            IntPredicate skip) {
        int step = forwards ? 1 : -1;
        int depth = 0;
        int limit = Math.min(text.length(), FAR_ENOUGH);
        for (int at = from, walked = 0; at >= 0 && at < text.length() && walked < limit;
                at += step, walked++) {
            if (skip.test(at)) {
                continue;
            }
            char c = text.charAt(at);
            if (c == mine) {
                depth++;
            } else if (c == theirs) {
                depth--;
                if (depth == 0) {
                    return at;
                }
            }
        }
        return -1;
    }
}
