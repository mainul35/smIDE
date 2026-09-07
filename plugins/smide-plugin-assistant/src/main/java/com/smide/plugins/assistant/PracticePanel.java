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

    private final ComboBox<String> topic = new ComboBox<>();
    private final ChoiceBox<String> difficulty = new ChoiceBox<>();
    private final Button start = new Button("Start session");
    private final Button next = new Button("Next question");
    private final Button submit = new Button("Submit answer");
    private final Button stop = new Button("Stop");
    private final Label score = new Label();
    private final Label status = new Label();
    private final Label answerLabel = new Label("Your answer");

    private final MarkdownView view;
    private final AnswerEditor answer;

    /** Titles already set this session, so the next question is a different one. */
    private final List<String> asked = new ArrayList<>();
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
        difficulty.getItems().setAll("easy", "medium", "hard", "mixed");
        difficulty.setValue("medium");
        score.getStyleClass().add("muted-small");
        status.getStyleClass().add("muted-small");
        answerLabel.getStyleClass().add("assistant-file");

        start.setOnAction(e -> startSession());
        next.setOnAction(e -> askQuestion());
        submit.setOnAction(e -> submit());
        stop.setOnAction(e -> cancel());
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

        SplitPane split = new SplitPane(view, answerBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.58);
        com.smide.api.ui.Splits.grabbable(split);

        HBox footer = new HBox(status);
        footer.setPadding(new Insets(4, 8, 4, 8));

        setTop(bar);
        setCenter(split);
        setBottom(footer);

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

    private String welcome() {
        return """
                ### Practice

                Pick a topic and press **Start session**. A question arrives one at a time;
                write your answer below and press **Submit answer** (Ctrl+Enter) to have it
                marked, with what was right, what was missing, and what to do differently.

                Code questions - SQL, Python, CSS, a data structure - are answered in the
                editor below, coloured for the language. Questions whose answer is prose are
                answered in Markdown in the same place.

                The assistant marks and explains. It does not write the answer for you.
                """;
    }

    // ------------------------------------------------------------- the session

    private void startSession() {
        asked.clear();
        answered = 0;
        correct = 0;
        score.setText("");
        askQuestion();
    }

    private void askQuestion() {
        if (busy()) {
            return;
        }
        if (!assistant.config().ready()) {
            view.show("> " + assistant.config().whyNotReady());
            return;
        }
        String subject = topic.getValue() == null ? "" : topic.getValue().strip();
        if (subject.isEmpty()) {
            status.setText("Name something to practise first.");
            return;
        }
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
                                difficulty.getValue(), asked))),
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
        if (turn != null) {
            turn.cancel();
            turn = null;
            setBusy(false);
            status.setText("Stopped.");
            if (question != null) {
                answer.setEditable(true);
                submit.setDisable(false);
            }
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
