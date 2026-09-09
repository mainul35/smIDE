package com.smide.plugins.assistant;

import com.mdviewer.ai.ChatProvider;
import com.smide.api.Ide;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A test session: one problem at a time, answered in the IDE, marked when submitted.
 *
 * <p>Ask for a topic - SQL, CSS, Python, data structures, the Stream API, generics, Kafka -
 * and the assistant sets a question, waits for an answer, marks it against the scheme it
 * wrote when it set the question, and offers the next one. Code questions are answered in
 * a code editor; the ones whose answer is prose, which is most of what there is to say
 * about Kafka, are answered in Markdown.
 *
 * <p>The marking scheme is written before the answer exists and is sent back with it. That
 * is the difference between marking and agreeing: a model shown only an answer will find
 * something to praise in almost any of them.
 */
final class PracticePanel extends BorderPane {

    /** The language choice that means "whatever suits the question". */
    private static final String ANY_LANGUAGE = "Any language";

    /** Where the choice is remembered between sessions. */
    private static final String LANGUAGE_KEY = "assistant.practiceLanguage";

    /** Starting points, not a menu: the box is editable and anything typed is a topic. */
    private static final List<String> TOPICS = List.of(
            "SQL queries", "SQL schema design", "Data structures", "Algorithms",
            "Java Stream API", "Java generics", "Java concurrency", "Spring Boot",
            "Kafka", "REST API design", "Python", "CSS", "JavaScript", "TypeScript",
            "Regular expressions", "Git", "Docker", "System design", "Security");

    /**
     * What a code answer may be written in.
     *
     * <p>Half the topics here - data structures, algorithms, system design, REST - are
     * not about any one language, and somebody working through them is usually learning a
     * language at the same time. Left to itself the assistant picks whatever it likes,
     * which is Java about four times in five.
     */
    private static final List<String> LANGUAGES = List.of(
            ANY_LANGUAGE, "Java", "Python", "JavaScript", "TypeScript", "Kotlin", "Go",
            "Rust", "C#", "C++", "C", "SQL", "Bash", "CSS", "HTML", "PHP", "Ruby", "Scala");

    private final Assistant assistant;
    private final Ide ide;

    /** Teaching, then testing. A session is one and then the other. */
    private enum Stage { IDLE, TUTORIAL, PRACTICE }

    private final ComboBox<String> topic = new ComboBox<>();
    private final ChoiceBox<String> difficulty = new ChoiceBox<>();
    private final ChoiceBox<String> language = new ChoiceBox<>();
    private final Button start = new Button("Start session");
    private final Button next = new Button("Next question");
    private final Button submit = new Button("Submit answer");
    private final Button hint = new Button("Hint");
    private final Button ask = new Button("Ask");
    private final javafx.scene.control.TextField askField = new javafx.scene.control.TextField();
    private final Button stop = new Button("Stop");
    private final Button understood = new Button("I have read this - start practice");
    private final Button skip = new Button("Skip the tutorial");
    private final Label score = new Label();
    private final Label status = new Label();
    private final Label answerLabel = new Label("Your answer");
    private final HBox acknowledge = new HBox(8, understood, skip);

    private final MarkdownPane view;
    private final AnswerEditor answer;
    /* A model behind a proxy can take a minute to say its first word. A status line that
       does not move for a minute is read as a button that did nothing. */
    private final Waiting waiting;
    private SplitPane split;
    private VBox answerBox;

    /** Titles already set this session, so the next question is a different one. */
    private final List<String> asked = new ArrayList<>();
    /** The topic the session is actually on, which the box is not allowed to contradict. */
    private String chosenTopic;
    /** True while the box is being rewritten, so its own edits are not read as typing. */
    private boolean filtering;
    private Stage stage = Stage.IDLE;
    /** The tutorial just read, carried into every question so the exercise is not a copy. */
    private String taught = "";
    private PracticeQuestion question;
    /** The question and any hints given on it: what the view is showing. */
    private String onScreen = "";
    /** Hints asked for on this question, so they can get more concrete. */
    private int hints;
    /** Questions asked and answered on this question, so a follow-up follows something. */
    private final List<String> exchanges = new ArrayList<>();
    /** Set when a submission looked like a command, so pressing again sends it anyway. */
    private boolean submitAnyway;
    private Assistant.Turn turn;
    private StringBuilder streaming;
    private long lastRender;
    private int answered;
    private int correct;

    PracticePanel(Assistant assistant) {
        this.assistant = assistant;
        this.ide = assistant.ide();
        this.view = new MarkdownPane(ide);
        this.answer = new AnswerEditor(ide);
        this.waiting = new Waiting(status);

        topic.getItems().setAll(TOPICS);
        topic.setEditable(true);
        topic.setValue("SQL queries");
        topic.setPrefWidth(210);
        topic.setTooltip(new Tooltip("Anything you want to practise. Type your own."));
        chosenTopic = topic.getValue();
        topic.valueProperty().addListener((o, was, now) -> {
            // Not while the box is filtering itself: the value it sets there is the half
            // word being typed, and taking that as the subject would leave Escape with
            // nothing better to restore than the half word.
            if (!filtering && now != null && !now.isBlank()) {
                chosenTopic = now.strip();
            }
        });
        /* Escape closes the list and does nothing else.
           Left to the skin it cancels the edit, and cancelling an editable ComboBox rolls
           its value back to an earlier one - so pressing Escape over an open list, which
           is the ordinary way to change your mind about opening it, silently changed the
           subject of the session. The next question came back on SQL in the middle of a
           session on generics, with the box quietly reading SQL again and nothing to say
           it had. A filter, so the skin never sees the key at all. */
        topic.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ESCAPE) {
                return;
            }
            if (topic.isShowing()) {
                topic.hide();
            }
            event.consume();
            restoreTopic();
        });
        /* Typing narrows the list. Nineteen suggestions is more than anybody reads, and
           the box being editable made it worse rather than better: the list stayed at
           nineteen while what you typed had nothing to do with any of them. What is typed
           is still a topic in its own right - "Java" narrows to three and "Kafka consumer
           groups" narrows to nothing and is asked anyway. */
        topic.getEditor().textProperty().addListener((o, was, now) -> filterTopics(now));
        difficulty.getItems().setAll("easy", "medium", "hard", "mixed");
        difficulty.setValue("medium");
        language.getItems().setAll(LANGUAGES);
        language.setValue(ide.settings().get(LANGUAGE_KEY, ANY_LANGUAGE));
        language.setTooltip(new Tooltip("What code answers are written in. Topics that are"
                + " about one language - Java generics, CSS - keep their own."));
        // Remembered: somebody learning Go is learning Go next week as well.
        language.valueProperty().addListener((o, was, now) ->
                ide.settings().set(LANGUAGE_KEY, now == null ? ANY_LANGUAGE : now));
        score.getStyleClass().add("muted-small");
        status.getStyleClass().add("muted-small");
        answerLabel.getStyleClass().add("assistant-file");

        start.setOnAction(e -> startSession());
        next.setOnAction(e -> askQuestion());
        submit.setOnAction(e -> submit());
        hint.setOnAction(e -> askHint());
        hint.setTooltip(new Tooltip("A nudge, not the answer. Ask again for a"
                + " bigger one. Typing /hint in the answer does the same."));
        ask.setOnAction(e -> askAbout(askField.getText()));
        ask.setTooltip(new Tooltip("Ask about the subject - what a clause does, why a"
                + " structure behaves that way. It answers the question without answering"
                + " the exercise."));
        askField.setPromptText("Ask about this - what a clause does, why something behaves"
                + " that way. Enter asks.");
        askField.getStyleClass().add("assistant-question");
        askField.setOnAction(e -> askAbout(askField.getText()));
        stop.setOnAction(e -> cancel());
        understood.setOnAction(e -> beginPractice());
        /* Skipping is for the third session on the same topic, not for the first. It stays
           the quieter of the two, and what was written is still carried into the questions
           - a tutorial nobody read is still a list of what this session is not going to
           ask about twice. */
        skip.setOnAction(e -> beginPractice());
        next.setDisable(true);
        submit.setDisable(true);
        hint.setDisable(true);
        ask.setDisable(true);
        askField.setDisable(true);
        stop.setDisable(true);

        /* The topic on one row and the buttons on the next. All of this on one row fits a
           dialog and not a tool window, where it collapses into a row of ellipses. */
        HBox.setHgrow(topic, Priority.ALWAYS);
        topic.setMaxWidth(Double.MAX_VALUE);
        difficulty.setMinWidth(Region.USE_PREF_SIZE);
        language.setMinWidth(Region.USE_PREF_SIZE);
        HBox subject = new HBox(8, topic);
        subject.setAlignment(Pos.CENTER_LEFT);
        HBox options = new HBox(8, difficulty, language);
        options.setAlignment(Pos.CENTER_LEFT);
        for (Button button : new Button[]{start, next, stop, submit, hint, ask}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox buttons = new HBox(8, start, next, stop, spacer(), score);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox bar = new VBox(6, subject, options, buttons);
        bar.setPadding(new Insets(6, 8, 6, 8));

        HBox answerBar = new HBox(8, answerLabel, spacer(), hint, submit);
        answerBar.setAlignment(Pos.CENTER_LEFT);
        answerBar.setPadding(new Insets(4, 8, 4, 8));

        /* A question is not an answer, so it has its own box. Sharing the answer editor
           would mean somebody with half a query written having to delete it to ask what a
           clause does. */
        HBox.setHgrow(askField, Priority.ALWAYS);
        askField.setMaxWidth(Double.MAX_VALUE);
        HBox askBar = new HBox(8, askField, ask);
        askBar.setAlignment(Pos.CENTER_LEFT);
        askBar.setPadding(new Insets(0, 8, 4, 8));

        VBox answerBox = new VBox(answerBar, askBar, answer);
        VBox.setVgrow(answer, Priority.ALWAYS);
        this.answerBox = answerBox;

        split = new SplitPane(view, answerBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.58);
        com.smide.api.ui.Splits.grabbable(split);

        /* The gate between reading and being asked. It sits under the tutorial with
           nothing else on the row, because it is the only thing to do at that point. */
        understood.setDefaultButton(false);
        understood.getStyleClass().add("primary-button");
        for (Button button : new Button[]{understood, skip}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        acknowledge.setAlignment(Pos.CENTER_LEFT);
        acknowledge.setPadding(new Insets(6, 8, 2, 8));
        acknowledge.setVisible(false);
        acknowledge.setManaged(false);

        HBox footer = new HBox(status);
        footer.setPadding(new Insets(4, 8, 4, 8));

        setTop(bar);
        setCenter(split);
        setBottom(new VBox(acknowledge, footer));

        // Ctrl+Enter submits, which is where the hands already are.
        sceneProperty().addListener((o, was, now) -> {
            if (now != null) {
                now.getAccelerators().put(
                        new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN),
                        () -> {
                            if (!submit.isDisabled() && answer.area().isFocused()) {
                                submit();
                            }
                        });
            }
        });

        view.show(welcome());
        answer.setEditable(false);
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /**
     * What to practise: whatever is in the box, typed or chosen.
     *
     * <p>An editable ComboBox does not commit typed text to its value until Enter or a
     * focus change, so reading {@code getValue()} loses a topic somebody typed and then
     * pressed the button beside - which is most of how a box with a free-text field gets
     * used.
     */
    /** The language asked for, or empty when the assistant may choose. */
    private String chosenLanguage() {
        String chosen = language.getValue();
        return chosen == null || ANY_LANGUAGE.equals(chosen) ? "" : chosen;
    }

    private String topicText() {
        String typed = topic.getEditor() == null ? null : topic.getEditor().getText();
        if (typed != null && !typed.isBlank()) {
            return typed.strip();
        }
        String value = topic.getValue();
        return value == null ? "" : value.strip();
    }

    /** Puts the topic in use back in the box, value and editor together. */
    private void restoreTopic() {
        if (chosenTopic == null || chosenTopic.isBlank()) {
            return;
        }
        applyTopic(chosenTopic);
        /* And again on the next pulse. Putting the whole list back is an items change,
           and the skin answers an items change by rewriting the editor - after this
           method has returned, so doing it once left the box empty. */
        javafx.application.Platform.runLater(() -> applyTopic(chosenTopic));
    }

    /** Puts one subject in the box, list and all, without it counting as typing. */
    private void applyTopic(String subject) {
        filtering = true;
        try {
            if (!TOPICS.equals(topic.getItems())) {
                topic.getItems().setAll(TOPICS);
            }
            if (!subject.equals(topic.getValue())) {
                topic.setValue(subject);
            }
            if (topic.getEditor() != null && !subject.equals(topic.getEditor().getText())) {
                topic.getEditor().setText(subject);
                topic.getEditor().positionCaret(subject.length());
            }
        } finally {
            filtering = false;
        }
    }

    /**
     * Narrows the suggestions to what has been typed.
     *
     * <p>Replacing the items empties the editor - the skin treats it as the value having
     * gone - so what was being typed is put back, with the caret where it was. Without
     * that the box eats every second character and the whole thing reads as broken.
     */
    private void filterTopics(String typed) {
        if (filtering || topic.getEditor() == null) {
            return;
        }
        String text = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        List<String> matches = text.isEmpty() ? TOPICS : TOPICS.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).contains(text))
                .toList();
        /* The list never goes empty, and the value is kept equal to what is in the box.
           Both are about the same skin behaviour: when the items change and the value is
           no longer one of them, the selection is cleared and the editor is emptied with
           it - not always in the same breath, so putting the text back immediately is not
           enough. Typing "kafka consumer groups" lost its first six characters that way,
           at the keystroke where the matches ran out. */
        List<String> shown = matches.isEmpty() ? TOPICS : matches;
        filtering = true;
        try {
            String editing = topic.getEditor().getText();
            int caret = topic.getEditor().getCaretPosition();
            if (!shown.equals(topic.getItems())) {
                topic.getItems().setAll(shown);
            }
            topic.setValue(editing);
            if (!java.util.Objects.equals(editing, topic.getEditor().getText())) {
                topic.getEditor().setText(editing);
            }
            topic.getEditor().positionCaret(Math.min(caret,
                    editing == null ? 0 : editing.length()));
        } finally {
            filtering = false;
        }
        if (matches.isEmpty()) {
            // A topic of their own. Nothing to show, and nothing wrong with it.
            topic.hide();
            return;
        }
        if (!text.isEmpty() && topic.getEditor().isFocused() && !topic.isShowing()) {
            topic.show();
        }
    }

    private String welcome() {
        return """
                ### Practice

                Pick a topic and press **Start session**. It begins with a short tutorial -
                what the thing is, where you actually meet it, the rules that decide whether
                code works, and where it goes wrong. Read it, then press **I have read this**
                and the questions start.

                Stuck is allowed: **Hint** - or `/hint` in the answer box - asks for a
                nudge rather than the answer, and asking again gives a bigger one. The
                **Ask** box under your answer is for the subject rather than the exercise:
                what a clause does, why something behaves that way. It answers the question
                without answering the question you are on.

                A question arrives one at a time; write your answer below and press
                **Submit answer** (Ctrl+Enter) to have it marked, with what was right, what
                was missing, and what to do differently. Questions are set against what the
                tutorial covered, so they ask you to apply it rather than repeat it.

                Code questions - SQL, Python, CSS, a data structure - are answered in the
                editor below, coloured for the language. Questions whose answer is prose are
                answered in Markdown in the same place.

                The assistant teaches, marks and explains. It does not write the answer for
                you.
                """;
    }

    // ------------------------------------------------------------ the tutorial

    private void startSession() {
        asked.clear();
        answered = 0;
        correct = 0;
        taught = "";
        score.setText("");
        askTutorial();
    }

    /** The teaching half: one tutorial on the topic, then a gate the developer presses. */
    private void askTutorial() {
        if (busy()) {
            return;
        }
        if (!assistant.config().ready()) {
            view.show("> " + assistant.config().whyNotReady());
            return;
        }
        String subject = topicText();
        if (subject.isEmpty()) {
            status.setText("Name something to practise first.");
            return;
        }
        chosenTopic = subject;
        stage = Stage.TUTORIAL;
        question = null;
        showAcknowledge(false);
        showAnswerBox(false);
        setBusy(true);
        waiting.start("Writing a tutorial on " + subject);
        view.setFollow(true);
        view.show("> Writing a tutorial on **" + subject + "**...");

        streaming = new StringBuilder();
        lastRender = 0;
        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.tutorialSystem()),
                        new ChatProvider.Message("user",
                                Prompts.tutorialRequest(subject, difficulty.getValue(), chosenLanguage()))),
                fragment -> {
                    // Shown as it arrives: a tutorial has nothing hidden in it, and reading
                    // can start at the top while the rest is still coming.
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 350) {
                        lastRender = now;
                        view.show(streaming.toString());
                    }
                },
                whole -> {
                    turn = null;
                    taught = whole;
                    setBusy(false);
                    view.show(whole);
                    showAcknowledge(true);
                    waiting.stop("Read it, then press I have read this.");
                },
                error -> {
                    turn = null;
                    stage = Stage.IDLE;
                    setBusy(false);
                    view.show("> " + error);
                    waiting.stop("No tutorial came back. Press Start session to try again.");
                });
    }

    /** The gate: the developer says they have read it, and the questions begin. */
    private void beginPractice() {
        if (busy()) {
            return;
        }
        showAcknowledge(false);
        showAnswerBox(true);
        stage = Stage.PRACTICE;
        askQuestion();
    }

    private void showAcknowledge(boolean show) {
        acknowledge.setVisible(show);
        acknowledge.setManaged(show);
    }

    /** The answer editor is only there once there is something to answer. */
    private void showAnswerBox(boolean show) {
        boolean present = split.getItems().contains(answerBox);
        if (show && !present) {
            split.getItems().setAll(view, answerBox);
            split.setDividerPositions(0.58);
        } else if (!show && present) {
            split.getItems().setAll(view);
        }
    }

    // ------------------------------------------------------------- the session

    private void askQuestion() {
        if (busy()) {
            return;
        }
        if (!assistant.config().ready()) {
            view.show("> " + assistant.config().whyNotReady());
            return;
        }
        String subject = topicText();
        if (subject.isEmpty()) {
            status.setText("Name something to practise first.");
            return;
        }
        // Committed at the moment it is used, so a typed topic that was never entered
        // with Enter is still the one the session is on.
        chosenTopic = subject;
        stage = Stage.PRACTICE;
        showAcknowledge(false);
        showAnswerBox(true);
        question = null;
        submit.setDisable(true);
        answer.reset("text", "");
        answer.setEditable(false);
        setBusy(true);
        waiting.start("Setting a question on " + subject);
        view.show("> Writing a question on **" + subject + "**...");

        streaming = new StringBuilder();
        lastRender = 0;
        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.questionSystem()),
                        new ChatProvider.Message("user", Prompts.questionRequest(subject,
                                difficulty.getValue(), chosenLanguage(), asked, taught))),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 350) {
                        lastRender = now;
                        // The rubric is in the same reply, so nothing is rendered until
                        // it is complete - showing the marking scheme as it streams past
                        // would hand the developer the answer.
                        view.show("> Writing a question... ("
                                + streaming.length() + " characters)");
                    }
                },
                whole -> {
                    turn = null;
                    question = PracticeQuestion.parse(whole);
                    asked.add(question.title());
                    // After the title is recorded: Next is enabled by there being a
                    // session, and the session begins with its first question.
                    hints = 0;
                    exchanges.clear();
                    askField.clear();
                    submitAnyway = false;
                    onScreen = question.markdown();
                    setBusy(false);
                    view.show(onScreen);
                    hint.setDisable(false);
                    answerLabel.setText(question.isTheory()
                            ? "Your answer  (Markdown)" : "Your answer  (" + question.language() + ")");
                    answer.reset(question.isTheory() ? "markdown" : question.language(), "");
                    answer.setEditable(true);
                    answer.area().requestFocus();
                    submit.setDisable(false);
                    waiting.stop("Question " + asked.size() + ". Ctrl+Enter submits, /hint if you"
                            + " are stuck, Ask for anything else.");
                },
                error -> {
                    turn = null;
                    setBusy(false);
                    view.show("> " + error);
                    waiting.stop("No question was set. Press Next question to try again.");
                });
    }

    // --------------------------------------------------------------- being stuck

    /**
     * Whether what was submitted was somebody asking for help rather than answering.
     *
     * <p>Somebody stuck typed {@code /help} into the answer box, on the reasonable
     * assumption that a box in an IDE knows what a slash command is, and had it marked
     * nought out of ten for not being a Dockerfile. Being stuck is the moment a practice
     * session either teaches something or loses the person, and the least it can do is
     * recognise the word.
     *
     * <p>Anything else beginning with a slash gets one warning rather than a mark: paths
     * and regular expressions start that way too, so the second press sends it as the
     * answer.
     */
    private boolean command(String submission) {
        String text = submission.strip();
        if (!text.startsWith("/") || text.contains("\n")) {
            submitAnyway = false;
            return false;
        }
        String[] parts = text.split("\\s+", 2);
        String word = parts[0].toLowerCase(Locale.ROOT);
        if (word.equals("/hint") || word.equals("/help") || word.equals("/h")
                || word.equals("/?")) {
            answer.reset(question.isTheory() ? "markdown" : question.language(), "");
            askHint();
            return true;
        }
        if (word.equals("/ask") || word.equals("/a")) {
            if (parts.length < 2 || parts[1].isBlank()) {
                status.setText("Type the question after /ask, or use the Ask box below.");
                return true;
            }
            answer.reset(question.isTheory() ? "markdown" : question.language(), "");
            askAbout(parts[1]);
            return true;
        }
        if (submitAnyway) {
            submitAnyway = false;
            return false;
        }
        submitAnyway = true;
        status.setText(word + " is not a command. /hint asks for one; press Submit again"
                + " to send this as your answer.");
        return true;
    }

    /**
     * Asks for a nudge on the question in front of them.
     *
     * <p>The hint goes under the question rather than replacing it, and the next one is
     * more concrete than the last: a first hint that gives the game away is no better
     * than no hint at all, and one that says nothing wastes the only thing somebody stuck
     * has left to try.
     */
    private void askHint() {
        if (question == null || busy()) {
            return;
        }
        if (!assistant.config().ready()) {
            status.setText(assistant.config().whyNotReady());
            return;
        }
        hints++;
        setBusy(true);
        hint.setDisable(true);
        submit.setDisable(true);
        waiting.start("Thinking of a hint");
        streaming = new StringBuilder();
        lastRender = 0;
        String heading = onScreen + "\n\n---\n\n#### Hint " + hints + "\n\n";
        view.setFollow(true);
        view.show(heading + "*thinking...*");

        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.hintSystem()),
                        new ChatProvider.Message("user",
                                Prompts.hintRequest(question, answer.text(), hints))),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 350) {
                        lastRender = now;
                        view.show(heading + streaming);
                    }
                },
                whole -> {
                    turn = null;
                    onScreen = heading + whole.strip();
                    setBusy(false);
                    view.show(onScreen);
                    submit.setDisable(false);
                    hint.setDisable(false);
                    answer.setEditable(true);
                    answer.area().requestFocus();
                    waiting.stop("Hint " + hints + ". Ask again for a bigger one.");
                },
                error -> {
                    turn = null;
                    hints--;
                    setBusy(false);
                    view.show(onScreen);
                    submit.setDisable(false);
                    hint.setDisable(false);
                    waiting.stop("No hint came back. Press Hint to try again.");
                });
    }

    /**
     * Answers a question asked in the middle of the exercise.
     *
     * <p>A hint is about the exercise; this is about the subject, and the two are worth
     * keeping apart. Somebody halfway through a query who wants to know what {@code IS
     * NULL} does to an index is not asking to be told the query, and answering them
     * properly is the part of a session that teaches anything at all.
     */
    private void askAbout(String asked) {
        if (question == null || busy()) {
            return;
        }
        String text = asked == null ? "" : asked.strip();
        if (text.isEmpty()) {
            askField.requestFocus();
            status.setText("Ask about the subject - what a clause does, why something"
                    + " behaves that way.");
            return;
        }
        if (!assistant.config().ready()) {
            status.setText(assistant.config().whyNotReady());
            return;
        }
        askField.clear();
        setBusy(true);
        submit.setDisable(true);
        waiting.start("Answering");
        streaming = new StringBuilder();
        lastRender = 0;
        String heading = onScreen + "\n\n---\n\n#### You asked\n\n" + text + "\n\n";
        view.setFollow(true);
        view.show(heading + "*thinking...*");

        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.askSystem()),
                        new ChatProvider.Message("user", Prompts.askRequest(
                                question, answer.text(), exchanges, text))),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 350) {
                        lastRender = now;
                        view.show(heading + streaming);
                    }
                },
                whole -> {
                    turn = null;
                    exchanges.add("They asked: " + text);
                    exchanges.add("You answered: " + whole.strip());
                    onScreen = heading + whole.strip();
                    setBusy(false);
                    view.show(onScreen);
                    submit.setDisable(false);
                    answer.setEditable(true);
                    answer.area().requestFocus();
                    waiting.stop("Answered. Ask again, or write your answer.");
                },
                error -> {
                    turn = null;
                    // Back in the box: retyping a question is not the developer's job
                    // when the endpoint was the thing that failed.
                    askField.setText(text);
                    setBusy(false);
                    view.show(onScreen);
                    submit.setDisable(false);
                    waiting.stop("No answer came back. Press Ask to try again.");
                });
    }

    // ------------------------------------------------------------- the marking

    private void submit() {
        if (question == null || busy()) {
            return;
        }
        String submission = answer.text();
        if (submission.isBlank()) {
            status.setText("Write something first - an empty answer teaches nobody anything.");
            return;
        }
        if (command(submission)) {
            return;
        }
        setBusy(true);
        submit.setDisable(true);
        hint.setDisable(true);
        answer.setEditable(false);
        waiting.start("Marking");
        streaming = new StringBuilder();
        lastRender = 0;
        String heading = onScreen + "\n\n---\n\n## Marking\n\n";
        view.setFollow(true);
        view.show(heading + "*marking...*");

        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.judgeSystem()),
                        new ChatProvider.Message("user", Prompts.judgeRequest(question, submission))),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 350) {
                        lastRender = now;
                        view.show(heading + visible(streaming.toString()));
                    }
                },
                whole -> {
                    turn = null;
                    setBusy(false);
                    Marking marking = Marking.parse(whole);
                    answered++;
                    if (marking.verdict().startsWith("correct")) {
                        correct++;
                    }
                    score.setText(correct + " of " + answered + " correct");
                    view.show(heading + "**" + marking.label() + "**\n\n" + marking.feedback());
                    waiting.stop("Marked. Press Next question when you are ready.");
                    answer.setEditable(true);
                    submit.setDisable(false);
                },
                error -> {
                    turn = null;
                    setBusy(false);
                    view.show(heading + "> " + error);
                    answer.setEditable(true);
                    submit.setDisable(false);
                    waiting.stop("The marking did not come back. Press Submit answer again.");
                });
    }

    /** The part of a marking that is safe to show while it streams: the fields, unwrapped. */
    private static String visible(String partial) {
        java.util.Map<String, String> fields = Fields.of(partial);
        String feedback = fields.get("FEEDBACK");
        if (feedback != null) {
            String verdict = Fields.get(fields, "VERDICT", "");
            String points = Fields.get(fields, "SCORE", "");
            String head = verdict.isBlank() ? "" : "**" + verdict
                    + (points.isBlank() ? "" : "  -  " + points) + "**\n\n";
            return head + feedback;
        }
        return "*marking...*";
    }

    // ------------------------------------------------------------------ state

    private boolean busy() {
        return turn != null;
    }

    private void cancel() {
        if (turn == null) {
            return;
        }
        turn.cancel();
        turn = null;
        setBusy(false);
        waiting.stop("Stopped.");
        if (stage == Stage.TUTORIAL) {
            /* Whatever arrived before Stop is what was taught, and the gate opens on it:
               somebody who has read enough and pressed Stop wants the questions, not to
               start the session again. */
            if (streaming != null && !streaming.isEmpty()) {
                taught = streaming.toString();
                showAcknowledge(true);
                waiting.stop("Stopped. Press I have read this to start the questions.");
            } else {
                stage = Stage.IDLE;
            }
            return;
        }
        if (question != null) {
            answer.setEditable(true);
            submit.setDisable(false);
        }
    }

    private void setBusy(boolean busy) {
        start.setDisable(busy);
        next.setDisable(busy || asked.isEmpty());
        stop.setDisable(!busy);
        topic.setDisable(busy);
        difficulty.setDisable(busy);
        language.setDisable(busy);
        hint.setDisable(busy || question == null);
        ask.setDisable(busy || question == null);
        askField.setDisable(busy || question == null);
    }

    void dispose() {
        cancel();
        answer.dispose();
    }
}
