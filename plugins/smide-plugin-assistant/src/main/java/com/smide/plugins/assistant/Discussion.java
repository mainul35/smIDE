package com.smide.plugins.assistant;

import com.mdviewer.ai.ChatProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The conversation about one review: the code that was read, what was found, and every
 * question asked about it since.
 *
 * <p>Kept whole for as long as it fits and trimmed from the middle when it does not. The
 * two things that never go are the code and the review itself: a follow-up question is
 * about a particular line in a particular file, and an assistant that has forgotten the
 * file will answer it in general terms and sound just as sure. Old questions are what a
 * conversation can afford to lose - the fifth answer rarely depends on the first.
 *
 * <p>Nothing here talks to a model. It builds the list of messages a request is made of,
 * which is the part worth being able to reason about without one.
 */
final class Discussion {

    /** Reserved out of the window for the reply and the system prompt. */
    private static final int RESERVED = 12000;

    /** The code that was reviewed, and the review; then question, answer, question... */
    private final List<ChatProvider.Message> turns = new ArrayList<>();

    /** How many earlier questions have been dropped, so the panel can say so. */
    private int dropped;

    /** Begins the conversation from a finished review. */
    void start(String codeContext, String review) {
        turns.clear();
        dropped = 0;
        turns.add(new ChatProvider.Message("user", codeContext));
        turns.add(new ChatProvider.Message("assistant", review));
    }

    boolean isStarted() {
        return turns.size() >= 2;
    }

    int dropped() {
        return dropped;
    }

    /** Questions asked and answered so far. */
    int exchanges() {
        return Math.max(0, (turns.size() - 2) / 2);
    }

    /**
     * The messages one follow-up is made of: the system prompt, as much of the
     * conversation as fits, and the new question.
     *
     * <p>{@code windowChars} is the whole request, not the history: the endpoint does not
     * refuse an oversized one, it truncates from the front - taking the instructions first
     * and leaving the model holding the files with no idea what it was asked to do with
     * them. Counting the question and the system prompt in is the difference between a
     * budget and a hope.
     */
    List<ChatProvider.Message> request(String system, String question, int windowChars) {
        List<ChatProvider.Message> messages = new ArrayList<>();
        messages.add(new ChatProvider.Message("system", system));
        if (turns.isEmpty()) {
            messages.add(new ChatProvider.Message("user", question));
            return messages;
        }
        int budget = Math.max(4000, windowChars - RESERVED)
                - system.length() - question.length();

        ChatProvider.Message code = turns.get(0);
        ChatProvider.Message review = turns.get(1);
        int fixed = code.content().length() + review.content().length();
        if (fixed > budget) {
            /* Even the code and the review do not fit, which means the review was made
               with a bigger window than the one configured now. The review is what the
               questions are about, so the code gives way first, and says that it has. */
            int room = Math.max(1000, budget - review.content().length());
            code = new ChatProvider.Message("user", trim(code.content(), room));
            fixed = code.content().length() + review.content().length();
        }
        int left = budget - fixed;

        // Later turns first: the recent ones are the ones a follow-up follows.
        List<ChatProvider.Message> recent = new ArrayList<>();
        int kept = 0;
        for (int i = turns.size() - 1; i >= 2; i--) {
            ChatProvider.Message turn = turns.get(i);
            if (turn.content().length() > left) {
                break;
            }
            left -= turn.content().length();
            recent.add(0, turn);
            kept++;
        }
        dropped = (turns.size() - 2 - kept) / 2;

        messages.add(code);
        messages.add(review);
        if (dropped > 0) {
            // Said out loud rather than left as a silent gap, so an answer that ignores
            // something asked earlier is explicable rather than baffling.
            messages.add(new ChatProvider.Message("user",
                    "(" + dropped + " earlier question" + (dropped == 1 ? "" : "s")
                            + " in this conversation were dropped to fit the context window."
                            + " Say so if one of them would have mattered.)"));
        }
        messages.addAll(recent);
        messages.add(new ChatProvider.Message("user", question));
        return messages;
    }

    /** Remembers a question and the answer it got. */
    void record(String question, String answer) {
        if (turns.isEmpty()) {
            return;
        }
        turns.add(new ChatProvider.Message("user", question));
        turns.add(new ChatProvider.Message("assistant", answer));
    }

    private static String trim(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit)
                + "\n\n(the rest of the code was dropped to fit the context window)";
    }
}
