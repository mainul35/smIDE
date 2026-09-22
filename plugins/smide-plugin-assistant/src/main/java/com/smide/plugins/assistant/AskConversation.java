package com.smide.plugins.assistant;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.mdviewer.ai.ChatProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One project's conversation with the assistant, kept while the IDE runs and after it stops.
 *
 * <p>A developer with four projects open has four different questions in hand, and a single
 * transcript shared between them is no use to any of them: switch to the other project and the
 * answer on screen is about somewhere else, ask a question and the model answers it with the
 * wrong project's files in its memory. So there is one of these for each workspace - what was
 * said, what was asked, and what the model itself remembers - and the panel shows whichever
 * belongs to the project in front.
 *
 * <p>Kept in {@code ~/.smide/ask} rather than in the project, deliberately. A conversation is the
 * developer's own: it has their questions in it, their half-formed ideas, and whatever the model
 * quoted back at them from their code. None of that belongs in a folder that gets committed and
 * shared, and a project copied to another machine should not bring somebody else's thinking with
 * it.
 */
final class AskConversation {

    /** How much of a transcript is kept. Past this the oldest of it goes, as the model's does. */
    private static final int MOST_CHARACTERS = 400_000;
    /** And how many questions are worth keeping for the up arrow to walk back through. */
    private static final int MOST_QUESTIONS = 200;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** What one of these looks like on disk. */
    private static final class Stored {
        String root;
        String said;
        String draft;
        List<String> questions;
        List<Said> history;
    }

    /** One turn of the model's own memory: not what is on screen, but what it was told. */
    private static final class Said {
        String role;
        String content;

        Said(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private final Path root;

    /** Everything on screen, as Markdown. */
    final StringBuilder said = new StringBuilder();
    /** What has been asked here, oldest first, for the up arrow. */
    final List<String> questions = new ArrayList<>();
    /** A question typed but not sent, so switching away and back does not lose it. */
    String draft = "";
    /** What the model is saying this moment; never saved, because it is not said yet. */
    String streaming = "";
    /** The agent, made when the first question is asked and kept for the rest of them. */
    AskAgent agent;
    /** Whether a turn is running here, which is what the buttons show when this is in front. */
    boolean busy;
    /** Whether this turn's freedom to change files came from the words rather than the tick. */
    boolean tickedItself;
    /** The model's memory, read back from disk and given to the agent when it is made. */
    private List<ChatProvider.Message> remembered = List.of();

    AskConversation(Path root) {
        this.root = root;
    }

    Path root() {
        return root;
    }

    boolean isEmpty() {
        return said.length() == 0 && questions.isEmpty();
    }

    /** What the model should be told it already knows, when its agent is made. */
    List<ChatProvider.Message> remembered() {
        return remembered;
    }

    void rememberFrom(AskAgent agent) {
        remembered = agent == null ? remembered : agent.history();
    }

    /** Wipes it, on screen and on disk: "New question" means a new conversation. */
    void forget(Path home) {
        said.setLength(0);
        questions.clear();
        streaming = "";
        draft = "";
        remembered = List.of();
        if (agent != null) {
            agent.forget();
        }
        if (root == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileIn(home, root));
        } catch (IOException | RuntimeException e) {
            // It will be overwritten by the next answer; an unremovable file is not worth a dialog.
        }
    }

    // ------------------------------------------------------------------ on disk

    /** Reads this project's conversation back, or an empty one if there is none to read. */
    static AskConversation read(Path home, Path root) {
        AskConversation conversation = new AskConversation(root);
        Path file = fileIn(home, root);
        if (!Files.isRegularFile(file)) {
            return conversation;
        }
        try {
            Stored stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            if (stored == null) {
                return conversation;
            }
            if (stored.said != null) {
                conversation.said.append(stored.said);
            }
            if (stored.questions != null) {
                conversation.questions.addAll(stored.questions);
            }
            conversation.draft = stored.draft == null ? "" : stored.draft;
            List<ChatProvider.Message> history = new ArrayList<>();
            if (stored.history != null) {
                for (Said turn : stored.history) {
                    if (turn != null && turn.role != null && turn.content != null) {
                        history.add(new ChatProvider.Message(turn.role, turn.content));
                    }
                }
            }
            conversation.remembered = List.copyOf(history);
        } catch (IOException | RuntimeException e) {
            // A conversation that cannot be read is one conversation lost, not a broken IDE.
            System.err.println("smIDE: could not read " + file + ": " + e);
        }
        return conversation;
    }

    /** Writes it down, so that closing the IDE is not the same as ending the conversation. */
    void write(Path home) {
        if (root == null) {
            // The panel before any project is open: there is nothing to belong to yet.
            return;
        }
        rememberFrom(agent);
        Stored stored = new Stored();
        stored.root = root.toString();
        stored.said = tail(said.toString());
        stored.draft = draft;
        stored.questions = questions.size() <= MOST_QUESTIONS
                ? List.copyOf(questions)
                : List.copyOf(questions.subList(questions.size() - MOST_QUESTIONS, questions.size()));
        stored.history = new ArrayList<>();
        for (ChatProvider.Message message : remembered) {
            stored.history.add(new Said(message.role(), message.content()));
        }
        Path file = fileIn(home, root);
        try {
            Files.createDirectories(file.getParent());
            // Written beside and moved into place: an IDE that stops mid-write leaves the last
            // good conversation rather than half of this one.
            Path temporary = file.resolveSibling(file.getFileName() + ".new");
            Files.writeString(temporary, GSON.toJson(stored), StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: could not save the conversation for " + root + ": " + e);
        }
    }

    /** The end of a long transcript, which is the part anybody scrolls to. */
    private static String tail(String text) {
        if (text.length() <= MOST_CHARACTERS) {
            return text;
        }
        return "*The beginning of this conversation was dropped to keep it a sensible size.*\n\n"
                + text.substring(text.length() - MOST_CHARACTERS);
    }

    /**
     * Where a project's conversation lives.
     *
     * <p>Named after the project so the folder can be read by a person, and ended with a hash of
     * the whole path so that two projects called "server" are two conversations.
     */
    static Path fileIn(Path home, Path root) {
        String name = root.getFileName() == null ? "project" : root.getFileName().toString();
        StringBuilder safe = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) ? c : '-');
        }
        if (safe.length() > 40) {
            safe.setLength(40);
        }
        return home.resolve("ask").resolve(safe + "-" + shortHash(root.toString()) + ".json");
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.format("%08x", text.hashCode());
        }
    }
}
