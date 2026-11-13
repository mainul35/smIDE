package com.smide.plugins.notebook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrisTest {

    @Test
    void theSameTextIsTheSameFile() {
        assertTrue(Uris.same("file:///C:/p/a.py", "file:///C:/p/a.py"));
    }

    /** The case this class was written for: pyright's own spelling of a Windows path. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aPercentEncodedDriveLetterIsTheSameFile() {
        assertTrue(Uris.same("file:///C:/p/a.ipynb.py", "file:///c%3A/p/a.ipynb.py"));
        assertTrue(Uris.same("file:///C:/Users/me/work/n.ipynb.py",
                "file:///c%3A/Users/me/work/n.ipynb.py"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void caseInThePathDoesNotMatterWhereTheFileSystemSaysItDoesNot() {
        assertTrue(Uris.same("file:///C:/P/A.py", "file:///c:/p/a.py"));
    }

    @Test
    void differentFilesAreDifferent() {
        assertFalse(Uris.same("file:///C:/p/a.py", "file:///C:/p/b.py"));
        assertFalse(Uris.same("file:///C:/p/a.py", "file:///C:/q/a.py"));
    }

    @Test
    void aNotebooksPythonIsNotTheNotebook() {
        assertFalse(Uris.same("file:///C:/p/a.ipynb.py", "file:///C:/p/a.ipynb"));
    }

    @Test
    void nothingIsNotAFile() {
        assertFalse(Uris.same(null, "file:///C:/p/a.py"));
        assertFalse(Uris.same("file:///C:/p/a.py", null));
        assertFalse(Uris.same("not a uri at all", "file:///C:/p/a.py"));
    }
}
