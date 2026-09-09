package com.smide.plugins.assistant;

import com.mdviewer.ai.ChatProvider;
import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.editor.TextEditor;
import com.smide.api.ui.Notifications;
import com.smide.api.workspace.Workspace;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reviews the file in front of the developer: code smells, security, technical debt.
 *
 * <p>The file goes with its neighbours - what calls it, what it calls - because most of
 * those three things are only visible in the join. The reading is cached per file, so
 * switching tabs and coming back shows what was found rather than costing another minute
 * of a model's time.
 *
 * <p>Every file gets its own session, and a session outlives the tab being on screen: a
 * review started on one file keeps running while you read another, and is waiting,
 * finished, when you come back. That is the point of work that takes a minute - a minute
 * is long enough that nobody sits and watches it, and cancelling because somebody looked
 * away throws away the thing they asked for. Several can be in flight at once.
 *
 * <p>It reports. It never rewrites: there is no Apply button here, and there is not going
 * to be one.
 */
final class ReviewPanel extends BorderPane {

    private final Assistant assistant;
    private final Ide ide;
    private final MarkdownPane view;

    private final Label fileLabel = new Label("No file open");
    private final Label status = new Label();
    private final Button review = new Button("Review");
    private final Button stop = new Button("Stop");
    private final Button copy = new Button("Copy");
    private final Button configure = new Button("Configure...");
    private final CheckBox wholeProject = new CheckBox("Look at related files");
    private final ListView<CodeContext.Source> sources = new ListView<>();
    private final Label skippedNote = new Label();
    private final TitledPane sourcesPane = new TitledPane("Sources", new VBox(sources, skippedNote));
    private final TextArea question = new TextArea();
    private final Button ask = new Button("Ask");
    private final VBox askBar;

    /**
     * One file's review, the conversation about it, and whatever is arriving for it now.
     *
     * <p>Only the JavaFX thread touches these fields: the assistant hands its fragments
     * back on that thread, so a session running for a file nobody is looking at is written
     * to from exactly one place, the same as one being watched.
     */
    private static final class Session {

        final Discussion discussion = new Discussion();
        List<CodeContext.Source> sources = List.of();
        String skipped = "";

        /** The finished document: the review, then each question and its answer. */
        String transcript;

        /** The line under the panel, per file, so coming back explains itself. */
        String status = "";

        /** Said in place of a transcript: what is happening, or what went wrong. */
        String note = "";

        /** Wanted, from pressing Review or Ask until it lands, fails, or is stopped. */
        boolean running;

        /** When the current attempt started, so the status line can count. */
        long startedAt;

        /** Null while the project is still being read, and again once the reply is in. */
        Assistant.Turn turn;

        /** Non-null only while fragments are arriving. */
        StringBuilder streaming;

        /** What sits above the arriving text: the review and the question, for a follow-up. */
        String pending = "";

        /**
         * Which attempt is the current one.
         *
         * <p>Reading the project happens off the thread, so Stop followed by Review leaves
         * an earlier reading still on its way back. Without a number to check against, it
         * arrives and sends a second request nobody asked for, on top of whatever the file
         * is doing by then.
         */
        int generation;

        long lastRender;
    }

    private final Map<Path, Session> sessions = new LinkedHashMap<>();

    private Path current;

    /** Repaints the counting status line while anything is in flight; null when nothing is. */
    private javafx.animation.Timeline ticker;

    ReviewPanel(Assistant assistant) {
        this.assistant = assistant;
        this.ide = assistant.ide();
        this.view = new MarkdownPane();

        fileLabel.getStyleClass().add("assistant-file");
        status.getStyleClass().add("muted-small");
        wholeProject.setSelected(assistant.config().projectScope());
        wholeProject.setTooltip(new javafx.scene.control.Tooltip(
                "Send the files that call this one, and the ones it calls, as context."
                        + " Off sends only the open file."));
        wholeProject.selectedProperty().addListener((o, was, now) ->
                assistant.config().setProjectScope(now));
        stop.setDisable(true);
        copy.setDisable(true);

        review.setOnAction(e -> start());
        stop.setOnAction(e -> cancel());
        // The panel is where somebody finds out the assistant is not configured, so it
        // is where the way to configure it belongs. Settings > Tools > Assistant is three
        // levels into a tree, which is not somewhere anybody goes looking on a hunch.
        configure.setTooltip(new javafx.scene.control.Tooltip(
                "Which model answers, and what it is allowed to see"));
        configure.setOnAction(e -> ide.showSettings("Tools/Assistant"));
        copy.setOnAction(e -> {
            // The whole conversation, not just the review: by the third question that is
            // what somebody wants to paste into a ticket.
            Session session = current == null ? null : sessions.get(current);
            String text = session == null ? null : session.transcript;
            if (text != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
                ide.statusBar().message("Review copied");
            }
        });

        /* Two rows, and no button that may shrink. A tool window is a third of the width
           of a dialog, and a single row of controls in one turns every button into an
           ellipsis - which is a button you cannot read and will not press. */
        for (Button button : new Button[]{review, stop, copy, configure}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox buttons = new HBox(8, review, stop, copy, configure);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox bar = new VBox(6, buttons, wholeProject);
        bar.setPadding(new Insets(6, 8, 6, 8));

        VBox header = new VBox(2, fileLabel, bar);
        header.setPadding(new Insets(6, 2, 0, 8));

        /* The listing opens what it lists. A reader who wants the file that calls this one
           should not have to read its path off a panel and type it into Go to File.
           Ctrl+click, to match the editor's own go-to gesture, and double-click because
           that is what a list of files means everywhere else. */
        sources.getStyleClass().add("assistant-sources");
        sources.setPrefHeight(112);
        sources.setPlaceholder(new Label("Nothing sent yet."));
        sources.setCellFactory(list -> new SourceCell());
        sources.setOnMouseClicked(e -> {
            boolean ctrlClick = e.getButton() == MouseButton.PRIMARY
                    && e.getClickCount() == 1 && e.isShortcutDown();
            boolean doubleClick = e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2;
            if (ctrlClick || doubleClick) {
                open(sources.getSelectionModel().getSelectedItem());
            }
        });
        sources.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                open(sources.getSelectionModel().getSelectedItem());
            }
        });
        skippedNote.getStyleClass().add("muted-small");
        skippedNote.setWrapText(true);
        skippedNote.setPadding(new Insets(4, 8, 4, 8));
        skippedNote.setManaged(false);
        skippedNote.setVisible(false);
        sourcesPane.setExpanded(false);
        sourcesPane.setAnimated(false);

        /* Asking about the review, which is where most of the value is: the first answer
           is a list of claims, and the conversation after it is where they are tested. */
        question.setPromptText("Ask about this review - why a finding holds, what to do"
                + " about one, or whether it applies here. Ctrl+Enter sends.");
        question.setWrapText(true);
        question.setPrefRowCount(2);
        question.getStyleClass().add("assistant-question");
        question.setDisable(true);
        question.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
                event.consume();
                askQuestion();
            }
        });
        ask.setMinWidth(Region.USE_PREF_SIZE);
        ask.setDisable(true);
        ask.setOnAction(e -> askQuestion());
        HBox.setHgrow(question, Priority.ALWAYS);
        HBox askRow = new HBox(6, question, ask);
        askRow.setAlignment(Pos.BOTTOM_RIGHT);
        askBar = new VBox(askRow);
        askBar.setPadding(new Insets(6, 8, 0, 8));

        HBox footer = new HBox(8, status);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(4, 8, 4, 8));

        setTop(header);
        setCenter(view);
        setBottom(new VBox(askBar, sourcesPane, footer));

        ide.editors().addActiveListener(editor -> onActiveEditor());
        onActiveEditor();
    }

    // -------------------------------------------------------------- the file

    /** Called from the action, so reviewing is one keystroke from the editor. */
    void reviewActive() {
        onActiveEditor();
        if (current != null && !running(current)) {
            start();
        }
    }

    /**
     * Shows whichever file the editor moved to.
     *
     * <p>Nothing is cancelled here. Work in flight belongs to the file it was started on,
     * not to this panel, which is only a window onto it.
     */
    private void onActiveEditor() {
        Optional<Editor> editor = ide.editors().active();
        Path path = editor.map(Editor::path).orElse(null);
        if (path != null && path.equals(current)) {
            return;
        }
        current = path;
        render();
    }

    /** The session for a file, created on first use. */
    private Session session(Path file) {
        return sessions.computeIfAbsent(file, f -> new Session());
    }

    private boolean running(Path file) {
        Session session = sessions.get(file);
        return session != null && session.running;
    }

    private boolean isCurrent(Path file) {
        return file != null && file.equals(current);
    }

    // -------------------------------------------------------------- painting

    /** Paints the whole panel from the current file's session. */
    private void render() {
        if (current == null) {
            fileLabel.setText("No file open");
            view.setFollow(false);
            view.show(placeholder("Open a file and press Review."));
            showSourcesFor(null);
            refresh();
            return;
        }
        fileLabel.setText(current.getFileName().toString());
        Session session = sessions.get(current);
        // Follow the text only while it is arriving. Coming back to a finished review
        // should leave the reader at the top of it, not at the end.
        view.setFollow(session != null && session.streaming != null);
        view.show(body(session));
        showSourcesFor(session);
        refresh();
    }

    /** What belongs in the reading area for a session, which may not exist yet. */
    private String body(Session session) {
        if (session == null) {
            return placeholder(invitation());
        }
        if (session.streaming != null) {
            String text = session.pending + session.streaming;
            return text.isBlank()
                    ? placeholder(session.note.isBlank() ? "Working..." : session.note)
                    : text;
        }
        if (session.transcript != null) {
            return session.transcript;
        }
        return placeholder(session.note.isBlank() ? invitation() : session.note);
    }

    private String invitation() {
        return "Press Review to read **" + current.getFileName() + "** for code smells,"
                + " security problems and technical debt.";
    }

    /** Buttons and the status line, which are only ever about the file on screen. */
    private void refresh() {
        Session session = current == null ? null : sessions.get(current);
        boolean busy = session != null && session.running;
        review.setDisable(busy || current == null);
        stop.setDisable(!busy);
        wholeProject.setDisable(busy);
        copy.setDisable(session == null || session.transcript == null);
        boolean askable = !busy && session != null && session.discussion.isStarted();
        question.setDisable(!askable);
        ask.setDisable(!askable);

        String text = session == null ? "" : session.status;
        if (busy) {
            /* Counted, because a line that has said "Reviewing..." for ninety seconds is
               read as a button that did nothing - and a large model behind a proxy really
               can take that long to say its first word. */
            int seconds = (int) ((System.currentTimeMillis() - session.startedAt) / 1000);
            text = text + "  " + seconds + "s";
            if (seconds >= 20) {
                text += "  -  a large model can take a while to start answering.";
            }
        }
        int others = 0;
        for (Map.Entry<Path, Session> entry : sessions.entrySet()) {
            if (!entry.getKey().equals(current) && entry.getValue().running) {
                others++;
            }
        }
        if (others > 0) {
            // Somebody who started three reviews and walked away should be able to see
            // that they are still running without opening each file to find out.
            text = (text.isBlank() ? "" : text + "  ") + others + " other review"
                    + (others == 1 ? "" : "s") + " still running.";
        }
        status.setText(text);
        tick(busy || others > 0);
    }

    /**
     * Keeps the second counter moving while anything is in flight, and stops it after.
     *
     * <p>One timer for the panel rather than one per file: the only thing it does is
     * repaint a line of text that is only ever about the file on screen.
     */
    private void tick(boolean wanted) {
        if (wanted && ticker == null) {
            ticker = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                            event -> refresh()));
            ticker.setCycleCount(javafx.animation.Animation.INDEFINITE);
            ticker.play();
        } else if (!wanted && ticker != null) {
            ticker.stop();
            ticker = null;
        }
    }

    /** Records a session's status; the panel only shows the current file's. */
    private void setStatus(Session session, String text) {
        session.status = text;
        refresh();
    }

    /** Puts a session's text on screen, if it is the session being watched. */
    private void showIfCurrent(Path file, Session session) {
        if (isCurrent(file)) {
            view.show(body(session));
        }
    }

    // ------------------------------------------------------------- the review

    private void start() {
        Path file = current;
        if (file == null) {
            return;
        }
        Session session = session(file);
        if (session.running) {
            return;
        }
        if (!assistant.config().ready()) {
            session.note = assistant.config().whyNotReady();
            setStatus(session, session.transcript == null ? "" : session.note);
            showIfCurrent(file, session);
            return;
        }
        String text = textOf(file);
        if (text == null) {
            session.note = "That file could not be read as text.";
            showIfCurrent(file, session);
            return;
        }
        Workspace workspace = ide.workspaces().containing(file)
                .orElse(ide.workspaces().active().orElse(null));
        Path root = wholeProject.isSelected() && workspace != null ? workspace.root() : null;

        session.running = true;
        session.startedAt = System.currentTimeMillis();
        session.turn = null;
        session.streaming = null;
        session.generation++;
        int generation = session.generation;
        session.note = "Reading the project...";
        setStatus(session, "Reading the project around " + file.getFileName());
        if (session.transcript == null) {
            showIfCurrent(file, session);
        }

        int budget = assistant.config().intValue("context.totalChars", 90000);
        int maxFiles = assistant.config().intValue("context.maxFiles", 80);
        int perFile = assistant.config().intValue("context.perFileChars", 40000);

        ide.window().runInBackground(() -> {
            CodeContext.Result context = CodeContext.of(root, file, text, budget, maxFiles, perFile);
            ide.window().runLater(() -> {
                if (!session.running || session.generation != generation) {
                    return; // Stopped, or asked again, while the project was being read.
                }
                showSources(file, session, context);
                send(file, session, context);
            });
        });
    }

    private void send(Path file, Session session, CodeContext.Result context) {
        session.streaming = new StringBuilder();
        session.pending = "";
        session.note = "Reviewing...";
        session.lastRender = 0;
        setStatus(session, "Reviewing with " + assistant.config().model());
        if (isCurrent(file)) {
            view.setFollow(true);
        }
        showIfCurrent(file, session);
        session.turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.reviewSystem()),
                        new ChatProvider.Message("user", context.prompt())),
                fragment -> {
                    session.streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    /* Re-rendering Markdown on every token of a long review is most of a
                       core for no gain; five times a second reads as continuous. A review
                       for a file nobody is looking at is not rendered at all - the text is
                       still collected, and drawn once when its tab comes back. */
                    if (isCurrent(file) && now - session.lastRender > 350) {
                        session.lastRender = now;
                        view.show(body(session));
                    }
                },
                whole -> {
                    session.transcript = whole;
                    session.streaming = null;
                    session.note = "";
                    session.running = false;
                    session.turn = null;
                    // A fresh review starts a fresh conversation: the questions about the
                    // last one were about findings that may no longer be there.
                    session.discussion.start(context.prompt(), whole);
                    setStatus(session, "Reviewed " + file.getFileName() + " - findings are the"
                            + " model's reading, not a compiler's. Ask about any of them.");
                    showIfCurrent(file, session);
                    announceIfAway(file, "Review ready: ", "The assistant has finished reading "
                            + file.getFileName() + ".");
                },
                error -> {
                    session.streaming = null;
                    session.running = false;
                    session.turn = null;
                    session.note = error;
                    setStatus(session, session.transcript == null ? "" : error);
                    showIfCurrent(file, session);
                    if (!isCurrent(file)) {
                        ide.notifications().error("Review failed: " + file.getFileName(), error);
                    }
                });
    }

    /**
     * Says that something has landed for a file that is not on screen.
     *
     * <p>Otherwise the work finishes and nobody is told, which for something that takes a
     * minute means it finishes and is forgotten.
     */
    private void announceIfAway(Path file, String title, String message) {
        if (isCurrent(file)) {
            return;
        }
        ide.notifications().info(title + file.getFileName(), message,
                new Notifications.NotificationAction("Open", () -> open(file)));
    }

    // --------------------------------------------------------- the conversation

    /**
     * Asks about the review, carrying the code, the findings and the earlier questions.
     *
     * <p>The transcript is one Markdown document that grows: the review, then each question
     * and what it got. That is what is shown, what Copy copies, and what comes back when
     * the file is opened again.
     */
    private void askQuestion() {
        Path file = current;
        if (file == null) {
            return;
        }
        Session session = session(file);
        if (session.running) {
            return;
        }
        if (!session.discussion.isStarted()) {
            setStatus(session, "Review the file first; the questions are about the review.");
            return;
        }
        String asked = question.getText().strip();
        if (asked.isEmpty()) {
            return;
        }
        if (!assistant.config().ready()) {
            setStatus(session, assistant.config().whyNotReady());
            return;
        }
        question.clear();
        String before = (session.transcript == null ? "" : session.transcript)
                + "\n\n---\n\n#### You asked\n\n" + asked + "\n\n#### Answer\n\n";
        session.transcript = before;
        session.pending = before;
        session.streaming = new StringBuilder();
        session.note = "";
        session.lastRender = 0;
        session.running = true;
        session.startedAt = System.currentTimeMillis();
        setStatus(session, "Thinking");
        if (isCurrent(file)) {
            view.setFollow(true);
            view.show(before + "*thinking...*");
        }

        int window = assistant.config().intValue("context.windowChars", 120000);
        session.turn = assistant.ask(
                session.discussion.request(Prompts.discussSystem(), asked, window),
                fragment -> {
                    session.streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (isCurrent(file) && now - session.lastRender > 350) {
                        session.lastRender = now;
                        view.show(body(session));
                    }
                },
                whole -> {
                    session.running = false;
                    session.turn = null;
                    session.streaming = null;
                    session.discussion.record(asked, whole);
                    session.transcript = before + whole;
                    showIfCurrent(file, session);
                    int dropped = session.discussion.dropped();
                    int exchanges = session.discussion.exchanges();
                    setStatus(session, exchanges + " question" + (exchanges == 1 ? "" : "s")
                            + " about this review."
                            + (dropped == 0 ? ""
                                    : "  " + dropped + " earlier one" + (dropped == 1 ? "" : "s")
                                            + " no longer fit the context window."));
                    announceIfAway(file, "Answer ready: ",
                            "The assistant has answered your question about "
                                    + file.getFileName() + ".");
                },
                error -> {
                    session.running = false;
                    session.turn = null;
                    session.streaming = null;
                    // The question goes back in the box: retyping it is not the developer's
                    // job when the endpoint was the thing that failed.
                    if (isCurrent(file)) {
                        question.setText(asked);
                    }
                    session.transcript = before.substring(0,
                            before.length() - "#### Answer\n\n".length()) + "*" + error + "*";
                    showIfCurrent(file, session);
                    setStatus(session, "");
                    if (!isCurrent(file)) {
                        ide.notifications().error("Question failed: " + file.getFileName(), error);
                    }
                });
    }

    /** Stop, which is about the file on screen and no other. */
    private void cancel() {
        Path file = current;
        Session session = file == null ? null : sessions.get(file);
        if (session == null || !session.running) {
            return;
        }
        session.running = false;
        session.generation++;
        if (session.turn != null) {
            session.turn.cancel();
            session.turn = null;
        }
        /* Whatever arrived before Stop is kept: half a review still names findings, and
           throwing it away is a second loss on top of the one just chosen. It is marked
           as stopped so nothing reads as a complete answer that is not one. */
        if (session.streaming != null && !session.streaming.isEmpty()) {
            session.transcript = session.pending + session.streaming + "\n\n*(stopped)*";
        }
        session.streaming = null;
        session.note = session.transcript == null ? "Stopped." : "";
        setStatus(session, "Stopped.");
        showIfCurrent(file, session);
    }

    private void showSources(Path file, Session session, CodeContext.Result context) {
        session.sources = context.included();
        StringBuilder notes = new StringBuilder();
        for (String line : context.skipped()) {
            notes.append(notes.isEmpty() ? "Not sent: " : "; ").append(line);
        }
        session.skipped = notes.toString();
        if (isCurrent(file)) {
            showSourcesFor(session);
        }
    }

    /** Puts one session's listing on screen, with the count on the pane's title. */
    private void showSourcesFor(Session session) {
        List<CodeContext.Source> listed = session == null ? List.of() : session.sources;
        sources.getItems().setAll(listed);
        sourcesPane.setText(listed.isEmpty() ? "Sources"
                : "Sources  (" + listed.size() + " files)");
        String notes = session == null ? "" : session.skipped;
        skippedNote.setText(notes);
        skippedNote.setManaged(!notes.isBlank());
        skippedNote.setVisible(!notes.isBlank());
    }

    /** Opens a listed file in the editor, which is the point of listing it. */
    private void open(CodeContext.Source source) {
        if (source != null) {
            open(source.path());
        }
    }

    private void open(Path path) {
        try {
            ide.editors().open(path);
        } catch (RuntimeException e) {
            ide.notifications().error("Could not open " + path.getFileName(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** The path, with what put it in the prompt beside it. */
    private static final class SourceCell extends javafx.scene.control.ListCell<CodeContext.Source> {

        @Override
        protected void updateItem(CodeContext.Source source, boolean empty) {
            super.updateItem(source, empty);
            if (empty || source == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(source.relative() + (source.why().isBlank() ? "" : "   " + source.why()));
            setTooltip(new javafx.scene.control.Tooltip(
                    source.path() + "\n\nCtrl+click or double-click to open."));
        }
    }

    /** The open editor's text, which may be ahead of the file, else what is on disk. */
    private String textOf(Path file) {
        Optional<TextEditor> editor = ide.editors().find(file).flatMap(Editor::asText);
        if (editor.isPresent()) {
            return editor.get().text();
        }
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    /** A note rather than an answer, set apart as a quote so it cannot be read as one. */
    private static String placeholder(String markdown) {
        StringBuilder quoted = new StringBuilder();
        for (String line : markdown.split("\n")) {
            quoted.append("> ").append(line).append('\n');
        }
        return quoted.toString();
    }
}
