package com.smide.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConflictsTest {

    @Test
    void anOrdinarySaveJustWrites() {
        assertEquals(Conflicts.Action.WRITE, Conflicts.forSave(false));
    }

    @Test
    void savingOverAFileThatChangedAsksFirst() {
        assertEquals(Conflicts.Action.ASK, Conflicts.forSave(true));
    }

    @Test
    void aCleanEditorTakesTheNewContent() {
        assertEquals(Conflicts.Action.RELOAD, Conflicts.forExternalChange(false, true));
    }

    @Test
    void anEditorWithUnsavedWorkKeepsIt() {
        // No dialog here: the question keeps until the save, which is when it matters.
        assertEquals(Conflicts.Action.LEAVE, Conflicts.forExternalChange(true, true));
    }

    @Test
    void anUnchangedFileIsNobodysBusiness() {
        assertEquals(Conflicts.Action.LEAVE, Conflicts.forExternalChange(false, false));
        assertEquals(Conflicts.Action.LEAVE, Conflicts.forExternalChange(true, false));
    }

    @Test
    void closingWithNothingUnsavedAsksNothing() {
        assertEquals(Conflicts.Action.LEAVE, Conflicts.forClose(false, false));
        // Even though the file changed: there is nothing of the user's to weigh against it.
        assertEquals(Conflicts.Action.LEAVE, Conflicts.forClose(false, true));
    }

    @Test
    void closingWithUnsavedWorkAlwaysAsks() {
        assertEquals(Conflicts.Action.ASK, Conflicts.forClose(true, false));
        assertEquals(Conflicts.Action.ASK, Conflicts.forClose(true, true));
    }

    @Test
    void theOneThatLostTheNotebook() {
        /* The case this class exists for. An editor held a one-line scratch file. The file
           was replaced on disk by six kilobytes of notebook. The window was closed, the
           editor was modified, and smIDE wrote the one line back over the notebook without
           a word. It must ask, and the asking must say the file changed. */
        assertEquals(Conflicts.Action.ASK, Conflicts.forClose(true, true));
        assertEquals(Conflicts.Action.ASK, Conflicts.forSave(true));
    }
}
