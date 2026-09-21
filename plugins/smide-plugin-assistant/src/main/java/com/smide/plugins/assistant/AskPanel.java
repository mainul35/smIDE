package com.smide.plugins.assistant;

import com.smide.api.Ide;
import com.smide.api.workspace.Workspace;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Ask: a question about the project in front of you, answered by something that can look at it.
 *
 * <p>Unlike Review, which reads one file and reports, this one works: it reads what it needs,
 * builds the project when the question is why it will not build, looks things up, and proposes
 * changes. Every change stops here first, as a card with the file and what would go in it, and
 * nothing is written until the developer presses Apply - except when they have asked outright for
 * something to be fixed, which turns that off for the one task and says so on screen.
 */
final class AskPanel extends BorderPane {

    /**
     * How a sentence that tells you to do something starts.
     *
     * <p>Matched at the start of a sentence rather than anywhere in the question, because the
     * difference between "fix it" and "what would fix this?" is the whole difference between a
     * request and a question - and the first of those is allowed to change the project.
     */
    private static final String[] ORDERS = {
        "fix ", "fix it", "fix this", "go ahead", "do it", "sort it out", "sort this out",
        "make the change", "make these changes", "apply it", "apply the fix", "just fix"
    };
    /** Said anywhere, these mean the same thing: get on with it. */
    private static final String[] ANYWHERE = {"yourself", "autonomously", "without asking"};

    private final Assistant assistant;
    private final Ide ide;
    private final MarkdownPane transcript;
    private final TextArea input = new TextArea();
    private final Button send = new Button("Ask");
    private final Button stop = new Button("Stop");
    private final Button clear = new Button("New question");
    private final CheckBox letItFix = new CheckBox("Let it change files for this task");
    private final Label status = new Label();
    private final Waiting waiting = new Waiting(status);
    private final Label where = new Label();
    private final VBox approval = new VBox(6);

    /** Everything said so far, as Markdown; the pane shows it whole each time it grows. */
    private final StringBuilder said = new StringBuilder();
    private String streaming = "";
    private AskAgent agent;
    private Path root;
    /** Whether this turn's freedom came from the words used rather than from the tick. */
    private boolean tickedItself;

    AskPanel(Assistant assistant) {
        this.assistant = assistant;
        this.ide = assistant.ide();
        this.transcript = new MarkdownPane(ide);

        where.getStyleClass().add("assistant-file");
        letItFix.setSelected(false);
        letItFix.setTooltip(com.smide.api.ui.Tooltips.of(
                "Off, every new file and every edit waits for you. Asking it to \"fix it\" turns"
                        + " this on for that one task."));

        HBox top = new HBox(10, where);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8, 10, 6, 10));
        setTop(top);

        transcript.setFollow(true);
        setCenter(transcript);

        input.setPromptText("Ask about this project - why it will not build, where something is done,"
                + " what to change. Ctrl+Enter sends.");
        input.setPrefRowCount(3);
        input.setWrapText(true);
        input.getStyleClass().add("assistant-question");
        input.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && (e.isControlDown() || e.isMetaDown())) {
                e.consume();
                ask();
            }
        });

        send.setDefaultButton(false);
        send.setOnAction(e -> ask());
        stop.setOnAction(e -> stop());
        stop.setDisable(true);
        clear.setOnAction(e -> reset());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(6, send, stop, clear, spacer, letItFix);
        buttons.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("muted-small");
        VBox bottom = new VBox(6, approval, input, buttons, status);
        bottom.setPadding(new Insets(6, 10, 10, 10));
        setBottom(bottom);

        approval.setVisible(false);
        approval.setManaged(false);
        followTheWorkspace();
        greet();
    }

    /** The project every question is about: whichever workspace is in front. */
    private void followTheWorkspace() {
        updateRoot();
        ide.workspaces().addActiveListener(w -> ide.window().runLater(this::updateRoot));
    }

    private void updateRoot() {
        Optional<Workspace> active = ide.workspaces().active();
        Path now = active.map(Workspace::root).orElse(null);
        if (now != null && !now.equals(root)) {
            root = now;
            agent = null;
            where.setText(active.get().name());
        } else if (now == null) {
            where.setText("No project open");
        }
    }

    private void greet() {
        said.append("Ask about **this project**. I can read it, search it, build it and look"
                + " things up - and I will show you any change before it is made.\n\n");
        transcript.show(said.toString());
    }

    // --------------------------------------------------------------- asking

    private void ask() {
        String question = input.getText() == null ? "" : input.getText().strip();
        if (question.isEmpty() || root == null) {
            if (root == null) {
                status("Open a project first.");
            }
            return;
        }
        if (agent == null) {
            AskTools tools = new AskTools(ide, root);
            agent = new AskAgent(assistant, tools, web(), new PanelListener());
        }
        // "Fix it" is a different request from "what is wrong": it is the one that may write.
        boolean asked = meansFixIt(question);
        boolean fixing = letItFix.isSelected() || asked;
        tickedItself = asked && !letItFix.isSelected();
        letItFix.setSelected(fixing);
        agent.setAutonomous(fixing);

        input.clear();
        said.append("\n\n### You asked\n\n").append(question).append("\n\n");
        transcript.show(said.toString());
        working(true);
        waiting.start(fixing ? "Working on it" : "Thinking");
        AskAgent working = agent;
        ide.window().runInBackground(() -> working.ask(question));
    }

    static boolean meansFixIt(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        for (String phrase : ANYWHERE) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        for (String sentence : lower.split("[.!?\n]")) {
            String said = sentence.strip();
            while (said.startsWith("please ") || said.startsWith("then ") || said.startsWith("and ")
                    || said.startsWith("now ")) {
                said = said.substring(said.indexOf(' ') + 1).strip();
            }
            for (String order : ORDERS) {
                if (said.equals(order.strip()) || said.startsWith(order)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void stop() {
        if (agent != null) {
            agent.stop();
        }
        waiting.stop("Stopped.");
        working(false);
        hideApproval();
    }

    private void reset() {
        stop();
        if (agent != null) {
            agent.forget();
        }
        said.setLength(0);
        streaming = "";
        letItFix.setSelected(false);
        greet();
    }

    private void working(boolean busy) {
        send.setDisable(busy);
        stop.setDisable(!busy);
        input.setDisable(busy);
        /* The freedom to change files was given for one task, and the task is over. A tick the
           developer put there themselves stays where they put it. */
        if (!busy && tickedItself) {
            tickedItself = false;
            letItFix.setSelected(false);
            if (agent != null) {
                agent.setAutonomous(false);
            }
        }
    }

    private void status(String text) {
        status.setText(text);
    }

    private AskWeb web() {
        AssistantConfig config = assistant.config();
        return new AskWeb(config.text("search.provider", ""), config.text("search.key", ""));
    }

    /** Shows the transcript with whatever the model is saying right now underneath it. */
    private void redraw() {
        transcript.show(closed(said + (streaming.isBlank() ? "" : "\n" + streaming)));
    }

    /**
     * The same Markdown with every code fence closed.
     *
     * <p>The conversation is one document, and a reply that opens a fence without closing it -
     * which a model cut off mid-answer does every time - turns everything after it into code: the
     * next question, the steps beneath it, the next answer, all in one grey box. Counting the
     * fences and closing the odd one out costs nothing and keeps the rest readable.
     */
    static String closed(String markdown) {
        int fences = 0;
        for (String line : markdown.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                fences++;
            }
        }
        return fences % 2 == 0 ? markdown : markdown + "\n```\n";
    }

    // --------------------------------------------------------------- the agent's side

    private final class PanelListener implements AskAgent.Listener {

        @Override
        public void streaming(String soFar) {
            ide.window().runLater(() -> {
                streaming = soFar;
                redraw();
            });
        }

        @Override
        public void doing(String what) {
            ide.window().runLater(() -> {
                streaming = "";
                said.append("- *").append(what).append("*\n");
                redraw();
                status(what);
            });
        }

        @Override
        public void did(String what, String result) {
            ide.window().runLater(() -> {
                String note = summarise(result);
                if (!note.isBlank()) {
                    said.append("  ").append(note).append('\n');
                    redraw();
                }
            });
        }

        @Override
        public CompletableFuture<Boolean> approve(AskTools.Change change) {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            ide.window().runLater(() -> showApproval(change, answer));
            return answer;
        }

        @Override
        public void answered(String markdown) {
            ide.window().runLater(() -> {
                streaming = "";
                said.append('\n').append(closed(markdown)).append('\n');
                redraw();
                waiting.stop("");
                working(false);
            });
        }

        @Override
        public void failed(String message) {
            ide.window().runLater(() -> {
                streaming = "";
                said.append("\n**That did not work.** ").append(message).append('\n');
                redraw();
                waiting.stop("");
                working(false);
            });
        }
    }

    /** One line about what a tool found, for the transcript; the model gets the whole thing. */
    private static String summarise(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        String first = result.strip().lines().findFirst().orElse("");
        long lines = result.strip().lines().count();
        if (lines <= 1 && first.length() <= 120) {
            return first;
        }
        return "(" + lines + " lines)";
    }

    // --------------------------------------------------------------- approval

    private void showApproval(AskTools.Change change, CompletableFuture<Boolean> answer) {
        approval.getChildren().clear();
        boolean command = change.kind() == AskTools.Change.Kind.COMMAND;
        Label what = new Label(command
                ? "Run:  " + change.after()
                : switch (change.kind()) {
                    case CREATE -> "Create ";
                    case CHANGE -> "Change ";
                    case DELETE -> "Delete ";
                    case COMMAND -> "Run ";
                } + change.relativeTo(root));
        what.getStyleClass().add("assistant-file");

        /* Whichever of the two sides this change has. A new file has what would go in it, a
           changed one has the lines that differ, and a file about to be removed has only what is
           in it now - which is exactly what the developer is being asked to part with, and was
           what crashed this card when it went looking for text that a deletion does not have. */
        String shown = switch (change.kind()) {
            case COMMAND, CREATE -> change.after();
            case CHANGE -> previewOf(change);
            case DELETE -> change.before();
        };
        shown = shown == null ? "" : shown;
        TextArea preview = new TextArea(shown);
        preview.setEditable(false);
        preview.setPrefRowCount(Math.min(14, Math.max(3, (int) shown.lines().count() + 1)));
        preview.setWrapText(false);
        preview.getStyleClass().add("assistant-change-preview");

        Button apply = new Button(switch (change.kind()) {
            case COMMAND -> "Run it";
            case CREATE -> "Create it";
            case CHANGE -> "Apply it";
            case DELETE -> "Delete it";
        });
        Button skip = new Button("No");
        apply.setOnAction(e -> {
            hideApproval();
            answer.complete(true);
        });
        skip.setOnAction(e -> {
            hideApproval();
            answer.complete(false);
        });
        HBox buttons = new HBox(6, apply, skip);
        buttons.setAlignment(Pos.CENTER_LEFT);

        approval.getChildren().addAll(what, preview, buttons);
        approval.setVisible(true);
        approval.setManaged(true);
        status(command ? "Waiting for you: run this?" : "Waiting for you: apply this change?");
    }

    /** What the change would put in the file: the new file, or the lines that differ. */
    private static String previewOf(AskTools.Change change) {
        if (change.isNew() || change.before() == null) {
            return change.after();
        }
        java.util.List<String> before = change.before().lines().toList();
        java.util.List<String> after = change.after().lines().toList();
        int start = 0;
        while (start < before.size() && start < after.size() && before.get(start).equals(after.get(start))) {
            start++;
        }
        int fromEnd = 0;
        while (fromEnd < before.size() - start && fromEnd < after.size() - start
                && before.get(before.size() - 1 - fromEnd).equals(after.get(after.size() - 1 - fromEnd))) {
            fromEnd++;
        }
        StringBuilder out = new StringBuilder();
        for (int i = start; i < before.size() - fromEnd; i++) {
            out.append("- ").append(before.get(i)).append('\n');
        }
        for (int i = start; i < after.size() - fromEnd; i++) {
            out.append("+ ").append(after.get(i)).append('\n');
        }
        return out.length() == 0 ? "(nothing would change)" : out.toString();
    }

    private void hideApproval() {
        approval.getChildren().clear();
        approval.setVisible(false);
        approval.setManaged(false);
    }

    /** Puts the caret in the question box, for the menu item that opens this tab. */
    void focusInput() {
        input.requestFocus();
    }

    void dispose() {
        if (agent != null) {
            agent.stop();
        }
        waiting.stop("");
    }
}
