package com.smide.api.lang;

/** A coloured range of text: {@code [start, end)}. */
public record Token(int start, int end, TokenType type) {

    public int length() {
        return end - start;
    }
}
