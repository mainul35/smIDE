package com.smide.api.problems;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.editor.TextEditor;
import com.smide.api.workspace.Workspace;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Watches a kind of build file and reports what it names but the machine has not got.
 *
 * <p>What the IDE does for a pom - a dependency that cannot be resolved drawn in red where it is
 * written, as it is typed - belongs to every build file, not only Maven's: a mistyped coordinate
 * in a Gradle script, a package that is not installed, a crate that does not exist. A plugin says
 * which files are its own and what is wrong inside one; the checking, the watching and the
 * publishing are the same for all of them and are done here.
 *
 * <p>Each file is checked when it is opened, shortly after each edit, and every twenty seconds -
 * the last because what a build downloads arrives without any file in the project changing, and
 * a dependency that has just been fetched should stop being red without anybody touching the
 * file. Files nobody has opened are checked too, a little less often, so a build file is red in
 * the tree before it is opened.
 *
 * <p>The check reads files, so it runs off the thread that draws the window, one file at a time.
 */
public final class ManifestChecks {

    /** What is wrong inside one build file: given its text, the problems to draw in it. */
    @FunctionalInterface
    public interface Check {
        /**
         * @param file the build file, which exists but may be half-typed
         * @param text what the editor holds, which may be newer than the disk
         * @return the problems, or an empty list when there are none
         */
        List<Diagnostic> problemsIn(Path file, String text);
    }

    /** Folders nothing worth checking lives in, and which are large. */
    private static final Set<String> SKIPPED = Set.of(".git", ".idea", ".smide", "target", "build", "out",
            "node_modules", "dist", ".gradle", ".mvn", "bin", "obj", "venv", ".venv", "__pycache__");
    private static final int MAX_DEPTH = 6;

    private final Ide ide;
    private final String source;
    private final Predicate<Path> mine;
    private final Check check;
    private final ExecutorService worker;
    private final Map<Path, Integer> generations = new ConcurrentHashMap<>();
    private volatile List<Path> known = List.of();
    private int ticks;

    private ManifestChecks(Ide ide, String source, Predicate<Path> mine, Check check) {
        this.ide = ide;
        this.source = source;
        this.mine = mine;
        this.check = check;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smide-checks-" + source);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts watching.
     *
     * @param source what to report the problems under, e.g. {@code gradle}; one source per kind of
     *               file, because setting a file's problems replaces what that source said before
     * @param mine   whether a path is a build file of this kind
     */
    public static void watch(Ide ide, String source, Predicate<Path> mine, Check check) {
        new ManifestChecks(ide, source, mine, check).install();
    }

    private void install() {
        ide.editors().addOpenedListener(this::opened);
        ide.editors().addClosedListener(editor -> {
            if (mine.test(editor.path())) {
                // Closed, it is what is on disk that counts - unsaved edits went with the editor.
                worker.execute(() -> fromDisk(editor.path()));
            }
        });
        ide.workspaces().addOpenedListener(workspace -> ide.window().runLater(this::sweep));
        ide.workspaces().addClosedListener(workspace -> {
            for (Path file : known) {
                if (file.startsWith(workspace.root())) {
                    ide.problems().set(source, file, List.of());
                }
            }
        });
        ide.window().runLater(() -> {
            ide.editors().open().forEach(this::opened);
            sweep();
            Timeline again = new Timeline(new KeyFrame(Duration.seconds(20), e -> {
                recheckOpen();
                // Every third time, the ones nobody has opened: a minute is soon enough for those.
                if (++ticks % 3 == 0) {
                    sweep();
                }
            }));
            again.setCycleCount(Animation.INDEFINITE);
            again.play();
            timeline = again;
        });
    }

    /** Held so it is not collected: a Timeline nothing refers to stops running. */
    private Timeline timeline;

    private void opened(Editor editor) {
        if (!mine.test(editor.path())) {
            return;
        }
        editor.asText().ifPresent(text -> {
            check(editor.path(), text.text());
            PauseTransition settle = new PauseTransition(Duration.millis(600));
            settle.setOnFinished(e -> check(editor.path(), text.text()));
            text.addTextListener(changed -> settle.playFromStart());
        });
    }

    private void recheckOpen() {
        for (Editor editor : ide.editors().open()) {
            if (mine.test(editor.path())) {
                editor.asText().map(TextEditor::text).ifPresent(text -> check(editor.path(), text));
            }
        }
    }

    /** Checks one file's text; a later check of the same file supersedes this one. */
    private void check(Path file, String text) {
        int generation = generations.merge(file, 1, Integer::sum);
        worker.execute(() -> {
            // Compared as numbers: two boxed Integers above 127 are never the same object.
            if (generations.get(file).intValue() != generation) {
                return;
            }
            publish(file, text);
        });
    }

    private void publish(Path file, String text) {
        try {
            ide.problems().set(source, file, check.problemsIn(file, text));
        } catch (RuntimeException e) {
            System.err.println("smIDE: could not check " + file + ": " + e);
        }
    }

    private void fromDisk(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                ide.problems().set(source, file, List.of());
                return;
            }
            publish(file, Files.readString(file));
        } catch (IOException | RuntimeException e) {
            // Unreadable now; the next sweep tries again.
        }
    }

    /**
     * Every build file of the open projects, from disk, except those open in an editor - those
     * are checked from what the editor holds, which is newer.
     */
    private void sweep() {
        Set<Path> open = new HashSet<>();
        for (Editor editor : ide.editors().open()) {
            if (mine.test(editor.path())) {
                open.add(editor.path().toAbsolutePath().normalize());
            }
        }
        List<Path> roots = new ArrayList<>();
        for (Workspace workspace : ide.workspaces().all()) {
            roots.add(workspace.root());
        }
        worker.execute(() -> {
            List<Path> found = new ArrayList<>();
            for (Path root : roots) {
                found.addAll(buildFilesUnder(root));
            }
            known = List.copyOf(found);
            for (Path file : found) {
                if (!open.contains(file.toAbsolutePath().normalize())) {
                    fromDisk(file);
                }
            }
        });
    }

    private List<Path> buildFilesUnder(Path root) {
        try (Stream<Path> tree = Files.walk(root, MAX_DEPTH)) {
            return tree.filter(p -> !skipped(root, p)).filter(Files::isRegularFile).filter(mine).toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static boolean skipped(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            String name = part.toString();
            if (SKIPPED.contains(name) || (name.startsWith(".") && name.length() > 1 && !name.equals(".github"))) {
                return true;
            }
        }
        return false;
    }
}
