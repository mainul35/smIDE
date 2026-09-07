package com.smide.plugins.git;

/** A Git operation failed; the message is fit to show the user. */
public final class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
