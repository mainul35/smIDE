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

    /** Starting points, not a menu: the box is editable and anything typed is a topic. */
    private static final List<String> TOPICS = List.of(
            "SQL queries", "SQL schema design", "Data structures", "Algorithms",
            "Java Stream API", "Java generics", "Java concurrency", "Spring Boot",
            "Kafka", "REST API design", "Python", "CSS", "JavaScript", "TypeScript",
            "Regular expressions", "Git", "Docker", "System design", "Security");

    private final Assistant assistant;
    private final Ide ide;

    /** Teaching, then testing. A session is one and then the other. */
    private enum Stage { IDLE, TUTORIAL, PRACTICE }

    private final ComboBox<String> topic = new ComboBox<>();
    private final ChoiceBox<String> difficulty = new ChoiceBox<>();
    private final Button start = new Button("Start session");
    private final Button next = new Button("Next question");
    private final Button submit = new Button("Submit answer");
    private final Button stop = new Button("Stop");
    private final Button understood = new Button("I have read this - start practice");
    private final Button skip = new Button("Skip the tutorial");
    private final Label score = new Label();
    private final Label status = new Label();
    private final Label answerLabel = new Label("Your answer");
    private final HBox acknowledge = new HBox(8, understood, skip);

    private final MarkdownView view;
    private final AnswerEditor answer;
    private SplitPane split;
    private VBox answerBox;

    /** Titles already set this session, so the next question is a different one. */
    private final List<String> asked = new ArrayList<>();
    /** The topic the session is actually on, which the box is not allowed to contradict. */
    private String chosenTopic;
    private Stage stage = Stage.IDLE;
    /** The tutorial just read, carried into every question so the exercise is not a copy. */
    private String taught = "";
    private PracticeQuestion question;
    private Assistant.Turn turn;
    private StringBuilder streaming;
    private long lastRender;
    private int answered;
    private int correct;

    PracticePanel(Assistant assistant) {
        this.assistant = assistant;
        this.ide = assistant.ide();
        this.view = new MarkdownView(ide.theme());
        this.answer = new AnswerEditor(ide);

        topic.getItems().setAll(TOPICS);
        topic.setEditable(true);
        topic.setValue("SQL queries");
        topic.setPrefWidth(210);
        topic.setTooltip(new Tooltip("Anything you want to practise. Type your own."));
        chosenTopic = topic.getValue();
        topic.valueProperty().addListener((o, was, now) -> {
            if (now != null && !now.isBlank()) {
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
        difficulty.getItems().setAll("easy", "medium", "hard", "mixed");
        difficulty.setValue("medium");
        score.getStyleClass().add("muted-small");
        status.getStyleClass().add("muted-small");
        answerLabel.getStyleClass().add("assistant-file");

        start.setOnAction(e -> startSession());
        next.setOnAction(e -> askQuestion());
        submit.setOnAction(e -> submit());
        stop.setOnAction(e -> cancel());
        understood.setOnAction(e -> beginPractice());
        /* Skipping is for the third session on the same topic, not for the first. It stays
           the quieter of the two, and what was written is still carried into the questions
           - a tutorial nobody read is still a list of what this session is not going to
           ask about twice. */
        skip.setOnAction(e -> beginPractice());
        next.setDisable(true);
        submit.setDisable(true);
        stop.setDisable(true);

        /* The topic on one row and the buttons on the next. All of this on one row fits a
           dialog and not a tool window, where it collapses into a row of ellipses. */
        HBox.setHgrow(topic, Priority.ALWAYS);
        topic.setMaxWidth(Double.MAX_VALUE);
        difficulty.setMinWidth(Region.USE_PREF_SIZE);
        HBox subject = new HBox(8, topic, difficulty);
        subject.setAlignment(Pos.CENTER_LEFT);
        for (Button button : new Button[]{start, next, stop, submit}) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox buttons = new HBox(8, start, next, stop, spacer(), score);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox bar = new VBox(6, subject, buttons);
        bar.setPadding(new Insets(6, 8, 6, 8));

        HBox answerBar = new HBox(8, answerLabel, spacer(), submit);
        answerBar.setAlignment(Pos.CENTER_LEFT);
        answerBar.setPadding(new Insets(4, 8, 4, 8));

        VBox answerBox = new VBox(answerBar, answer);
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
        if (!chosenTopic.equals(topic.getValue())) {
            topic.setValue(chosenTopic);
        }
        if (topic.getEditor() != null && !chosenTopic.equals(topic.getEditor().getText())) {
            topic.getEditor().setText(chosenTopic);
        }
    }

    private String welcome() {
        return """
                ### Practice

                Pick a topic and press **Start session**. It begins with a short tutorial -
                what the thing is, where you actually meet it, the rules that decide whether
                code works, and where it goes wrong. Read it, then press **I have read this**
                and the questions start.

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
        status.setText("Writing a tutorial on " + subject + "...");
        view.setFollow(true);
        view.show("> Writing a tutorial on **" + subject + "**...");

        streaming = new StringBuilder();
        lastRender = 0;
        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.tutorialSystem()),
                        new ChatProvider.Message("user",
                                Prompts.tutorialRequest(subject, difficulty.getValue()))),
                fragment -> {
                    // Shown as it arrives: a tutorial has nothing hidden in it, and reading
                    // can start at the top while the rest is still coming.
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 180) {
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
                    status.setText("Read it, then press I have read this.");
                },
                error -> {
                    turn = null;
                    stage = Stage.IDLE;
                    setBusy(false);
                    view.show("> " + error);
                    status.setText("");
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
        status.setText("Setting a question on " + subject + "...");
        view.show("> Writing a question on **" + subject + "**...");

        streaming = new StringBuilder();
        lastRender = 0;
        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.questionSystem()),
                        new ChatProvider.Message("user", Prompts.questionRequest(subject,
                                difficulty.getValue(), asked, taught))),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 180) {
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
                    setBusy(false);
                    view.show(question.markdown());
                    answerLabel.setText(question.isTheory()
                            ? "Your answer  (Markdown)" : "Your answer  (" + question.language() + ")");
                    answer.reset(question.isTheory() ? "markdown" : question.language(), "");
                    answer.setEditable(true);
                    answer.area().requestFocus();
                    submit.setDisable(false);
                    status.setText("Question " + asked.size() + ". Ctrl+Enter submits.");
                },
                error -> {
                    turn = null;
                    setBusy(false);
                    view.show("> " + error);
                    status.setText("");
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
        setBusy(true);
        submit.setDisable(true);
        answer.setEditable(false);
        status.setText("Marking...");
        streaming = new StringBuilder();
        lastRender = 0;
        String heading = question.markdown() + "\n\n---\n\n## Marking\n\n";
        view.setFollow(true);
        view.show(heading + "*marking...*");

        turn = assistant.ask(
                List.of(new ChatProvider.Message("system", Prompts.judgeSystem()),
                        new ChatProvider.Message("user", Prompts.judgeRequest(question, submission))),
                fragment -> {
                    streaming.append(fragment);
                    long now = System.currentTimeMillis();
                    if (now - lastRender > 180) {
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
                    status.setText("Marked. Press Next question when you are ready.");
                    answer.setEditable(true);
                    submit.setDisable(false);
                },
                error -> {
                    turn = null;
                    setBusy(false);
                    view.show(heading + "> " + error);
                    answer.setEditable(true);
                    submit.setDisable(false);
                    status.setText("");
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
        status.setText("Stopped.");
        if (stage == Stage.TUTORIAL) {
            /* Whatever arrived before Stop is what was taught, and the gate opens on it:
               somebody who has read enough and pressed Stop wants the questions, not to
               start the session again. */
            if (streaming != null && !streaming.isEmpty()) {
                taught = streaming.toString();
                showAcknowledge(true);
                status.setText("Stopped. Press I have read this to start the questions.");
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
    }

    void dispose() {
        cancel();
        answer.dispose();
    }
}
