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
    private final Button copy = new Button("Copy");
    private final CheckBox letItFix = new CheckBox("Let it change files for this task");
    private final Label status = new Label();
    private final Waiting waiting = new Waiting(status);
    private final Label where = new Label();
    private final VBox approval = new VBox(6);

    /**
     * One conversation for each project, and the one on screen.
     *
     * <p>Every question is about the project in front, so the answer belongs to that project too.
     * Switching workspace puts the other conversation up - including a turn still running in it -
     * rather than carrying one transcript between projects that have nothing to do with each
     * other.
     */
    private final java.util.Map<Path, AskConversation> conversations = new java.util.HashMap<>();
    private AskConversation current = new AskConversation(null);
    private Path root;
    /** Where the up arrow has got to, counting back from the end; -1 is "not walking". */
    private int recalled = -1;
    /** What was in the box before the walk started, to come back to. */
    private String beforeRecall = "";

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
                + " what to change. Enter sends, Ctrl+Enter starts a line, ``` opens a code block.");
        input.setPrefRowCount(3);
        input.setWrapText(true);
        input.getStyleClass().add("assistant-question");
        /* A filter rather than a handler: the box's own behaviour acts on the key press too, and
           whichever runs first wins. Left as a handler, Tab put a tab in the box and then moved the
           focus, which is both things at once and neither of them wanted. */
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::onKeyInInput);
        input.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, e -> {
            if ("	".equals(e.getCharacter())) {
                e.consume();
            }
        });

        send.setDefaultButton(false);
        send.setOnAction(e -> ask());
        stop.setOnAction(e -> stop());
        stop.setDisable(true);
        clear.setOnAction(e -> reset());
        copy.setOnAction(e -> copyConversation());
        copy.setTooltip(com.smide.api.ui.Tooltips.of(
                "Copies what you have selected, or the whole conversation when nothing is."
                        + " Dragging over the answer selects it; Ctrl+C copies that too."));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(6, send, stop, clear, copy, spacer, letItFix);
        buttons.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("muted-small");
        VBox bottom = new VBox(6, approval, input, buttons, status);
        bottom.setPadding(new Insets(6, 10, 10, 10));
        setBottom(bottom);

        approval.setVisible(false);
        approval.setManaged(false);
        // A half-typed question is kept with its project, so switching away does not lose it.
        input.focusedProperty().addListener((value, had, has) -> {
            if (!has && root != null) {
                current.draft = input.getText() == null ? "" : input.getText();
            }
        });
        followTheWorkspace();
    }

    /** The project every question is about: whichever workspace is in front. */
    private void followTheWorkspace() {
        updateRoot();
        ide.workspaces().addActiveListener(w -> ide.window().runLater(this::updateRoot));
    }

    private void updateRoot() {
        Optional<Workspace> active = ide.workspaces().active();
        Path now = active.map(Workspace::root).orElse(null);
        if (now == null) {
            where.setText("No project open");
            if (root == null) {
                // Nothing has ever been in front: there is no conversation to show, so say why.
                transcript.show("Open a project and I can read it, search it, build it and look"
                        + " things up. Each one keeps its own conversation.\n\n");
            }
            return;
        }
        if (now.equals(root)) {
            where.setText(active.get().name());
            return;
        }
        leave();
        root = now;
        where.setText(active.get().name());
        show(conversations.computeIfAbsent(now, at -> AskConversation.read(ide.homeDir(), at)));
    }

    /** Puts the conversation on screen away: what was typed, what was said, all of it. */
    private void leave() {
        if (root == null) {
            return;
        }
        current.draft = input.getText() == null ? "" : input.getText();
        current.write(ide.homeDir());
    }

    /** Brings a project's conversation up, whatever state it was left in. */
    private void show(AskConversation conversation) {
        current = conversation;
        if (conversation.isEmpty()) {
            greet();
        }
        recalled = -1;
        beforeRecall = "";
        input.setText(conversation.draft);
        input.positionCaret(input.getText().length());
        hideApproval();
        letItFix.setSelected(false);
        /* A turn left running in this project carries on in the background, and comes back to
           the screen it belongs to: the buttons say what is true of the project in front. */
        working(conversation.busy);
        if (conversation.busy) {
            waiting.start("Thinking");
        } else {
            waiting.stop("");
        }
        redraw();
        if (!conversation.questions.isEmpty()) {
            status("Carrying on from " + conversation.questions.size()
                    + (conversation.questions.size() == 1 ? " question" : " questions") + " here.");
        }
    }

    private void greet() {
        current.said.append("Ask about **this project**. I can read it, search it, build it and look"
                + " things up - and I will show you any change before it is made.\n\n");
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
        AskConversation conversation = current;
        if (conversation.agent == null) {
            AskTools tools = new AskTools(ide, root);
            conversation.agent = new AskAgent(assistant, tools, web(), new PanelListener(conversation));
            // What was said before the IDE was last closed, or before another project was in front.
            conversation.agent.restore(conversation.remembered());
        }
        AskAgent agent = conversation.agent;
        // "Fix it" is a different request from "what is wrong": it is the one that may write.
        boolean asked = meansFixIt(question);
        boolean fixing = letItFix.isSelected() || asked;
        conversation.tickedItself = asked && !letItFix.isSelected();
        letItFix.setSelected(fixing);
        agent.setAutonomous(fixing);

        input.clear();
        conversation.draft = "";
        // Kept for the up arrow, which is how the same question is asked again with one word changed.
        conversation.questions.add(question);
        recalled = -1;
        conversation.said.append("\n\n### You asked\n\n").append(question).append("\n\n");
        redraw();
        working(true);
        waiting.start(fixing ? "Working on it" : "Thinking");
        ide.window().runInBackground(() -> agent.ask(question));
    }

    /**
     * What the keys do in the question box.
     *
     * <p>Enter sends, because a question is usually one line and reaching for a second key to ask
     * it is a small tax paid every time. Ctrl+Enter starts a line, for the times it is not. And
     * inside a code block Enter starts a line by itself: somebody who has just typed ``` is
     * pasting code, and sending it off after the first line of it is never what they meant.
     *
     * <p>Tab leaves the box rather than putting a tab character in it. A tab is not much use in a
     * question, and a box that swallows Tab is a box the keyboard cannot get out of.
     */
    private void onKeyInInput(javafx.scene.input.KeyEvent e) {
        if (e.getCode() == javafx.scene.input.KeyCode.UP || e.getCode() == javafx.scene.input.KeyCode.DOWN) {
            if (recall(e.getCode() == javafx.scene.input.KeyCode.UP)) {
                e.consume();
            }
            return;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.TAB) {
            e.consume();
            if (e.isShiftDown()) {
                clear.requestFocus();
            } else {
                send.requestFocus();
            }
            return;
        }
        if (e.getCode() != javafx.scene.input.KeyCode.ENTER) {
            return;
        }
        if (e.isControlDown() || e.isMetaDown() || e.isShiftDown()) {
            // A line of its own, wherever the caret is.
            e.consume();
            input.insertText(input.getCaretPosition(), "\n");
            return;
        }
        if (insideCode(input.getText(), input.getCaretPosition())) {
            // Left alone: the box puts the newline in, as it would anywhere else.
            return;
        }
        e.consume();
        ask();
    }

    /**
     * The question before this one, and the one before that.
     *
     * <p>Only from the first line going back and the last line coming forward, so the arrows still
     * move the caret about inside a question of several lines. What was typed before the walk
     * started is kept, and coming forward past the newest question puts it back.
     *
     * @return whether the key was used for this
     */
    private boolean recall(boolean backwards) {
        java.util.List<String> questions = current.questions;
        if (questions.isEmpty()) {
            return false;
        }
        String text = input.getText() == null ? "" : input.getText();
        int caret = input.getCaretPosition();
        boolean onFirstLine = text.lastIndexOf('\n', Math.max(caret - 1, 0)) < 0;
        boolean onLastLine = text.indexOf('\n', caret) < 0;
        if (backwards && !onFirstLine || !backwards && !onLastLine) {
            return false;
        }
        if (backwards) {
            if (recalled < 0) {
                beforeRecall = text;
                recalled = questions.size() - 1;
            } else if (recalled > 0) {
                recalled--;
            }
        } else {
            if (recalled < 0) {
                return false;
            }
            recalled++;
            if (recalled >= questions.size()) {
                recalled = -1;
                input.setText(beforeRecall);
                input.positionCaret(input.getText().length());
                return true;
            }
        }
        input.setText(questions.get(recalled));
        input.positionCaret(input.getText().length());
        return true;
    }

    /**
     * Whether the caret is inside a code block that has been opened and not closed.
     *
     * <p>Counted rather than parsed: the fences before the caret, odd meaning open.
     */
    static boolean insideCode(String text, int caret) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String before = text.substring(0, Math.min(Math.max(caret, 0), text.length()));
        int fences = 0;
        int at = before.indexOf("```");
        while (at >= 0) {
            fences++;
            at = before.indexOf("```", at + 3);
        }
        return fences % 2 == 1;
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
        if (current.agent != null) {
            current.agent.stop();
        }
        waiting.stop("Stopped.");
        working(false);
        hideApproval();
    }

    private void reset() {
        stop();
        current.forget(ide.homeDir());
        recalled = -1;
        beforeRecall = "";
        input.clear();
        letItFix.setSelected(false);
        greet();
        redraw();
    }

    /** What is selected if anything is, and the whole conversation if not. */
    private void copyConversation() {
        String picked = transcript.selectedText();
        if (picked != null && !picked.isBlank()) {
            javafx.scene.input.ClipboardContent part = new javafx.scene.input.ClipboardContent();
            part.putString(picked);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(part);
            status("Copied what you selected - " + picked.lines().count() + " lines.");
            return;
        }
        String conversation = closed(current.said.toString()).strip();
        if (conversation.isEmpty()) {
            status("There is nothing to copy yet.");
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(conversation);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        status("Copied the conversation - " + conversation.lines().count() + " lines.");
    }

    private void working(boolean busy) {
        send.setDisable(busy);
        stop.setDisable(!busy);
        input.setDisable(busy);
        /* The freedom to change files was given for one task, and the task is over. A tick the
           developer put there themselves stays where they put it. */
        current.busy = busy;
        if (!busy && current.tickedItself) {
            current.tickedItself = false;
            letItFix.setSelected(false);
            if (current.agent != null) {
                current.agent.setAutonomous(false);
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

    /** When the transcript was last drawn, and whether a drawing is already on its way. */
    private long drawnAt;
    private boolean drawSoon;

    /**
     * Draws at a speed a person can read rather than at the speed a model types.
     *
     * <p>Every token redrew everything: parse the whole conversation, build every heading,
     * paragraph and block again, lay it all out - forty times a second, against a transcript that
     * only grows. That is the sluggishness, and it is worst exactly when the answer is longest.
     * A few times a second looks the same to read and costs a fraction of it.
     */
    private void redrawSoon() {
        long now = System.currentTimeMillis();
        long since = now - drawnAt;
        if (since >= DRAW_EVERY_MS) {
            drawnAt = now;
            redraw();
            return;
        }
        if (drawSoon) {
            return;
        }
        drawSoon = true;
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(DRAW_EVERY_MS - since));
        wait.setOnFinished(e -> {
            drawSoon = false;
            drawnAt = System.currentTimeMillis();
            redraw();
        });
        wait.play();
    }

    /** How often the transcript is redrawn while the model is speaking. */
    private static final long DRAW_EVERY_MS = 150;

    /** Shows the transcript with whatever the model is saying right now underneath it. */
    private void redraw() {
        drawnAt = System.currentTimeMillis();
        transcript.show(closed(current.said
                + (current.streaming.isBlank() ? "" : "\n" + current.streaming)));
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

    /**
     * The agent's side of one project's conversation.
     *
     * <p>Bound to the conversation it was made for rather than to whatever is on screen. A turn
     * takes minutes, a developer switches project while it runs, and everything it says has to go
     * where it belongs: the transcript it started in, the buttons of the project it is about.
     * Only what is being looked at is drawn; the rest is there when that project comes forward.
     */
    private final class PanelListener implements AskAgent.Listener {

        private final AskConversation conversation;

        PanelListener(AskConversation conversation) {
            this.conversation = conversation;
        }

        private boolean inFront() {
            return conversation == current;
        }

        @Override
        public void streaming(String soFar) {
            ide.window().runLater(() -> {
                conversation.streaming = soFar;
                if (inFront()) {
                    redrawSoon();
                }
            });
        }

        @Override
        public void doing(String what) {
            ide.window().runLater(() -> {
                conversation.streaming = "";
                conversation.said.append("- *").append(what).append("*\n");
                if (inFront()) {
                    redraw();
                    status(what);
                }
            });
        }

        @Override
        public void did(String what, String result) {
            ide.window().runLater(() -> {
                String note = summarise(result);
                if (!note.isBlank()) {
                    conversation.said.append("  ").append(note).append('\n');
                }
                conversation.said.append(readable(result));
                if (!note.isBlank() || !readable(result).isEmpty()) {
                    if (inFront()) {
                        redraw();
                    }
                }
            });
        }

        @Override
        public CompletableFuture<Boolean> approve(AskTools.Change change) {
            CompletableFuture<Boolean> answer = new CompletableFuture<>();
            ide.window().runLater(() -> {
                if (inFront()) {
                    showApproval(change, answer);
                    return;
                }
                /* Nobody can agree to a change they cannot see. The card would appear under
                   another project's conversation, about a file that is not the one on screen, so
                   it is refused - and the developer is told which project wanted what. */
                answer.complete(false);
                ide.notifications().info("Ask",
                        "The assistant wanted to " + change.verb().toLowerCase(Locale.ROOT) + " "
                                + change.relativeTo(conversation.root()) + " in "
                                + conversation.root().getFileName()
                                + ". Open that project and ask again to let it.");
            });
            return answer;
        }

        @Override
        public void answered(String markdown) {
            ide.window().runLater(() -> {
                conversation.streaming = "";
                conversation.said.append('\n').append(closed(markdown)).append('\n');
                done();
            });
        }

        @Override
        public void failed(String message) {
            ide.window().runLater(() -> {
                conversation.streaming = "";
                conversation.said.append("\n**That did not work.** ").append(message).append('\n');
                done();
            });
        }

        /** The turn is over: shown if this project is in front, and written down either way. */
        private void done() {
            conversation.busy = false;
            conversation.trim();
            if (inFront()) {
                redraw();
                waiting.stop("");
                working(false);
            }
            conversation.write(ide.homeDir());
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

    /** How much of a tool's answer is kept to be read; past this, the model had more than you do. */
    private static final int READABLE_CHARS = 20_000;

    /**
     * The whole of what a tool found, folded away under the step that found it.
     *
     * <p>"(241 lines)" is a fair summary and no use at all when the 241 lines are the reason the
     * answer is wrong. The step stays one line, and what came back goes underneath it in something
     * that opens: shut by default, because a transcript of every file the agent read is not a
     * transcript anybody can follow, and there when it matters.
     *
     * <p>Written as Markdown, like everything else here, so that copying the conversation still
     * gets it and so that it is still there when the conversation is read back tomorrow.
     */
    static String readable(String result) {
        if (result == null || result.isBlank()) {
            return "";
        }
        String text = result.strip();
        if (text.lines().count() <= 1 && text.length() <= 120) {
            // Already said in full beside the step; saying it twice helps nobody.
            return "";
        }
        if (text.length() > READABLE_CHARS) {
            text = text.substring(0, READABLE_CHARS) + "\n... the rest is not kept here ...";
        }
        /* A fence long enough to survive its contents. A file being read is as likely as not to
           have ``` in it - every README does - and three backticks inside three backticks ends
           the block early and turns the rest of the answer into code. */
        int longest = 0;
        int run = 0;
        for (char c : text.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return "\n" + fence + "details\n" + text + "\n" + fence + "\n\n";
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
                    case CONFIG -> "Set up the ";
                } + change.relativeTo(root));
        what.getStyleClass().add("assistant-file");

        /* Whichever of the two sides this change has. A new file has what would go in it, a
           changed one has the lines that differ, and a file about to be removed has only what is
           in it now - which is exactly what the developer is being asked to part with, and was
           what crashed this card when it went looking for text that a deletion does not have. */
        String shown = switch (change.kind()) {
            case COMMAND, CREATE, CONFIG -> change.after();
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
            case CONFIG -> "Set it up";
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

    /** The IDE is closing: stop what is running and write every conversation down. */
    void dispose() {
        if (root != null) {
            current.draft = input.getText() == null ? "" : input.getText();
        }
        for (AskConversation conversation : conversations.values()) {
            if (conversation.agent != null) {
                conversation.agent.stop();
            }
            conversation.write(ide.homeDir());
        }
        waiting.stop("");
    }
}
