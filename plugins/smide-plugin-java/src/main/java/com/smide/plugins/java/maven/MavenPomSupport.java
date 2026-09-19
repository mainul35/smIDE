package com.smide.plugins.java.maven;

import com.smide.api.Ide;
import com.smide.api.editor.DeclarationProvider;
import com.smide.api.editor.Editor;
import com.smide.api.editor.TextEditor;
import com.smide.api.problems.Diagnostic;
import com.smide.api.workspace.Workspace;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * What IntelliJ does for a pom: a dependency Maven does not have is drawn in red, and
 * Ctrl+click on one that it does have opens its pom.
 *
 * <p>Every open pom is checked when it is opened, shortly after each edit, and every twenty
 * seconds - the last because what a Maven build downloads arrives in the local repository
 * without any file in the project changing, and a dependency that has just been fetched should
 * stop being red without the reader having to touch the pom.
 *
 * <p>The check reads files - parents, BOMs, the repository - so it runs off the thread that
 * draws the window, one pom at a time, and what it finds is published as problems under the
 * source {@code maven}: in the editor, and in the Problems window beside everything else.
 */
public final class MavenPomSupport implements DeclarationProvider {

    public static final String SOURCE = "maven";
    private static final Set<String> SKIPPED = Set.of(".git", ".idea", ".smide", "target", "build", "out",
            "node_modules", "dist", ".gradle", ".mvn", "bin", "obj");
    private static final long MODULE_CACHE_MILLIS = 30_000;

    private final Ide ide;
    private final PomResolver resolver;
    private final PomReferences references;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smide-maven-poms");
        t.setDaemon(true);
        return t;
    });
    private final Map<Path, Integer> generations = new ConcurrentHashMap<>();
    private volatile Map<String, Path> modules = Map.of();
    /** Every pom the last walk of the open projects found. */
    private volatile List<Path> poms = List.of();
    private volatile long modulesAt;

    public MavenPomSupport(Ide ide) {
        this(ide, LocalRepository.locate());
    }

    MavenPomSupport(Ide ide, LocalRepository repository) {
        this.ide = ide;
        this.resolver = new PomResolver(repository, this::modules);
        this.references = new PomReferences(resolver);
    }

    /** Starts watching the poms that are opened, and the ones already open. */
    public void install() {
        ide.editors().addOpenedListener(this::opened);
        ide.editors().addClosedListener(editor -> {
            if (isPom(editor.path())) {
                // Closed, it is what is on disk that counts - unsaved edits went with the editor.
                checkOnDisk(editor.path());
            }
        });
        ide.workspaces().addOpenedListener(workspace -> {
            modulesAt = 0;
            Platform.runLater(this::sweep);
        });
        ide.workspaces().addClosedListener(workspace -> {
            for (Path pom : poms) {
                if (pom.startsWith(workspace.root())) {
                    ide.problems().set(SOURCE, pom, List.of());
                }
            }
            modulesAt = 0;
        });
        Platform.runLater(() -> {
            ide.editors().open().forEach(this::opened);
            sweep();
            Timeline again = new Timeline(new KeyFrame(Duration.seconds(20), e -> {
                recheckOpen();
                // Every third time, every pom in the projects: a minute is soon enough for a closed file.
                if (++ticks % 3 == 0) {
                    sweep();
                }
            }));
            again.setCycleCount(Animation.INDEFINITE);
            again.play();
        });
    }

    private int ticks;

    /**
     * Checks every pom of the open projects, from disk, except the ones open in an editor -
     * those are checked from what the editor holds, which is newer.
     *
     * <p>So that a pom nobody has opened is still red in the file tree when it names something
     * Maven does not have, and still counted when a build asks whether the project has errors.
     */
    private void sweep() {
        java.util.Set<Path> open = new java.util.HashSet<>();
        for (Editor editor : ide.editors().open()) {
            if (isPom(editor.path())) {
                open.add(editor.path().toAbsolutePath().normalize());
            }
        }
        worker.execute(() -> {
            modules();
            for (Path pom : poms) {
                if (!open.contains(pom.toAbsolutePath().normalize())) {
                    scanOnDisk(pom);
                }
            }
        });
    }

    private void checkOnDisk(Path pom) {
        worker.execute(() -> scanOnDisk(pom));
    }

    /** On the worker thread: one pom as it is on disk; a deleted one takes its problems with it. */
    private void scanOnDisk(Path pom) {
        try {
            if (!Files.isRegularFile(pom)) {
                ide.problems().set(SOURCE, pom, List.of());
                return;
            }
            String text = Files.readString(pom);
            references.scan(pom, text).ifPresent(all -> ide.problems().set(SOURCE, pom, diagnostics(pom, text, all)));
        } catch (IOException | RuntimeException e) {
            // Unreadable now; the next sweep tries again.
        }
    }

    static boolean isPom(Path file) {
        String name = file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        return name.equals("pom.xml") || name.endsWith(".pom");
    }

    private void opened(Editor editor) {
        if (!isPom(editor.path())) {
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
            if (isPom(editor.path())) {
                editor.asText().map(TextEditor::text).ifPresent(text -> check(editor.path(), text));
            }
        }
    }

    /** Checks one pom's text and publishes what is missing; a later check of the same file supersedes it. */
    void check(Path file, String text) {
        int generation = generations.merge(file, 1, Integer::sum);
        worker.execute(() -> {
            // Compared as numbers: two boxed Integers above 127 are never the same object.
            if (generations.get(file).intValue() != generation) {
                return;
            }
            try {
                Optional<List<PomReferences.Reference>> found = references.scan(file, text);
                // Unreadable is the pom half-typed: what was shown stays, rather than flickering away and back.
                found.ifPresent(all -> ide.problems().set(SOURCE, file, diagnostics(file, text, all)));
            } catch (RuntimeException e) {
                System.err.println("smIDE: could not check " + file + ": " + e);
            }
        });
    }

    /** The missing references, as problems drawn in red on their names. */
    static List<Diagnostic> diagnostics(Path file, String text, List<PomReferences.Reference> all) {
        List<Diagnostic> out = new ArrayList<>();
        for (PomReferences.Reference r : all) {
            if (r.state() != PomReferences.State.MISSING) {
                continue;
            }
            int[] start = lineColumn(text, r.start());
            int[] end = lineColumn(text, r.end());
            out.add(new Diagnostic(file, start[0], start[1], end[0], end[1], Diagnostic.Severity.ERROR,
                    r.message(), SOURCE, Diagnostic.UNRESOLVED));
        }
        return out;
    }

    private static int[] lineColumn(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
    }

    // ------------------------------------------------------------------ Ctrl+click

    /**
     * Where the name under the caret leads: the pom of the dependency, BOM, parent, plugin or
     * module named there. Nothing is claimed away from those names, so everywhere else in the
     * pom the XML language server answers as before.
     */
    @Override
    public Optional<Declaration> declarationAt(Path file, String text, int offset) {
        if (!isPom(file)) {
            return Optional.empty();
        }
        return references.at(file, text, offset).map(r -> r.target() != null
                ? Declaration.at(r.target(), 0, 0)
                : Declaration.nowhere(r.message() != null ? r.message() : r.coordinates() + " leads nowhere."));
    }

    /**
     * The span to draw as a link under Ctrl: the group, artifact or version under the pointer,
     * of a reference that leads to a pom. A missing one is drawn red, never as a link.
     */
    @Override
    public Optional<Span> linkAt(Path file, String text, int offset) {
        if (!isPom(file)) {
            return Optional.empty();
        }
        return scanned(file, text).flatMap(all -> all.stream()
                .filter(r -> r.target() != null)
                .flatMap(r -> r.spans().stream())
                .filter(span -> offset >= span[0] && offset < span[1])
                .findFirst()
                .map(span -> new Span(span[0], span[1])));
    }

    /**
     * The last scan, kept while the text is the same: Ctrl+hover asks on every pointer move,
     * and reading the pom and its parents again for each would be work for nothing.
     */
    private record Scan(Path file, String text, List<PomReferences.Reference> references) {
    }

    private volatile Scan lastScan;

    private Optional<List<PomReferences.Reference>> scanned(Path file, String text) {
        Scan scan = lastScan;
        if (scan != null && scan.file().equals(file) && scan.text().equals(text)) {
            return Optional.of(scan.references());
        }
        Optional<List<PomReferences.Reference>> found = references.scan(file, text);
        found.ifPresent(all -> lastScan = new Scan(file, text, all));
        return found;
    }

    // ------------------------------------------------------------------ open projects

    /**
     * The poms of the open projects, by {@code group:artifact}.
     *
     * <p>A dependency on another module of the same project is that module, not whatever
     * version of it was last installed into the local repository - it is what Ctrl+click
     * should open, and it is resolved whether or not it has ever been built. Found by walking
     * the projects' folders, kept for half a minute so that typing in a pom is not a walk of the
     * whole tree per keystroke.
     */
    private Map<String, Path> modules() {
        if (ide == null) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        if (now - modulesAt < MODULE_CACHE_MILLIS) {
            return modules;
        }
        Map<String, Path> found = new HashMap<>();
        List<Path> every = new ArrayList<>();
        for (Workspace workspace : ide.workspaces().all()) {
            try (Stream<Path> walk = Files.walk(workspace.root(), 8)) {
                walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("pom.xml"))
                        .filter(p -> !skipped(workspace.root(), p))
                        .limit(2000)
                        .peek(every::add)
                        .forEach(pom -> resolver.read(pom).ifPresent(model -> {
                            String groupId = PomResolver.groupOf(model);
                            if (groupId != null && model.getArtifactId() != null) {
                                found.putIfAbsent(groupId + ":" + model.getArtifactId(), pom);
                            }
                        }));
            } catch (IOException | RuntimeException e) {
                // A project that cannot be walked contributes no modules; its dependencies resolve from the repository.
            }
        }
        modules = Map.copyOf(found);
        poms = List.copyOf(every);
        modulesAt = now;
        return modules;
    }

    private static boolean skipped(Path root, Path pom) {
        Path relative = root.relativize(pom);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (SKIPPED.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    /** For tests: the resolver, to check against a repository of their own. */
    PomReferences references() {
        return references;
    }
}
