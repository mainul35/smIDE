package com.smide.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where the caret has been, so Back and Forward mean what they do in a browser.
 *
 * <p>A place is recorded when the user jumps somewhere on purpose - a declaration, a
 * search hit, a stack frame - not on every caret move, which would make Back step
 * through typing. Going back and then jumping somewhere new drops the forward tail, the
 * same rule a browser follows.
 */
public final class NavigationHistory {

    public record Place(Path file, int line, int column) {

        /** Two places within a few lines of each other in one file are the same place. */
        boolean isNear(Place other) {
            return other != null && file.equals(other.file) && Math.abs(line - other.line) <= 2;
        }
    }

    private static final int MAX = 60;

    private final List<Place> places = new ArrayList<>();
    private int cursor = -1;

    /** Adds a place after the current one, dropping anything that was ahead. */
    public void record(Place place) {
        if (place == null) {
            return;
        }
        if (cursor >= 0 && cursor < places.size() && places.get(cursor).isNear(place)) {
            places.set(cursor, place);
            return;
        }
        while (places.size() > cursor + 1) {
            places.remove(places.size() - 1);
        }
        places.add(place);
        if (places.size() > MAX) {
            places.remove(0);
        }
        cursor = places.size() - 1;
    }

    /**
     * Replaces the current place, for the position the user is leaving.
     *
     * <p>Called just before a jump so Back returns to where the caret actually was,
     * rather than to wherever it was when that file was first opened.
     */
    public void updateCurrent(Place place) {
        if (place != null && cursor >= 0 && cursor < places.size()
                && places.get(cursor).file().equals(place.file())) {
            places.set(cursor, place);
        }
    }

    public boolean canGoBack() {
        return cursor > 0;
    }

    public boolean canGoForward() {
        return cursor >= 0 && cursor < places.size() - 1;
    }

    public Optional<Place> back() {
        if (!canGoBack()) {
            return Optional.empty();
        }
        cursor--;
        return Optional.of(places.get(cursor));
    }

    public Optional<Place> forward() {
        if (!canGoForward()) {
            return Optional.empty();
        }
        cursor++;
        return Optional.of(places.get(cursor));
    }

    /** Drops every place in a file that is no longer there. */
    public void forget(Path file) {
        places.removeIf(p -> p.file().equals(file));
        cursor = Math.min(cursor, places.size() - 1);
    }

    public int size() {
        return places.size();
    }
}
