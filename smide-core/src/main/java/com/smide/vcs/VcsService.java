package com.smide.vcs;

import com.smide.api.editor.Editor;
import com.smide.api.util.Events;
import com.smide.api.vcs.FileStatus;
import com.smide.api.vcs.VersionControl;
import com.smide.core.ExtensionRegistry;
import com.smide.core.IdeImpl;
import com.smide.editor.CodeEditor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What has changed since the last commit, where it can be seen: beside the lines that changed,
 * and on the names of the files that changed.
 *
 * <p>The comparison is against what is in the editor rather than what is on disk, so a line is
 * marked as it is typed and unmarked the moment it is typed back - which is the difference
 * between a mark that tells you where you are and one that tells you where you were when you last
 * saved. The committed text is read once per file and kept until something moves under it: a
 * commit, a checkout, a pull, a file written by something else.
 *
 * <p>Everything that reads a repository happens off the thread that draws the window. What the
 * window asks for - the status of a file it is drawing in the tree - is answered from what has
 * already been read, and a file nobody has looked up yet is looked up in the background and drawn
 * again when the answer arrives.
 */
public final class VcsService {

    private final IdeImpl ide;
    private final ExtensionRegistry registry;
    /** The committed text of each open file, as of the last time anything moved. */
    private final Map<Path, String> committed = new ConcurrentHashMap<>();
    /** What was found for each file, for the tree to draw without waiting. */
    private final Map<Path, FileStatus> statuses = new ConcurrentHashMap<>();
    /** Files whose status is being looked up, so a tree that asks sixty times asks once. */
    private final java.util.Set<Path> asking = ConcurrentHashMap.newKeySet();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** What is not in a repository at all, so it is not asked about again. */
    private static final String NONE = "smide.vcs.no-committed-version";

    public VcsService(IdeImpl ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    public void install() {
        ide.editors().addOpenedListener(this::watch);
        ide.editors().addClosedListener(editor -> committed.remove(editor.path().toAbsolutePath().normalize()));
        ide.events().subscribe(Events.FileSaved.class, e -> refresh());
        ide.events().subscribe(Events.FilesChanged.class, e -> refresh());
        ide.events().subscribe(Events.WorkspaceOpened.class, e -> refresh());
        // A version control that arrives with a plugin brings its own news of commits and checkouts.
        registry.onVersionControlAdded(vcs -> {
            vcs.addChangeListener(this::refresh);
            refresh();
        });
        for (VersionControl vcs : registry.versionControls()) {
            vcs.addChangeListener(this::refresh);
        }
    }

    /** Told when what {@link #statusOf} answers may have changed: the file tree listens. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * What version control makes of a file, from what has already been read.
     *
     * <p>Safe to call while drawing: a file nobody has asked about before comes back as
     * {@code UNKNOWN} and is looked up in the background, and the listeners are told when the
     * answer arrives.
     */
    public FileStatus statusOf(Path file) {
        if (file == null) {
            return FileStatus.UNKNOWN;
        }
        Path key = file.toAbsolutePath().normalize();
        FileStatus known = statuses.get(key);
        if (known != null) {
            return known;
        }
        if (registry.versionControls().isEmpty() || !asking.add(key)) {
            return FileStatus.UNKNOWN;
        }
        ide.window().runInBackground(() -> {
            FileStatus found = read(key);
            asking.remove(key);
            if (statuses.put(key, found) != found) {
                fire();
            }
        });
        return FileStatus.UNKNOWN;
    }

    private FileStatus read(Path file) {
        for (VersionControl vcs : registry.versionControls()) {
            try {
                if (vcs.handles(file)) {
                    return vcs.statusOf(file);
                }
            } catch (RuntimeException e) {
                // A repository that cannot be read says nothing, rather than breaking the tree.
            }
        }
        return FileStatus.UNKNOWN;
    }

    /** Everything that was read is out of date; read what is on screen again. */
    public void refresh() {
        statuses.clear();
        committed.clear();
        ide.window().runInBackground(() -> {
            for (Editor editor : List.copyOf(ide.editors().open())) {
                if (editor instanceof CodeEditor code) {
                    load(code);
                }
            }
        });
        fire();
    }

    /** Marks an editor's lines, and keeps marking them as it is typed in. */
    private void watch(Editor editor) {
        if (!(editor instanceof CodeEditor code)) {
            return;
        }
        ide.window().runInBackground(() -> load(code));
        code.setOnChangeClicked((line, at) -> open(code, line, at));
        /* After the typing stops rather than during it: the comparison is quick, but it is not
           worth doing forty times while a line is being written. */
        javafx.animation.PauseTransition settle = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(400));
        settle.setOnFinished(e -> mark(code));
        code.addTextListener(text -> settle.playFromStart());
    }

    /** On a background thread: what the last commit has, then the marks that follow from it. */
    private void load(CodeEditor editor) {
        Path file = editor.path().toAbsolutePath().normalize();
        Optional<String> text = committedText(file);
        committed.put(file, text.orElse(NONE));
        statuses.put(file, read(file));
        ide.window().runLater(() -> mark(editor));
    }

    private Optional<String> committedText(Path file) {
        for (VersionControl vcs : registry.versionControls()) {
            try {
                if (vcs.handles(file)) {
                    return vcs.committedText(file);
                }
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Marks the editor from what is in it now.
     *
     * <p>On the window's thread, because it reads the editor's text and writes to its gutter; the
     * comparison between them is the only work, and it is a few milliseconds for a file a person
     * would edit.
     */
    private void mark(CodeEditor editor) {
        String base = committed.get(editor.path().toAbsolutePath().normalize());
        if (base == null) {
            return;
        }
        if (NONE.equals(base)) {
            /* Nothing to compare against. A file that is new since the last commit is new in
               every line of it, which is what IntelliJ shows; a file outside a repository has
               nothing said about it at all. */
            boolean brandNew = statuses.get(editor.path().toAbsolutePath().normalize()) == FileStatus.ADDED;
            editor.setChanges(brandNew ? everyLineAdded(editor.text()) : Map.of(),
                    brandNew ? List.of(new LineChanges.Hunk(LineChanges.Kind.ADDED, 0,
                            editor.lineCount(), 0, 0)) : List.of());
            return;
        }
        editor.setChanges(LineChanges.between(base, editor.text()), LineChanges.hunks(base, editor.text()));
    }

    /** What a line was when it was last committed, shown beside it. */
    private final ChangePopup popup = new ChangePopup();

    private void open(CodeEditor editor, int line, double[] at) {
        String base = committed.get(editor.path().toAbsolutePath().normalize());
        LineChanges.Hunk hunk = editor.changeAt(line).orElse(null);
        if (hunk == null || base == null) {
            return;
        }
        String committedText = NONE.equals(base) ? "" : base;
        popup.show(ide.window().stage(), ide.theme().stylesheet(), ide.theme().isDark(), hunk, committedText, at,
                new ChangePopup.Actions(
                        () -> rollback(editor, hunk, committedText),
                        step -> step(editor, hunk, step, at),
                        () -> showDiff(editor),
                        message -> commit(editor, hunk, committedText, message),
                        canCommit(editor.path())));
    }

    /**
     * Puts the committed lines back where they were.
     *
     * <p>In the editor, not on the disk: it is one more edit, undone with Ctrl+Z like any other,
     * and saved when the reader saves.
     */
    private void rollback(CodeEditor editor, LineChanges.Hunk hunk, String committedText) {
        List<String> lines = hunk.committedLines(committedText);
        int from = editor.offsetOf(Math.min(hunk.start(), Math.max(editor.lineCount() - 1, 0)), 0);
        int to = hunk.end() >= editor.lineCount()
                ? editor.text().length()
                : editor.offsetOf(hunk.end(), 0);
        String replacement = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        if (hunk.end() >= editor.lineCount() && !replacement.isEmpty() && !editor.text().endsWith("\n")) {
            replacement = replacement.substring(0, replacement.length() - 1);
        }
        editor.replace(from, Math.max(to, from), replacement);
        editor.moveCaret(Math.min(hunk.start(), Math.max(editor.lineCount() - 1, 0)), 0);
    }

    private boolean canCommit(Path file) {
        for (VersionControl vcs : registry.versionControls()) {
            if (vcs.handles(file) && vcs.canCommit(file)) {
                return true;
            }
        }
        return false;
    }

    /** The file beside its committed version, in whatever window the version control plugin has. */
    private void showDiff(CodeEditor editor) {
        ide.editors().open(editor.path());
        // The version control plugin's own window; it is not there when no plugin registered one.
        ide.actions().invoke("vcs.compare");
    }

    /**
     * Commits this one change, and nothing else.
     *
     * <p>The file is saved first, as IntelliJ saves it: what is committed is a version of the
     * file, and a version nobody could open afterwards - the commit having one line the disk does
     * not - would be a strange thing to leave behind. What is committed is the last commit's text
     * with this change applied, so the file's other changes stay where they are.
     */
    private void commit(CodeEditor editor, LineChanges.Hunk hunk, String committedText, String message) {
        Path file = editor.path();
        try {
            if (editor.isModified()) {
                editor.save();
            }
        } catch (RuntimeException e) {
            ide.notifications().error("Could not save " + file.getFileName(), String.valueOf(e.getMessage()));
            return;
        }
        String content = LineChanges.apply(committedText, editor.text(), hunk);
        ide.window().runInBackground(() -> {
            try {
                for (VersionControl vcs : registry.versionControls()) {
                    if (vcs.handles(file) && vcs.canCommit(file)) {
                        vcs.commitContent(file, content, message);
                        ide.window().runLater(() -> {
                            ide.statusBar().message("Committed one change in " + file.getFileName());
                            refresh();
                        });
                        return;
                    }
                }
            } catch (RuntimeException e) {
                ide.window().runLater(() -> ide.notifications().error("Could not commit",
                        e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            }
        });
    }

    /** The change before or after this one: the caret goes there, and it opens in turn. */
    private void step(CodeEditor editor, LineChanges.Hunk from, int direction, double[] at) {
        List<LineChanges.Hunk> all = new java.util.ArrayList<>();
        for (int line = 0; line < editor.lineCount(); line++) {
            editor.changeAt(line).filter(h -> !all.contains(h)).ifPresent(all::add);
        }
        int index = all.indexOf(from);
        int next = index < 0 ? 0 : index + direction;
        if (next < 0 || next >= all.size()) {
            return;
        }
        LineChanges.Hunk going = all.get(next);
        editor.moveCaret(Math.min(going.start(), Math.max(editor.lineCount() - 1, 0)), 0);
        open(editor, going.start(), at);
    }

    private static Map<Integer, LineChanges.Kind> everyLineAdded(String text) {
        Map<Integer, LineChanges.Kind> all = new java.util.HashMap<>();
        int line = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                if (i > 0 || text.length() > 0) {
                    all.put(line, LineChanges.Kind.ADDED);
                }
                line++;
            }
        }
        return all;
    }

    private void fire() {
        List<Runnable> toTell = new ArrayList<>(listeners);
        ide.window().runLater(() -> toTell.forEach(Runnable::run));
    }
}
