package com.smide.plugins.notebook;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.lang.Highlighter;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.workspace.Workspace;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A notebook, as a notebook.
 *
 * <p>A column of cells, each its own editor, with the output of each under it; a kernel
 * started from the project's own interpreter when the first cell is run and not before;
 * and pyright shown the cells above whichever one is being typed in, so that a name
 * defined three cells up is a name and not a mistake.
 *
 * <p>Three things are kept apart on purpose, and they are the reason this class is as
 * small as it is. {@link Notebook} is the file and knows nothing else. {@link Kernel} is a
 * process and {@link Pyright} is another, and neither knows what a cell looks like.
 * {@link CellContext}, {@link Outputs} and {@link Spans} are the arithmetic, and are
 * tested without any of the above. What is left here is the wiring, which is the part that
 * needs a screen to judge.
 *
 * <p>A notebook that will not parse is not opened as an empty one. It is shown as what it
 * is - a file that cannot be read, and why - with its text there to look at and saving
 * refused, because the alternative is that the first save writes an empty notebook over
 * somebody's work.
 */
public final class NotebookEditor implements Editor {

    /** How long after the last keystroke the checker is told about it. */
    private static final Duration CHECK_DELAY = Duration.millis(400);
    /** And how long before a suggestion list is asked for. */
    private static final Duration COMPLETE_DELAY = Duration.millis(180);

    private static final KeyCombination RUN = KeyCombination.keyCombination("shortcut+Enter");
    private static final KeyCombination RUN_AND_NEXT = KeyCombination.keyCombination("shift+Enter");
    private static final KeyCombination COMPLETE = KeyCombination.keyCombination("shortcut+Space");
    private static final KeyCombination SPLIT = KeyCombination.keyCombination("shortcut+shift+Minus");
    private static final KeyCombination FORMAT = KeyCombination.keyCombination("shortcut+alt+L");

    private final Ide ide;
    private final Workspace workspace;
    private final BorderPane root = new BorderPane();
    private final VBox column = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(column);
    private final Label kernelState = new Label();
    /** What the type checker is doing, so that "nothing happens" is never unexplained. */
    private final Label typesState = new Label();
    private final List<CellView> views = new ArrayList<>();
    private final ReadOnlyBooleanWrapper modified = new ReadOnlyBooleanWrapper(false);
    private final CompletionPopup completion;
    private final PauseTransition checkDebounce = new PauseTransition(CHECK_DELAY);
    private final PauseTransition completeDebounce = new PauseTransition(COMPLETE_DELAY);
    private final Highlighter highlighter;
    private final String fontFamily;
    private final int fontSize;

    /** Which cell each running request belongs to. */
    private final Map<String, CellView> running = new HashMap<>();
    /** Cells waiting their turn, for Run All. */
    private final Deque<CellView> queue = new ArrayDeque<>();

    private Path path;
    private Notebook notebook;
    /** Why the file could not be read, when it could not. */
    private String unreadable;
    private String rawText = "";
    private CellView selected;
    private Kernel kernel;
    private Pyright pyright;
    private boolean disposed;

    NotebookEditor(Ide ide, Workspace workspace, Path file) {
        this.ide = ide;
        this.workspace = workspace;
        this.path = file.toAbsolutePath().normalize();
        this.fontFamily = ide.settings().get("editor.fontFamily", "Cascadia Code");
        this.fontSize = ide.settings().getInt("editor.fontSize", 13);
        this.highlighter = ide.languages().byId("python")
                .map(LanguageSupport::highlighter).orElse(Highlighter.NONE);
        this.completion = new CompletionPopup(ide);

        root.getStyleClass().add("notebook-editor");
        column.getStyleClass().add("notebook-column");
        column.setPadding(new Insets(6));
        column.setFillWidth(true);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("notebook-scroll");

        checkDebounce.setOnFinished(event -> {
            check();
            checkSyntax();
        });
        completeDebounce.setOnFinished(event -> complete(false));
        /* Accepting a suggestion edits the cell, and an edit asks for suggestions - so the
           list came straight back up over the word it had just completed. The insertion is
           what schedules that, so the cancelling has to happen after it. */
        completion.setAfterInsert(completeDebounce::stop);

        load();
        root.setTop(toolbar());
        root.setCenter(unreadable == null ? scroll : unreadableNode());
    }

    // ---------------------------------------------------------------- Editor

    @Override
    public Node node() {
        return root;
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public Workspace workspace() {
        return workspace;
    }

    @Override
    public ReadOnlyBooleanProperty modifiedProperty() {
        return modified.getReadOnlyProperty();
    }

    @Override
    public void save() {
        write(path);
    }

    @Override
    public void saveAs(Path target) {
        Path resolved = target.toAbsolutePath().normalize();
        if (write(resolved)) {
            path = resolved;
        }
    }

    @Override
    public void focus() {
        if (selected != null) {
            selected.area().requestFocus();
        } else if (!views.isEmpty()) {
            views.get(0).area().requestFocus();
        }
    }

    @Override
    public void reload() {
        load();
        root.setCenter(unreadable == null ? scroll : unreadableNode());
    }

    @Override
    public void dispose() {
        disposed = true;
        completion.hide();
        checkDebounce.stop();
        completeDebounce.stop();
        if (kernel != null) {
            kernel.shutdown();
        }
        if (pyright != null) {
            pyright.dispose();
        }
    }

    @Override
    public void shown() {
        // The checker is only worth starting once somebody is looking at the notebook: a
        // session restored at startup can open half a dozen of these behind the front tab,
        // and each one would otherwise start a pyright nobody asked for.
        if (unreadable == null && pyright == null) {
            pyright = new Pyright(ide, workspace, path, this::applyDiagnostics,
                    this::interpreter);
            /* Warm it now rather than on the first edit. Starting pyright takes a second or
               two, and the first thing a reader does in a notebook is ask for completion -
               which used to be the thing that started it, so the first request was always
               the one that got nothing back. */
            check();
            showTypesState();
            check();
        }
    }

    // ---------------------------------------------------------------- the file

    /** What the file looked like when it was last read or written; see FileStamp. */
    private com.smide.api.editor.FileStamp loaded = com.smide.api.editor.FileStamp.UNKNOWN;

    /** True if the file changed since this editor last read or wrote it. */
    @Override
    public boolean isStaleOnDisk() {
        return loaded.differsFrom(path);
    }

    private void load() {
        loaded = com.smide.api.editor.FileStamp.of(path);
        try {
            rawText = Files.exists(path)
                    ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            rawText = "";
            unreadable = "it could not be read: " + e.getMessage();
            notebook = null;
            return;
        }
        try {
            notebook = rawText.isBlank() ? Notebook.empty("python") : Notebook.read(rawText);
            unreadable = null;
        } catch (NotebookFormatException e) {
            notebook = null;
            unreadable = e.getMessage();
            return;
        }
        rebuild();
        modified.set(false);
    }

    /** Builds a view per cell, keeping the order the notebook is in. */
    private void rebuild() {
        views.clear();
        column.getChildren().clear();
        CellListener listener = new CellListener();
        for (Cell cell : notebook.cells()) {
            CellView view = new CellView(cell, highlighter, fontFamily, fontSize, listener);
            view.area().addEventFilter(KeyEvent.KEY_PRESSED, event -> keyIn(view, event));
            views.add(view);
            column.getChildren().add(view);
        }
        if (views.isEmpty()) {
            // A notebook with no cells cannot be typed into; give it one.
            notebook.insert(0, CellKind.CODE, "");
            rebuild();
            return;
        }
        select(views.get(0));
    }

    private boolean write(Path target) {
        if (notebook == null) {
            ide.notifications().error("This notebook was not saved",
                    "smIDE could not read it in the first place, so it will not write over it: "
                            + unreadable);
            return false;
        }
        views.forEach(CellView::flush);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, notebook.toJson(), StandardCharsets.UTF_8);
            loaded = com.smide.api.editor.FileStamp.of(target);
            modified.set(false);
            return true;
        } catch (IOException e) {
            ide.notifications().error("This notebook was not saved", e.getMessage());
            return false;
        }
    }

    /**
     * What is shown instead of cells when the file is not a notebook this can read.
     *
     * <p>Half-written, hand-edited and simply-wrong {@code .ipynb} files are not a corner
     * case: somebody creates one empty and starts typing Python into it, or a tool writes
     * one and is killed partway. The file is still worth looking at, so it is shown - as
     * JSON, highlighted by whichever plugin provides JSON, because that is what makes a
     * missing bracket findable - and it is read-only here, because an editor that could
     * not read it has no business writing it.
     *
     * <p>A notebook that is simply empty is not this case at all: an empty file is opened
     * as a new notebook with one cell to type in, which is what somebody who has just
     * created one wants.
     */
    private Node unreadableNode() {
        Label title = new Label("This is not a notebook smIDE can read: " + unreadable);
        title.setWrapText(true);
        title.getStyleClass().add("notebook-unreadable");
        Label advice = new Label("It is shown below as the text it is, and nothing here will"
                + " write to it. A notebook is a JSON object with a \"cells\" array; repair"
                + " the file, or restore it from version control, and reopen it.");
        advice.setWrapText(true);
        CodeArea raw = new CodeArea(rawText);
        raw.setEditable(false);
        raw.getStyleClass().add("code-area");
        raw.setStyle("-fx-font-family: \"" + fontFamily.replace("\"", "")
                + "\", Consolas, monospace; -fx-font-size: " + fontSize + "px;");
        // Whatever plugin provides JSON, asked for by id rather than compiled against:
        // plugins cannot reach each other's classes, and a notebook still opens where
        // nothing provides JSON - it is simply not coloured.
        ide.languages().byId("json").map(LanguageSupport::highlighter).ifPresent(json -> {
            try {
                raw.setStyleSpans(0, Spans.of(rawText.length(), json.tokenize(rawText), List.of()));
            } catch (RuntimeException e) {
                // Spans that do not add up to the text's length; leave it uncoloured.
            }
        });
        VBox box = new VBox(8, title, advice, new VirtualizedScrollPane<>(raw));
        VBox.setVgrow(box.getChildren().get(2), Priority.ALWAYS);
        box.setPadding(new Insets(12));
        return box;
    }

    // ---------------------------------------------------------------- the toolbar

    private Node toolbar() {
        HBox bar = new HBox(4);
        bar.getStyleClass().add("tool-window-header");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 6, 4, 6));
        bar.getChildren().addAll(
                button("▶ Run All", "Run every code cell, from the top", this::runAll),
                button("⏸ Interrupt", "Interrupt whatever the kernel is doing",
                        this::interrupt),
                button("↻ Restart", "Restart the kernel; the cells keep their output",
                        this::restart),
                button("↻ Restart and Clear", "Restart the kernel and clear every output",
                        this::restartAndClear),
                button("+ Cell", "Add a code cell at the end",
                        () -> new CellListener().addBelow(views.isEmpty()
                                ? null : views.get(views.size() - 1))),
                button("Format", "Tidy every code cell with ruff (Ctrl+Alt+L)", this::formatAll));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        kernelState.getStyleClass().add("notebook-kernel-state");
        kernelState.setFont(Font.font(fontFamily, fontSize - 1));
        typesState.getStyleClass().add("notebook-kernel-state");
        typesState.setFont(Font.font(fontFamily, fontSize - 1));
        typesState.setPadding(new Insets(0, 12, 0, 0));
        bar.getChildren().addAll(gap, typesState, kernelState);
        showKernelState("no kernel yet");
        showTypesState();
        bar.setDisable(unreadable != null);
        return bar;
    }

    private Button button(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("flat-button");
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(event -> action.run());
        return button;
    }

    private void showKernelState(String what) {
        kernelState.setText("Kernel: " + what);
    }

    /**
     * Says what the type checker is doing, in the toolbar beside the kernel.
     *
     * <p>Because the alternative is silence. Completion that is unavailable used to look
     * exactly like completion that was broken, or a keystroke that never arrived: nothing
     * happened and nothing said why. A reader who can see "Types: off - pyright is not
     * installed" knows what to do; a reader who sees nothing files a bug, or gives up.
     */
    private void showTypesState() {
        typesState.setText("Types: " + typesStateText());
    }

    private String typesStateText() {
        if (unreadable != null) {
            return "off - this file is not a notebook";
        }
        if (Pyright.launcher(ide).isEmpty()) {
            return "off - pyright is not installed (open a .py file to be offered it)";
        }
        if (pyright == null) {
            return "starting";
        }
        return pyright.isAvailable() ? "pyright" : "off - pyright would not start";
    }

    // ------------------------------------------------------------- formatting

    /**
     * Tidies every code cell with ruff, installing it the first time if it is not there.
     *
     * <p>Installing without being asked is a deliberate choice and a narrow one: into this
     * project's own environment, one pinned package, only when the user has asked for
     * formatting and there is nothing to format with. The alternative - a dialog explaining
     * that formatting needs a tool, with a command to copy - is a worse answer to somebody
     * who has just pressed Format.
     *
     * <p>Cell by cell rather than all at once, because a notebook's cells are fragments: one
     * that is not whole Python on its own is skipped and the rest are still tidied, which is
     * not possible if they are formatted as one document.
     */
    private void formatAll() {
        if (notebook == null || unreadable != null) {
            return;
        }
        Optional<Path> interpreter = interpreter();
        if (interpreter.isEmpty()) {
            ide.notifications().warn("Nothing to format with",
                    "This project has no Python interpreter, so there is no ruff to run.");
            return;
        }
        views.forEach(CellView::flush);
        List<CellView> code = views.stream()
                .filter(view -> view.cell().kind() == CellKind.CODE).toList();
        if (code.isEmpty()) {
            return;
        }
        Thread worker = new Thread(() -> formatOffThread(interpreter.get(), code),
                "smide-notebook-format");
        worker.setDaemon(true);
        worker.start();
    }

    private void formatOffThread(Path interpreter, List<CellView> code) {
        List<String> formatted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        boolean installed = false;
        for (CellView view : code) {
            int index = views.indexOf(view);
            String before = view.cell().source();
            Formatter.Formatted answer = Formatter.format(interpreter, before,
                    Formatter.nameFor(path, index));
            if (!answer.worked() && !installed
                    && answer.problem().orElse("").contains("not installed")) {
                Platform.runLater(() -> showKernelState("installing ruff"));
                Optional<String> wrong = Formatter.install(interpreter);
                installed = true;
                if (wrong.isPresent()) {
                    Platform.runLater(() -> ide.notifications().error("Could not install ruff",
                            "Formatting needs ruff in this project's environment: " + wrong.get()));
                    return;
                }
                answer = Formatter.format(interpreter, before, Formatter.nameFor(path, index));
            }
            if (answer.worked()) {
                String after = answer.text().orElse(before);
                if (!after.equals(before)) {
                    formatted.add(String.valueOf(index + 1));
                    Platform.runLater(() -> {
                        if (!disposed) {
                            view.setText(after);
                        }
                    });
                }
            } else {
                skipped.add((index + 1) + ": " + answer.problem().orElse("no reason given"));
            }
        }
        List<String> changed = List.copyOf(formatted);
        List<String> left = List.copyOf(skipped);
        Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            showKernelState(kernel == null ? "no kernel yet" : "ready");
            if (changed.isEmpty() && left.isEmpty()) {
                ide.notifications().info("Already tidy", "Every cell was already formatted.");
            } else if (left.isEmpty()) {
                ide.notifications().info("Formatted", "Cells " + String.join(", ", changed) + ".");
            } else {
                ide.notifications().warn("Formatted, with some left alone",
                        (changed.isEmpty() ? "" : "Cells " + String.join(", ", changed) + ". ")
                                + "Left alone - " + String.join("; ", left));
            }
        });
    }

    /** This project's interpreter, the one its cells run in. */
    private Optional<Path> interpreter() {
        Path root = workspace == null ? path.getParent() : workspace.root();
        return Interpreters.forNotebook(path, root,
                com.smide.plugins.python.PythonToolchain::venvInterpreter,
                () -> new com.smide.plugins.python.PythonToolchain().locate(ide));
    }

    // ---------------------------------------------------------------- keys

    private void keyIn(CellView view, KeyEvent event) {
        if (completion.handle(event)) {
            event.consume();
            return;
        }
        if (RUN.match(event)) {
            event.consume();
            run(view);
            return;
        }
        if (RUN_AND_NEXT.match(event)) {
            event.consume();
            run(view);
            int next = views.indexOf(view) + 1;
            if (next < views.size()) {
                select(views.get(next));
                views.get(next).area().requestFocus();
            }
            return;
        }
        if (COMPLETE.match(event)) {
            event.consume();
            complete(true);
            return;
        }
        if (FORMAT.match(event)) {
            event.consume();
            formatAll();
            return;
        }
        if (SPLIT.match(event)) {
            event.consume();
            new CellListener().splitAtCaret(view);
        }
    }

    // ---------------------------------------------------------------- running

    private void runAll() {
        queue.clear();
        views.stream().filter(view -> view.cell().kind() == CellKind.CODE).forEach(queue::add);
        runNextInQueue();
    }

    private void runNextInQueue() {
        if (queue.isEmpty()) {
            return;
        }
        run(queue.poll());
    }

    /**
     * Runs one cell.
     *
     * <p>The cell's text goes into the model first, so that what ran and what is saved are
     * the same thing - a cell run and then saved without being touched again must not be
     * saved as it was before the run.
     */
    private void run(CellView view) {
        if (view == null || view.cell().kind() != CellKind.CODE) {
            return;
        }
        view.flush();
        Kernel started = kernel();
        if (started == null) {
            return;
        }
        try {
            String id = started.execute(view.text());
            running.put(id, view);
            view.cell().clearOutputs();
            view.setBusy(true);
            view.refreshOutputs();
            touched();
            showKernelState("busy");
        } catch (IOException e) {
            view.setBusy(false);
            ide.notifications().error("The cell was not run", e.getMessage());
        }
    }

    private void interrupt() {
        if (kernel == null) {
            return;
        }
        queue.clear();
        try {
            kernel.interrupt();
        } catch (IOException e) {
            ide.notifications().warn("The kernel was not interrupted", e.getMessage());
        }
    }

    private void restart() {
        if (kernel == null) {
            kernel();
            return;
        }
        queue.clear();
        running.clear();
        views.forEach(view -> view.setBusy(false));
        try {
            showKernelState("restarting");
            kernel.restart();
        } catch (IOException e) {
            ide.notifications().error("The kernel was not restarted", e.getMessage());
        }
    }

    private void restartAndClear() {
        if (notebook != null) {
            notebook.clearAllOutputs();
            views.forEach(view -> {
                view.setBusy(false);
                view.refreshOutputs();
            });
            touched();
        }
        restart();
    }

    /**
     * The kernel, started if it is not running yet.
     *
     * <p>Null, with something said on screen, when there is nothing to start it with. The
     * two reasons are a project with no interpreter - the Python plugin's business, and
     * already reported by it - and an interpreter without {@code ipykernel}, which is
     * answered with the exact command that fixes it, run through the interpreter rather
     * than through a {@code pip.exe} that Windows may refuse to start.
     */
    private Kernel kernel() {
        if (kernel != null && kernel.state() != Kernel.State.FAILED
                && kernel.state() != Kernel.State.STOPPED) {
            return kernel;
        }
        if (kernel != null) {
            kernel.shutdown();
            kernel = null;
        }
        if (notebook != null && !notebook.isPython()) {
            ide.notifications().warn("This notebook is not Python",
                    "It says its kernel is " + notebook.language().orElse("unknown")
                            + ". smIDE can only run Python notebooks.");
            return null;
        }
        Path root = workspace == null ? path.getParent() : workspace.root();
        // The virtual environment nearest the notebook, and only then whatever Python the
        // machine has: a notebook next to a venv must run in that venv, because that is
        // where its packages are. {@link Interpreters} says why the workspace root alone
        // is not enough to find it.
        Optional<Path> interpreter = Interpreters.forNotebook(path, root,
                com.smide.plugins.python.PythonToolchain::venvInterpreter,
                () -> new com.smide.plugins.python.PythonToolchain().locate(ide));
        if (interpreter.isEmpty()) {
            showKernelState("no interpreter");
            ide.notifications().error("This notebook cannot be run",
                    "This project has no Python interpreter. Set one in Settings, or create a"
                            + " virtual environment in the project folder.");
            return null;
        }
        try {
            Path helper = KernelHelper.install(ide.homeDir());
            // The notebook's own folder, as Jupyter does: a cell that writes "data.csv"
            // means the file beside the notebook, and somebody moving a notebook between
            // this editor and Jupyter must not find that its paths mean something else.
            Kernel started = new Kernel(interpreter.get(), helper, path.getParent(),
                    event -> Platform.runLater(() -> onKernelEvent(event)),
                    line -> System.err.println("smIDE notebook kernel: " + line));
            started.start();
            kernel = started;
            showKernelState("starting");
            return started;
        } catch (IOException | RuntimeException e) {
            showKernelState("failed");
            ide.notifications().error("The kernel would not start",
                    e.getMessage() == null ? e.toString() : e.getMessage());
            return null;
        }
    }

    /**
     * One event from the kernel, on the UI thread.
     *
     * <p>The cell a message belongs to comes from the request id the helper carries back,
     * and a message with no id - the kernel going idle after everything has finished, a
     * message from something started earlier - belongs to no cell and only moves the state
     * on the toolbar.
     */
    private void onKernelEvent(KernelEvent event) {
        if (disposed) {
            return;
        }
        switch (event.type()) {
            case KernelEvent.READY -> showKernelState("ready");
            case KernelEvent.RESTARTED -> {
                showKernelState("ready (restarted)");
                views.forEach(view -> view.setBusy(false));
            }
            case KernelEvent.MISSING -> {
                showKernelState("no " + event.module());
                String command = KernelHelper.installCommand(
                        kernel == null ? null : Path.of(kernel.command().get(0)));
                ide.notifications().error("This project cannot run notebooks yet",
                        "Its Python has no " + event.module() + ". Jupyter needs ipykernel;"
                                + " install it with:\n\n" + command);
                failEverythingRunning();
            }
            case KernelEvent.FAILED -> {
                showKernelState("failed");
                ide.notifications().error("The kernel stopped", event.message());
                failEverythingRunning();
            }
            case KernelEvent.REPLY -> {
                CellView view = running.remove(event.id());
                if (view != null) {
                    event.count().ifPresent(count -> view.cell().setExecutionCount(count));
                    view.setBusy(false);
                    view.refreshOutputs();
                    touched();
                }
                showKernelState(running.isEmpty() ? "ready" : "busy");
                // An error stops a Run All where it went wrong, as Jupyter does: the cells
                // below it were written to run after this one, not instead of it.
                if ("error".equals(event.status())) {
                    queue.clear();
                } else {
                    runNextInQueue();
                }
            }
            case KernelEvent.MESSAGE -> onMessage(event);
            default -> {
                // Nothing else changes anything on screen.
            }
        }
    }

    private void onMessage(KernelEvent event) {
        CellView view = running.get(event.id());
        Outputs.idleFrom(event.msgType(), event.content()).ifPresent(idle -> {
            if (idle && view != null) {
                view.setBusy(false);
            }
        });
        if (view == null) {
            return;
        }
        Outputs.Effect effect = Outputs.effectOf(event.msgType(), event.content());
        if (effect instanceof Outputs.Effect.Add add) {
            view.cell().setOutputs(Outputs.append(view.cell().outputs(), add.output()));
            view.refreshOutputs();
            touched();
        } else if (effect instanceof Outputs.Effect.Clear) {
            view.cell().clearOutputs();
            view.refreshOutputs();
            touched();
        } else if (effect instanceof Outputs.Effect.Counted counted) {
            view.cell().setExecutionCount(counted.executionCount());
            view.refreshOutputs();
            touched();
        }
    }

    private void failEverythingRunning() {
        queue.clear();
        running.values().forEach(view -> view.setBusy(false));
        running.clear();
    }

    /** Clears every output and writes the file, which is what the action asks for. */
    void clearOutputsAndSave() {
        if (notebook == null) {
            return;
        }
        notebook.clearAllOutputs();
        views.forEach(CellView::refreshOutputs);
        modified.set(true);
        save();
    }

    // ---------------------------------------------------------------- the checker

    /**
     * Tells pyright what the notebook now says, from the last code cell's point of view.
     *
     * <p>One request covers the whole notebook: the context built for the last code cell
     * contains every code cell above it, so the diagnostics that come back are spread over
     * all of them at once rather than one checker request per cell.
     */
    private void check() {
        if (pyright == null || notebook == null || !pyright.isAvailable()) {
            return;
        }
        views.forEach(CellView::flush);
        int last = -1;
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).cell().kind() == CellKind.CODE) {
                last = i;
            }
        }
        if (last < 0) {
            return;
        }
        pyright.update(CellContext.upTo(notebook.cells(), last, null));
    }

    /**
     * Asks the interpreter whether each code cell is Python it would accept.
     *
     * <p>Beside pyright, not behind it. pyright is a download that needs Node and may be
     * absent, starting or broken; the interpreter is always there, because a notebook with
     * no interpreter cannot run at all. A mark under an unclosed bracket is the most basic
     * thing a reader expects, and it should not be the thing that depends on the most
     * elaborate part of the stack.
     *
     * <p>One process per code cell, after typing has settled, off the UI thread.
     */
    private void checkSyntax() {
        if (notebook == null || unreadable != null) {
            return;
        }
        Optional<Path> interpreter = interpreter();
        if (interpreter.isEmpty()) {
            return;
        }
        views.forEach(CellView::flush);
        List<CellView> code = views.stream()
                .filter(view -> view.cell().kind() == CellKind.CODE).toList();
        Map<CellView, String> sources = new HashMap<>();
        code.forEach(view -> sources.put(view, view.cell().source()));
        Thread worker = new Thread(() -> {
            for (CellView view : code) {
                String source = sources.get(view);
                Optional<SyntaxCheck.Problem> problem = SyntaxCheck.check(interpreter.get(), source);
                Platform.runLater(() -> {
                    // Dropped if the cell has been typed in since: the position would be stale.
                    if (disposed || !view.text().equals(source)) {
                        return;
                    }
                    view.setSyntaxProblem(problem.orElse(null));
                });
            }
        }, "smide-notebook-syntax");
        worker.setDaemon(true);
        worker.start();
    }

    /** Diagnostics for one cell, from pyright's thread. */
    private void applyDiagnostics(Pyright.CellDiagnostics diagnostics) {
        Platform.runLater(() -> {
            if (disposed || diagnostics.cellIndex() >= views.size()) {
                return;
            }
            views.get(diagnostics.cellIndex()).setDiagnostics(diagnostics.diagnostics());
        });
    }

    /**
     * Asks for suggestions where the caret is.
     *
     * <p>pyright is asked on a thread of its own, because a request that takes longer than
     * expected must not stop the cell being typed into. The answer is only shown if the
     * caret has not moved on, so a list that arrives late is dropped rather than put up
     * over text it no longer describes.
     */
    private void complete(boolean explicit) {
        CellView view = selected;
        if (pyright == null || notebook == null || view == null
                || view.cell().kind() != CellKind.CODE || !pyright.isAvailable()) {
            return;
        }
        CodeArea area = view.area();
        int caret = area.getCaretPosition();
        String text = area.getText();
        int wordStart = caret;
        while (wordStart > 0 && isWordCharacter(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        if (!explicit && wordStart == caret && !(caret > 0 && text.charAt(caret - 1) == '.')) {
            // Nothing being typed and no member access: do not interrupt.
            return;
        }
        int index = views.indexOf(view);
        int line = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        views.forEach(CellView::flush);
        CellContext context = CellContext.upTo(notebook.cells(), index, text);
        int wordFrom = wordStart;
        Thread worker = new Thread(() -> {
            List<org.eclipse.lsp4j.CompletionItem> items =
                    pyright.completionsAt(context, index, line, column);
            Platform.runLater(() -> {
                if (disposed || selected != view || area.getCaretPosition() != caret) {
                    return;
                }
                completion.show(area, items, wordFrom);
            });
        }, "smide-notebook-completion");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    // ---------------------------------------------------------------- edits

    private void select(CellView view) {
        if (selected == view) {
            return;
        }
        if (selected != null) {
            selected.setSelected(false);
        }
        selected = view;
        if (selected != null) {
            selected.setSelected(true);
        }
    }

    /** Something changed: the tab says so, and the checker is told once typing stops. */
    private void touched() {
        modified.set(true);
        checkDebounce.playFromStart();
    }

    /**
     * Rebuilds the column after a structural change, keeping the reader where they were.
     *
     * <p>Views are rebuilt rather than reshuffled because a cell's view is cheap and the
     * alternative - keeping a view per cell and moving nodes about - is where this kind of
     * editor accumulates its bugs. The index to land on is passed in, because after a
     * delete or a move the cell that should have the caret is not the one that had it.
     */
    private void rebuildAround(int focusIndex) {
        rebuild();
        if (views.isEmpty()) {
            return;
        }
        CellView view = views.get(Math.max(0, Math.min(focusIndex, views.size() - 1)));
        select(view);
        Platform.runLater(view.area()::requestFocus);
        touched();
    }

    /** What a cell asks for; every change to the notebook goes through here. */
    private final class CellListener implements CellView.Listener {

        @Override
        public void run(CellView cell) {
            NotebookEditor.this.run(cell);
        }

        @Override
        public void changed(CellView cell) {
            cell.flush();
            modified.set(true);
            checkDebounce.playFromStart();
            completeDebounce.playFromStart();
        }

        @Override
        public void kindChanged(CellView cell, CellKind kind) {
            cell.flush();
            cell.cell().setKind(kind);
            cell.load();
            touched();
        }

        @Override
        public void focused(CellView cell) {
            select(cell);
        }

        @Override
        public void addBelow(CellView cell) {
            int at = cell == null ? views.size() : views.indexOf(cell) + 1;
            views.forEach(CellView::flush);
            notebook.insert(at, CellKind.CODE, "");
            rebuildAround(at);
        }

        @Override
        public void delete(CellView cell) {
            if (views.size() <= 1) {
                // The last cell is emptied rather than removed: a notebook with no cells
                // cannot be typed into, and there would be nothing left to click.
                cell.area().replaceText("");
                cell.cell().clearOutputs();
                cell.refreshOutputs();
                touched();
                return;
            }
            int at = views.indexOf(cell);
            views.forEach(CellView::flush);
            notebook.delete(at);
            rebuildAround(at);
        }

        @Override
        public void moveUp(CellView cell) {
            int at = views.indexOf(cell);
            if (at <= 0) {
                return;
            }
            views.forEach(CellView::flush);
            notebook.move(at, at - 1);
            rebuildAround(at - 1);
        }

        @Override
        public void moveDown(CellView cell) {
            int at = views.indexOf(cell);
            if (at < 0 || at >= views.size() - 1) {
                return;
            }
            views.forEach(CellView::flush);
            notebook.move(at, at + 1);
            rebuildAround(at + 1);
        }

        @Override
        public void splitAtCaret(CellView cell) {
            int at = views.indexOf(cell);
            int offset = cell.area().getCaretPosition();
            views.forEach(CellView::flush);
            notebook.split(at, offset);
            rebuildAround(at + 1);
        }

        @Override
        public void mergeWithNext(CellView cell) {
            int at = views.indexOf(cell);
            if (at < 0 || at >= views.size() - 1) {
                return;
            }
            views.forEach(CellView::flush);
            notebook.mergeWithNext(at);
            rebuildAround(at);
        }
    }
}
