package com.smide.plugins.assistant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a model actually sends back, as opposed to what it was asked to send.
 *
 * <p>The IDE talks to whatever endpoint the developer points it at, and the formats differ. A
 * hosted model returns the prose and nothing else. A local one - gpt-oss and the rest of the
 * Harmony family especially - returns its control tokens too: {@code <|eot|>} on the end of every
 * turn, {@code <|channel|>analysis<|message|>} around its thinking, {@code to=some.tool} in front
 * of a call. None of that is for the reader, and a reply ending in {@code <|eot|>} on screen is
 * the IDE showing its workings.
 *
 * <p>So everything that comes back goes through here first: the tokens are taken out, the final
 * channel is preferred over the thinking that led to it, and what is left is what a person was
 * meant to read.
 */
final class Replies {

    /** Any control token: {@code <|eot|>}, {@code <|im_end|>}, {@code <|channel|>} and their kind. */
    private static final Pattern CONTROL = Pattern.compile("<\\|[^|>\\n]{0,40}\\|>");

    /**
     * The routing in front of a call: {@code to=smide}, {@code to=functions.smide json}.
     *
     * <p>Only ever removed when a JSON object follows it, so that a sentence with "to=" in it is
     * left as the sentence it is.
     */
    private static final Pattern ROUTING = Pattern.compile(
            "\\bto=[A-Za-z_][A-Za-z0-9_.]*\\s*(?:json)?\\s*(?=\\{)");

    /** Where a Harmony model puts the part that was meant for the reader. */
    private static final Pattern FINAL_CHANNEL = Pattern.compile("<\\|channel\\|>final<\\|message\\|>");

    /**
     * The header on a turn: {@code <|start|>assistant<|message|>}, {@code <|channel|>analysis…}.
     *
     * <p>Taken out whole, because taking out only the tokens leaves the word between them - a
     * reply that begins "assistant" or "analysis" for no reason a reader could guess at.
     */
    private static final Pattern HEADER = Pattern.compile("<\\|(?:start|channel)\\|>[^<]{0,60}?<\\|message\\|>");

    private Replies() {
    }

    /** A reply with the machinery taken out of it. */
    static String cleaned(String reply) {
        if (reply == null || reply.isEmpty()) {
            return reply;
        }
        String text = reply;
        /* When the model has said which part is the answer, that part is the answer: everything
           before it is the thinking it did to get there, which was never addressed to anyone. */
        Matcher last = FINAL_CHANNEL.matcher(text);
        int from = -1;
        while (last.find()) {
            from = last.end();
        }
        if (from >= 0) {
            text = text.substring(from);
        }
        text = HEADER.matcher(text).replaceAll("");
        text = ROUTING.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll("");
        return text.strip();
    }
}
