package com.smide.plugins.notebook;

/**
 * The file is not a notebook this can read.
 *
 * <p>Thrown rather than returning an empty notebook, because the two cases want opposite
 * treatment. A notebook that cannot be parsed must not be opened as an empty one: the
 * editor would then be holding nothing, and the first save would write that nothing over
 * whatever was really in the file. Refusing to open it leaves the file alone and lets the
 * reader see the JSON in a plain editor and fix it.
 */
public class NotebookFormatException extends Exception {

    public NotebookFormatException(String message) {
        super(message);
    }

    public NotebookFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
