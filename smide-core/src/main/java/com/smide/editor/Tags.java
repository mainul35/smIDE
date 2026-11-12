package com.smide.editor;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The other end of the tag the caret is in.
 *
 * <p>The same question brackets ask, asked by markup: this {@code </div>} closes which
 * {@code <div>}? In a page where every other line opens one, counting them by eye is the
 * work the editor should be doing - and more so than for brackets, because the two ends
 * of a tag can be hundreds of lines apart with a dozen more between them.
 *
 * <p>What is marked is the name rather than the whole tag: {@code div}, not
 * {@code <div class="panel" data-id="17">}. The name is what identifies the pair, and
 * boxing an entire opening tag would be a box around most of the line.
 *
 * <p>Tags inside comments, and inside the strings of a script block, are not tags - the
 * caller says which positions those are, off the colouring that has already worked it
 * out. Void elements, which close themselves by being what they are, have no other end
 * and are left alone rather than reported as unclosed.
 */
final class Tags {

    /** The two names, as ranges: {@code [start, end)} of each. */
    record Match(int start, int end, int partnerStart, int partnerEnd) {

        boolean found() {
            return partnerStart >= 0;
        }
    }

    /**
     * Elements that have no closing tag at all in HTML.
     *
     * <p>Without this, putting the caret in an {@code <img>} would hunt to the end of the
     * file for an {@code </img>} that never comes and then report it as a mistake.
     */
    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
            "param", "source", "track", "wbr", "!doctype");

    /** How far back a tag may have started. An opening tag longer than this is pathological. */
    private static final int LONGEST_TAG = 20_000;

    /** How far the search for the other end goes before giving up. */
    private static final int FAR_ENOUGH = 2_000_000;

    private Tags() {
    }

    /** One tag, as it sits in the text. */
    private record Tag(int start, int nameStart, int nameEnd, int end, boolean closing,
                       boolean selfClosing, String name) {
    }

    /** The match for the tag the caret is in, if it is in one. */
    static Optional<Match> around(String text, int caret, IntPredicate skip) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        Tag tag = tagAt(text, caret, skip);
        if (tag == null || tag.selfClosing() || VOID.contains(tag.name())) {
            return Optional.empty();
        }
        Tag partner = partnerOf(text, tag, skip);
        return Optional.of(partner == null
                ? new Match(tag.nameStart(), tag.nameEnd(), -1, -1)
                : new Match(tag.nameStart(), tag.nameEnd(), partner.nameStart(), partner.nameEnd()));
    }

    /** The tag the caret is inside, counting both angle brackets as inside. */
    private static Tag tagAt(String text, int caret, IntPredicate skip) {
        int from = Math.max(0, Math.min(caret, text.length() - 1));
        int stop = Math.max(0, from - LONGEST_TAG);
        for (int at = from; at >= stop; at--) {
            if (text.charAt(at) != '<' || skip.test(at)) {
                continue;
            }
            Tag tag = parse(text, at);
            // A '<' that is not the start of a tag - a less-than sign - ends the search:
            // whatever the caret is in, it is not a tag that started before this.
            if (tag == null) {
                return null;
            }
            return caret <= tag.end() ? tag : null;
        }
        return null;
    }

    /** Reads a tag starting at a {@code <}, or null when that is not what it is. */
    private static Tag parse(String text, int start) {
        int at = start + 1;
        boolean closing = at < text.length() && text.charAt(at) == '/';
        if (closing) {
            at++;
        }
        int nameStart = at;
        while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at))
                || "!:-_.".indexOf(text.charAt(at)) >= 0)) {
            at++;
        }
        if (at == nameStart) {
            return null;
        }
        String name = text.substring(nameStart, at).toLowerCase(Locale.ROOT);
        int close = text.indexOf('>', at);
        int end = close < 0 ? text.length() : close;
        boolean selfClosing = close > 0 && text.charAt(close - 1) == '/';
        return new Tag(start, nameStart, at, end, closing, selfClosing, name);
    }

    /** The opening tag for a closing one, or the closing tag for an opening one. */
    private static Tag partnerOf(String text, Tag tag, IntPredicate skip) {
        Pattern same = Pattern.compile("</?" + Pattern.quote(text.substring(tag.nameStart(), tag.nameEnd()))
                + "(?=[\\s/>])", Pattern.CASE_INSENSITIVE);
        Matcher m = same.matcher(text);
        int depth = 0;
        if (tag.closing()) {
            // Backwards: every match before this one, in order, the last to balance wins.
            Tag found = null;
            int limit = Math.max(0, tag.start() - FAR_ENOUGH);
            m.region(limit, tag.end() + 1);
            java.util.List<Tag> before = new java.util.ArrayList<>();
            while (m.find()) {
                Tag other = parse(text, m.start());
                if (other != null && !skip.test(m.start())) {
                    before.add(other);
                }
            }
            for (int i = before.size() - 1; i >= 0; i--) {
                Tag other = before.get(i);
                if (other.selfClosing()) {
                    continue;
                }
                depth += other.closing() ? 1 : -1;
                if (depth == 0) {
                    found = other;
                    break;
                }
            }
            return found;
        }
        m.region(tag.start(), Math.min(text.length(), tag.start() + FAR_ENOUGH));
        while (m.find()) {
            Tag other = parse(text, m.start());
            if (other == null || other.selfClosing() || skip.test(m.start())) {
                continue;
            }
            depth += other.closing() ? -1 : 1;
            if (depth == 0) {
                return other;
            }
        }
        return null;
    }
}
