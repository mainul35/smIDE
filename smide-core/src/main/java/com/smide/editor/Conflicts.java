package com.smide.editor;

/**
 * What to do about a file that changed underneath an open editor.
 *
 * <p>Two facts decide it, and only two: whether the editor has unsaved edits, and whether the
 * file is still the one the editor read. Everything else - which editor, which file, whether
 * this is a save, a close or a sweep after the window came back to the front - only chooses
 * which of the two questions below is being asked.
 *
 * <p>No JavaFX here. The dialogs this leads to cannot be tested; the reasoning that decides
 * which dialog to show can be, and keeping it apart is the only way that happens.
 */
public final class Conflicts {

    /** What the IDE should do. */
    public enum Action {
        /** Write, without asking: the file is as the editor left it. */
        WRITE,
        /** Re-read the file: it changed, and there is nothing of the user's to lose. */
        RELOAD,
        /** Do nothing at all. */
        LEAVE,
        /**
         * Ask the user. Both versions exist, they differ, and neither can be thrown away on
         * the IDE's say-so - which is the whole point of this class.
         */
        ASK
    }

    private Conflicts() {
    }

    /**
     * What to do when something is about to write the editor's contents to its file.
     *
     * <p>A file that has not changed is written without a word, which is every save in a
     * normal day's work. A file that has changed is not: writing would destroy whatever the
     * change was, and the IDE is not in a position to judge whether that matters, so it asks.
     * This holds whether the save was asked for with a keystroke, swept up by Save All, or
     * offered by the dialog that appears when a modified editor closes - that last one is
     * where the destruction is most likely, because the user is answering a question about
     * unsaved changes and has no reason to suspect the file also moved under them.
     */
    public static Action forSave(boolean staleOnDisk) {
        return staleOnDisk ? Action.ASK : Action.WRITE;
    }

    /**
     * What to do when the file is noticed to have changed and nobody asked to save.
     *
     * <p>An editor with no unsaved edits holds nothing the file does not, so it re-reads and
     * the user sees the new content - the same as never having had it open. An editor with
     * unsaved edits holds something the file does not, so it keeps it and says nothing: a
     * dialog here would interrupt typing to ask about a file the user may not even be
     * thinking of, and the question keeps perfectly well until the moment it matters, which
     * is the save. An unchanged file is nobody's business.
     */
    public static Action forExternalChange(boolean modified, boolean staleOnDisk) {
        if (!staleOnDisk) {
            return Action.LEAVE;
        }
        return modified ? Action.LEAVE : Action.RELOAD;
    }

    /**
     * What to do when a modified editor is closing.
     *
     * <p>Unchanged file: the familiar save-or-discard question, which is not about a conflict
     * at all. Changed file: the same question with a third version in play, so it has to be
     * asked differently - and that is {@link Action#ASK} either way, distinguished by what is
     * put in front of the user rather than by this value.
     */
    public static Action forClose(boolean modified, boolean staleOnDisk) {
        if (!modified) {
            // Nothing of the user's is at stake; a stale clean editor is simply closing.
            return Action.LEAVE;
        }
        return Action.ASK;
    }
}
