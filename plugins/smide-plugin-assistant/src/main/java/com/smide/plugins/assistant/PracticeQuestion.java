package com.smide.plugins.assistant;

import java.util.Map;

/**
 * One problem in a practice session.
 *
 * <p>The rubric is part of the question and is never shown. It is written when the question
 * is set, before there is an answer to be swayed by, and sent back with the submission so
 * the marking is against a scheme rather than against whatever the model thinks now. That
 * is most of what stops a theory answer being marked by vibe.
 *
 * <p>There is no starter text, deliberately. A field for one is a field a model will
 * fill, and what it filled it with was three quarters of the query - which is the whole
 * exercise handed over before it begins. The editor starts empty.
 *
 * @param kind     {@code code} when the answer is code, {@code theory} when it is prose
 * @param language what the answer is written in, for the editor's highlighting
 */
record PracticeQuestion(String title, String kind, String language, String difficulty,
                        String question, String rubric) {

    static PracticeQuestion parse(String reply) {
        Map<String, String> fields = Fields.of(reply);
        String kind = Fields.get(fields, "KIND", "code").toLowerCase(java.util.Locale.ROOT);
        String question = Fields.get(fields, "QUESTION", "");
        if (question.isBlank()) {
            /* No fields at all: the model answered in prose. Rather than showing an empty
               question, the whole reply becomes the question - it is still a question, and
               a session that limps is better than one that stops. */
            question = reply == null ? "" : reply.strip();
        }
        return new PracticeQuestion(
                Fields.get(fields, "TITLE", "Practice question"),
                kind.startsWith("theory") ? "theory" : "code",
                Fields.get(fields, "LANGUAGE", "text").toLowerCase(java.util.Locale.ROOT).strip(),
                Fields.get(fields, "DIFFICULTY", "medium"),
                question,
                Fields.get(fields, "RUBRIC", "(none was written; mark on the merits)"));
    }

    boolean isTheory() {
        return "theory".equals(kind);
    }

    /** The question as the panel renders it: the heading, then the body. */
    String markdown() {
        return "### " + title + "\n\n*" + difficulty + " - "
                + (isTheory() ? "written answer" : language + " answer") + "*\n\n" + question;
    }
}
