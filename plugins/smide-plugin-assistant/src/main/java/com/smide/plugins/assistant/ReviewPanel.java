package com.smide.plugins.assistant;

import com.mdviewer.ai.ChatProvider;
import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.editor.TextEditor;
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
 * <p>It reports. It never rewrites: there is no Apply button here, and there is not going
 * to be one.
 */
final class ReviewPanel extends BorderPane {

    private final Assistant assistant;
    private final Ide ide;
    private final MarkdownView view;

    private final Label fileLabel = new Label("No file open");
    private final Label status = new Label();
    private final Button review = new Button("Review");
    private final Button stop = new Button("Stop");
    private final Button copy = new Button("Copy");
    private final CheckBox wholeProject = new CheckBox("Look at related files");
    private final ListView<CodeContext.Source> sources = new ListView<>();
    private final Label skippedNote = new Label();
    private final TitledPane sourcesPane = new TitledPane("Sources", new VBox(sources, skippedNote));
    private final TextArea question = new TextArea();
    private final Button ask = new Button("Ask");
    private final VBox askBar;

    /**
     * Everything about one file, kept so that switching tabs and coming back does not
     * throw away a review that cost a minute of a model's time - or the conversation
     * about it, which by then is worth more than the review was.
     */
    private final Map<Path, String> transcripts = new LinkedHashMap<>();
    private final Map<Path, Discussion> discussions = new LinkedHashMap<>();
    private final Map<Path, List<CodeContext.Source>> sourceLists = new LinkedHashMap<>();
    private final Map<Path, String> skippedLists = new LinkedHashMap<>();

    private Path current;
    private Assistant.Turn turn;
    private StringBuilder streaming;
    private long lastRender;

    ReviewPanel(Assistant assistant) {
        this.assistant = assistant;
        this.ide = assistant.ide();
        this.view = new MarkdownView(ide.theme());

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
        copy.setOnAction(e -> {
            // The whole conversation, not just the review: by the third question that is
            // what somebody wants to paste into a ticket.
            String text = current == null ? null : transcripts.get(current);
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
        for (Button button : new Button[]{review, stop, copy}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox buttons = new HBox(8, review, stop, copy);
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
        if (current != null && (turn == null)) {
            start();
        }
    }

    private void onActiveEditor() {
        Optional<Editor> editor = ide.editors().active();
        Path path = editor.map(Editor::path).orElse(null);
        if (path != null && path.equals(current)) {
            return;
        }
        // A review in flight belongs to the file it was started on; switching away is as
        // good as saying it is no longer wanted.
        cancel();
        current = path;
        if (path == null) {
            fileLabel.setText("No file open");
            review.setDisable(true);
            copy.setDisable(true);
            view.show(placeholder("Open a file and press Review."));
            sources.getItems().clear();
            sourcesPane.setText("Sources");
            setAskable(false);
            status.setText("");
            return;
        }
        review.setDisable(false);
        fileLabel.setText(path.getFileName().toString());
        String previous = transcripts.get(path);
        copy.setDisable(previous == null);
        view.show(previous != null ? previous
                : placeholder("Press Review to read **" + path.getFileName() + "** for code"
                        + " smells, security problems and technical debt."));
        showSourcesFor(path);
        setAskable(previous != null);
        if (previous == null) {
            status.setText("");
        } else {
            int asked = discussion(path).exchanges();
            status.setText("Reviewed earlier in this session."
                    + (asked == 0 ? "" : "  " + asked + " question" + (asked == 1 ? "" : "s")
                            + " since."));
        }
    }

    /** The conversation about a file, created on first use. */
    private Discussion discussion(Path file) {
        return discussions.computeIfAbsent(file, f -> new Discussion());
    }

    /** Whether there is a review to ask about. */
    private void setAskable(boolean askable) {
        question.setDisable(!askable);
        ask.setDisable(!askable);
    }

    // ------------------------------------------------------------- the review

    private void start() {
        if (current == null || turn != null) {
            return;
        }
        if (!assistant.config().ready()) {
            view.show(placeholder(assistant.config().whyNotReady()));
            return;
        }
        Path file = current;
        String text = textOf(file);
        if (text == null) {
            view.show(placeholder("That file could not be read as text."));
            return;
        }
        Workspace workspace = ide.workspaces().containing(file)
                .orElse(ide.workspaces().active().orElse(null));
        Path root = wholeProject.isSelected() && workspace != null ? workspace.root() : null;

        setBusy(true);
        status.setText("Reading the project around " + file.getFileName() + "...");
        view.show(placeholder("Reading the project..."));

        int budget = assistant.config().intValue("context.totalChars", 90000);
        int maxFiles = assistant.config().intValue("context.maxFiles", 80);
        int perFile = assistant.config().intValue("context.perFileChars", 40000);

        ide.window().runInBackground(() -> {
            CodeContext.Result context = CodeContext.of(root, file, text, budget, maxFiles, perFile);
            ide.window().runLater(() -> {
                if (!file.equals(current)) {
                    setBusy(false);
                    return; // Moved on while the project was being read.
                }
                showSources(file, context);
                send(file, context);
            });
        });
    }

    private void send(Path file, CodeContext.Result context) {
        streaming = new StringBuilder();
        lastRender = 0;
        status.setText("Reviewing with " + assistant.config().model() + "...");
        view.setFollow(true);
        view.show(placeholder("Reviewing..."));
        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.reviewSystem()),
                        new ChatProvider.Message("user", context.prompt())),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    // Re-rendering Markdown on every token of a long review is most of a
                    // core for no gain; five times a second reads as continuous.
                    if (now - lastRender > 180) {
                        lastRender = now;
                        view.show(streaming.toString());
                    }
                },
                whole -> {
                    transcripts.put(file, whole);
                    // A fresh review starts a fresh conversation: the questions about the
                    // last one were about findings that may no longer be there.
                    discussion(file).start(context.prompt(), whole);
                    view.show(whole);
                    setBusy(false);
                    copy.setDisable(false);
                    setAskable(true);
                    status.setText("Reviewed " + file.getFileName() + " - findings are the"
                            + " model's reading, not a compiler's. Ask about any of them.");
                    turn = null;
                },
                error -> {
                    view.show(placeholder(error));
                    setBusy(false);
                    status.setText("");
                    turn = null;
                });
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
        if (file == null || turn != null) {
            return;
        }
        Discussion discussion = discussion(file);
        if (!discussion.isStarted()) {
            status.setText("Review the file first; the questions are about the review.");
            return;
        }
        String asked = question.getText().strip();
        if (asked.isEmpty()) {
            return;
        }
        if (!assistant.config().ready()) {
            status.setText(assistant.config().whyNotReady());
            return;
        }
        question.clear();
        String before = transcripts.getOrDefault(file, "")
                + "\n\n---\n\n#### You asked\n\n" + asked + "\n\n#### Answer\n\n";
        transcripts.put(file, before);
        streaming = new StringBuilder();
        lastRender = 0;
        setBusy(true);
        setAskable(false);
        status.setText("Thinking...");
        view.setFollow(true);
        view.show(before + "*thinking...*");

        int window = assistant.config().intValue("context.windowChars", 120000);
        turn = assistant.ask(
                discussion.request(Prompts.discussSystem(), asked, window),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 180) {
                        lastRender = now;
                        view.show(before + streaming);
                    }
                },
                whole -> {
                    turn = null;
                    discussion.record(asked, whole);
                    String full = before + whole;
                    transcripts.put(file, full);
                    view.show(full);
                    setBusy(false);
                    setAskable(true);
                    int dropped = discussion.dropped();
                    status.setText(discussion.exchanges() + " question"
                            + (discussion.exchanges() == 1 ? "" : "s") + " about this review."
                            + (dropped == 0 ? ""
                                    : "  " + dropped + " earlier one" + (dropped == 1 ? "" : "s")
                                            + " no longer fit the context window."));
                },
                error -> {
                    turn = null;
                    // The question goes back in the box: retyping it is not the developer's
                    // job when the endpoint was the thing that failed.
                    question.setText(asked);
                    transcripts.put(file, before.substring(0,
                            before.length() - "#### Answer\n\n".length()) + "*" + error + "*");
                    view.show(transcripts.get(file));
                    setBusy(false);
                    setAskable(true);
                    status.setText("");
                });
    }

    private void cancel() {
        if (turn != null) {
            turn.cancel();
            turn = null;
            setBusy(false);
            setAskable(current != null && discussion(current).isStarted());
            status.setText("Stopped.");
        }
    }

    private void setBusy(boolean busy) {
        review.setDisable(busy || current == null);
        stop.setDisable(!busy);
        wholeProject.setDisable(busy);
    }

    private void showSources(Path file, CodeContext.Result context) {
        sourceLists.put(file, context.included());
        StringBuilder notes = new StringBuilder();
        for (String line : context.skipped()) {
            notes.append(notes.isEmpty() ? "Not sent: " : "; ").append(line);
        }
        skippedLists.put(file, notes.toString());
        showSourcesFor(file);
    }

    /** Puts one file's listing on screen, with the count on the pane's title. */
    private void showSourcesFor(Path file) {
        List<CodeContext.Source> listed = sourceLists.getOrDefault(file, List.of());
        sources.getItems().setAll(listed);
        sourcesPane.setText(listed.isEmpty() ? "Sources"
                : "Sources  (" + listed.size() + " files)");
        String notes = skippedLists.getOrDefault(file, "");
        skippedNote.setText(notes);
        skippedNote.setManaged(!notes.isBlank());
        skippedNote.setVisible(!notes.isBlank());
    }

    /** Opens a listed file in the editor, which is the point of listing it. */
    private void open(CodeContext.Source source) {
        if (source == null) {
            return;
        }
        try {
            ide.editors().open(source.path());
        } catch (RuntimeException e) {
            ide.notifications().error("Could not open " + source.relative(),
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
