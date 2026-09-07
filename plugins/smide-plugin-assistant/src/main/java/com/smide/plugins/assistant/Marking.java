package com.smide.plugins.assistant;

import java.util.Locale;
import java.util.Map;

/** What came back when a submission was marked. */
record Marking(String verdict, String score, String feedback) {

    static Marking parse(String reply) {
        Map<String, String> fields = Fields.of(reply);
        String feedback = Fields.get(fields, "FEEDBACK", "");
        if (feedback.isBlank()) {
            feedback = reply == null ? "" : reply.strip();
        }
        return new Marking(Fields.get(fields, "VERDICT", "").toLowerCase(Locale.ROOT).strip(),
                Fields.get(fields, "SCORE", "").strip(), feedback);
    }

    String label() {
        String name = verdict.isBlank() ? "marked" : verdict;
        return score.isBlank() ? name : name + "  -  " + score;
    }
}
