package com.smide.plugins.assistant;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.execution.ConsoleHandle;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.problems.Diagnostic;
import com.smide.api.project.ProjectModel;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * What the Ask agent can do to find an answer: read the project, search it, build it, look
 * something up on the web, and - with the developer's say-so - write to it.
 *
 * <p>Every one of these runs off the window's thread and comes back as text for the model to read,
 * which is the whole contract: a tool either answers or explains why it could not. Nothing here
 * throws at the agent; a tool that fails says so in words, because the model can recover from a
 * sentence and cannot recover from a stack trace.
 *
 * <p>The two that change the project - writing a file, replacing part of one - do nothing by
 * themselves. They hand the change to whoever asked for the agent to run, and that is where the
 * developer says yes or no.
 */
public final class AskTools {

    /** How much of one file goes to the model. Past this, the middle is left out and said to be. */
    private static final int FILE_CHARS = 40_000;
    /** How much build output goes back: the end of it, which is where the errors are. */
    private static final int OUTPUT_CHARS = 12_000;
    private static final int MAX_LISTED = 300;
    private static final int MAX_MATCHES = 60;
    private static final long BUILD_MINUTES = 10;

    private static final Set<String> SKIPPED = Set.of(".git", ".idea", ".smide", "target", "build",
            "out", "node_modules", "dist", ".gradle", ".mvn", "bin", "obj", "venv", ".venv", "__pycache__");

    private final Ide ide;
    private final Path root;

    public AskTools(Ide ide, Path root) {
        this.ide = ide;
        this.root = root;
    }

    /** A file the agent wants to write or remove, for the developer to accept or refuse. */
    public record Change(Kind kind, Path file, String before, String after, String what) {

        public enum Kind { CREATE, CHANGE, DELETE, COMMAND, CONFIG }

        /** Everything that is about a file, which is everything except a run configuration. */
        public Change(Kind kind, Path file, String before, String after) {
            this(kind, file, before, after, null);
        }

        public boolean isNew() {
            return kind == Kind.CREATE;
        }

        /** What this would do, in a word: for the card's button and the transcript. */
        public String verb() {
            return switch (kind) {
                case CREATE -> "Creating";
                case CHANGE -> "Changing";
                case DELETE -> "Deleting";
                case COMMAND -> "Running";
                case CONFIG -> "Setting up";
            };
        }

        public String relativeTo(Path root) {
            try {
                return what != null ? what : root.relativize(file).toString().replace('\\', '/');
            } catch (RuntimeException e) {
                return file.toString();
            }
        }
    }

    public Path root() {
        return root;
    }


    /**
     * Runs something that touches the IDE on the window's thread, and waits for the answer.
     *
     * <p>The agent works on a thread of its own, because a model takes seconds to answer and the
     * window has to stay alive; but an editor's text, the list of open files and the console a
     * build runs in all belong to the window, and asking them anything from anywhere else throws.
     * Every tool that reaches into the IDE comes through here.
     */
    private <T> T onWindow(java.util.concurrent.Callable<T> work, T whenItFails) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            try {
                return work.call();
            } catch (Exception e) {
                return whenItFails;
            }
        }
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(work);
        ide.window().runLater(task);
        try {
            return task.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return whenItFails;
        } catch (Exception e) {
            return whenItFails;
        }
    }

    // ------------------------------------------------------------------ reading

    /** One file, as the editor has it if it is open and unsaved, else as it is on disk. */
    public String readFile(String relative) {
        Path file = resolve(relative);
        if (file == null) {
            return "There is no such file in this project: " + relative;
        }
        String text = openText(file);
        if (text == null) {
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Cannot read " + relative + ": " + e.getMessage();
            }
        }
        if (text.length() > FILE_CHARS) {
            int half = FILE_CHARS / 2;
            return text.substring(0, half)
                    + "\n\n... " + (text.length() - FILE_CHARS) + " characters left out of the middle ...\n\n"
                    + text.substring(text.length() - half);
        }
        return text;
    }

    /**
     * Several files at once.
     *
     * <p>One file per step is how an agent spends twenty-four steps reading a package and never
     * gets to the question. A model that knows it needs four files should be able to say so.
     */
    public String readFiles(List<String> relatives) {
        StringBuilder out = new StringBuilder();
        int budget = FILE_CHARS * 2;
        for (String relative : relatives) {
            String text = readFile(relative);
            out.append("=== ").append(shortened(relative)).append(" ===\n");
            if (out.length() + text.length() > budget) {
                out.append("(left out: the files before this one filled the reply)\n\n");
                continue;
            }
            out.append(text).append("\n\n");
        }
        return out.toString();
    }

    /**
     * The tree under a folder, to a depth.
     *
     * <p>So that "what is in this project" is one step rather than one step per folder.
     */
    public String tree(String relative, int depth) {
        Path dir = relative == null || relative.isBlank() || ".".equals(relative) ? root : resolve(relative);
        if (dir == null || !Files.isDirectory(dir)) {
            return "There is no such folder in this project: " + relative;
        }
        List<String> lines = new ArrayList<>();
        walk(dir, dir, Math.max(1, Math.min(depth <= 0 ? 3 : depth, 8)), lines);
        if (lines.size() >= MAX_LISTED) {
            lines.add("... and more; ask for a folder in particular");
        }
        return lines.isEmpty() ? "(empty)" : String.join("\n", lines);
    }

    private void walk(Path from, Path dir, int depth, List<String> into) {
        if (depth <= 0 || into.size() >= MAX_LISTED) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                if (into.size() >= MAX_LISTED) {
                    return;
                }
                String name = entry.getFileName().toString();
                if (SKIPPED.contains(name) || name.startsWith(".") && !name.equals(".github")) {
                    continue;
                }
                String shown = from.relativize(entry).toString().replace('\\', '/');
                if (Files.isDirectory(entry)) {
                    into.add(shown + "/");
                    walk(from, entry, depth - 1, into);
                } else {
                    into.add(shown);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A folder that cannot be read is left out rather than failing the listing.
        }
    }

    /** What is in a folder, one name per line, folders marked. */
    public String listFiles(String relative) {
        Path dir = relative == null || relative.isBlank() || ".".equals(relative) ? root : resolve(relative);
        if (dir == null || !Files.isDirectory(dir)) {
            return "There is no such folder in this project: " + relative;
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (SKIPPED.contains(name)) {
                    continue;
                }
                names.add(Files.isDirectory(entry) ? name + "/" : name);
                if (names.size() >= MAX_LISTED) {
                    names.add("... and more");
                    break;
                }
            }
        } catch (IOException e) {
            return "Cannot read " + relative + ": " + e.getMessage();
        }
        return names.isEmpty() ? "(empty)" : String.join("\n", names);
    }

    /** Where a piece of text appears in the project: file, line number and the line itself. */
    public String findText(String needle) {
        if (needle == null || needle.isBlank()) {
            return "Nothing to look for.";
        }
        String wanted = needle.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root, 12)) {
            for (Path file : tree.filter(Files::isRegularFile).filter(this::worthReading).toList()) {
                if (hits.size() >= MAX_MATCHES) {
                    hits.add("... and more");
                    break;
                }
                String text;
                try {
                    if (Files.size(file) > 2_000_000) {
                        continue;
                    }
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    continue;
                }
                int line = 0;
                for (String each : text.split("\n", -1)) {
                    line++;
                    if (each.toLowerCase(Locale.ROOT).contains(wanted)) {
                        hits.add(relative(file) + ":" + line + ": " + each.strip());
                        if (hits.size() >= MAX_MATCHES) {
                            break;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return "Could not search: " + e.getMessage();
        }
        return hits.isEmpty() ? "No file in this project contains that." : String.join("\n", hits);
    }

    /** What the IDE knows about the project: build tool, modules, tasks, and what is open. */
    public String projectInfo() {
        StringBuilder out = new StringBuilder();
        out.append("Project root: ").append(root).append('\n');
        Optional<ProjectModel> model = workspace().flatMap(w -> ide.projects().modelOf(w));
        if (model.isPresent()) {
            ProjectModel project = model.get();
            out.append("Name: ").append(project.name()).append('\n');
            out.append("Built with: ").append(project.type()).append('\n');
            out.append("Modules:\n");
            for (ProjectModel.ProjectModule module : project.modules()) {
                out.append("  ").append(module.name()).append("  (").append(relative(module.root())).append(")\n");
            }
            if (!project.tasks().isEmpty()) {
                out.append("Build tasks: ");
                out.append(project.tasks().stream().map(ProjectModel.BuildTask::name).distinct().limit(25)
                        .reduce((a, b) -> a + ", " + b).orElse(""));
                out.append('\n');
            }
        } else {
            out.append("The IDE has not imported a build for this folder.\n");
        }
        List<String> open = onWindow(() -> ide.editors().open().stream()
                .map(Editor::path).map(this::relative).limit(20).toList(), List.of());
        if (!open.isEmpty()) {
            out.append("Open in the editor: ").append(String.join(", ", open)).append('\n');
        }
        return out.toString();
    }

    /** What the IDE is complaining about right now, worst first. */
    public String problems() {
        List<Diagnostic> all = onWindow(() -> ide.problems().all().stream()
                .filter(d -> d.file() != null && d.file().startsWith(root))
                .sorted((a, b) -> a.severity().compareTo(b.severity()))
                .limit(60)
                .toList(), List.of());
        if (all.isEmpty()) {
            return "The IDE is reporting nothing wrong in this project.";
        }
        StringBuilder out = new StringBuilder();
        for (Diagnostic d : all) {
            out.append(d.severity()).append("  ").append(relative(d.file())).append(':')
                    .append(d.startLine() + 1).append("  ").append(d.message()).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ building

    /** What the build says. The whole point of this one is the part where it fails. */
    public String build() {
        List<String> command = buildCommand();
        if (command.isEmpty()) {
            return "This project has no build the IDE knows how to run.";
        }
        return run(command, root, "Building for the assistant");
    }

    /**
     * What the tests say.
     *
     * <p>The other half of building. A change that compiles is a change that parses, and the
     * question the developer actually has - did this work - is answered by the project's own
     * tests or not at all.
     */
    public String test() {
        List<String> command = testCommand();
        if (command.isEmpty()) {
            return "This project has no tests the IDE knows how to run."
                    + " Say so rather than claiming the change is good.";
        }
        return run(command, root, "Testing for the assistant");
    }

    /** Runs a command in the project and gives back what it printed. */
    public String run(List<String> command, Path workingDir, String title) {
        if (!command.isEmpty() && !canRun(command.get(0))) {
            /* Said rather than attempted. The IDE starts the process and the failure comes back as
               "the system cannot find the file specified", which tells the model nothing it can
               act on - so it tries the same thing spelled differently, and again, until the steps
               are gone. This says what is missing and what the project has instead. */
            List<String> wrapper = wrapperIn(workingDir == null ? root : workingDir);
            return command.get(0) + " is not installed on this machine, or not on the PATH."
                    + (wrapper.isEmpty()
                            ? " There is no wrapper in the project either; ask the developer how they build it."
                            : " This project has its own: run " + wrapper.get(0) + " instead.");
        }
        try {
            ConsoleHandle console = onWindow(() -> ide.execution().run(
                    new ProcessSpec(title, command, workingDir == null ? root : workingDir, Map.of())), null);
            if (console == null) {
                return "Could not start " + String.join(" ", command) + ".";
            }
            Integer code = console.exitCode().get(BUILD_MINUTES, TimeUnit.MINUTES);
            String output = console.output();
            if (output.length() > OUTPUT_CHARS) {
                output = "... earlier output left out ...\n" + output.substring(output.length() - OUTPUT_CHARS);
            }
            return "$ " + String.join(" ", command) + "\nexit code: " + code + "\n\n" + output;
        } catch (java.util.concurrent.TimeoutException e) {
            return "The command was still running after " + BUILD_MINUTES + " minutes; it was left to it.";
        } catch (Exception e) {
            return "Could not run " + String.join(" ", command) + ": " + e;
        }
    }

    /** What this project builds with, for the model to be told before it guesses. */
    public String buildDescription() {
        List<String> command = buildCommand();
        return command.isEmpty() ? "no build this IDE knows how to run" : String.join(" ", command);
    }

    /** And what it tests with. */
    public String testDescription() {
        List<String> command = testCommand();
        return command.isEmpty() ? "no tests this IDE knows how to run" : String.join(" ", command);
    }

    /**
     * How this project runs its tests.
     *
     * <p>Same order as the build: the task the IDE imported, then the wrapper the project brought
     * with it, then whatever the files in the root say this is.
     */
    public List<String> testCommand() {
        Optional<ProjectModel> model = workspace().flatMap(w -> ide.projects().modelOf(w));
        if (model.isPresent()) {
            for (String wanted : List.of("test", "check", "verify")) {
                for (ProjectModel.BuildTask task : model.get().tasks()) {
                    if (task.name().equalsIgnoreCase(wanted) && !task.command().isEmpty()
                            && canRun(task.command().get(0))) {
                        return task.command();
                    }
                }
            }
        }
        List<String> wrapper = wrapperTestIn(root);
        if (!wrapper.isEmpty()) {
            return wrapper;
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return List.of("mvn", "-q", "test");
        }
        if (Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return List.of("gradle", "test");
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            return List.of("cargo", "test");
        }
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            return List.of("go", "test", "./...");
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return List.of("npm", "test");
        }
        return List.of();
    }

    /**
     * How this project builds.
     *
     * <p>The project's own build task when the IDE imported one - which is the wrapper script, the
     * right module, the lot - and otherwise whatever the files in the root say it is.
     */
    public List<String> buildCommand() {
        Optional<ProjectModel> model = workspace().flatMap(w -> ide.projects().modelOf(w));
        if (model.isPresent()) {
            for (String wanted : List.of("build", "compile", "package", "assemble")) {
                for (ProjectModel.BuildTask task : model.get().tasks()) {
                    if (task.name().equalsIgnoreCase(wanted) && !task.command().isEmpty()
                            && canRun(task.command().get(0))) {
                        return task.command();
                    }
                }
            }
        }
        List<String> wrapper = wrapperIn(root);
        if (!wrapper.isEmpty()) {
            return wrapper;
        }
        // Plain names: which file on this machine they mean is settled when the process starts.
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return List.of("mvn", "-q", "-DskipTests", "compile");
        }
        if (Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return List.of("gradle", "build", "-x", "test");
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            return List.of("cargo", "build");
        }
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            return List.of("go", "build", "./...");
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return List.of("npm", "run", "build");
        }
        return List.of();
    }

    /**
     * The build the project brought with it.
     *
     * <p>A wrapper is what a project means by "build me": it is checked in, it fetches the version
     * the project was written against, and it is there precisely because nobody can assume Maven
     * or Gradle is installed. The IDE assuming otherwise is how an agent ended up running `mvn`
     * on a machine that has no mvn, reading "the system cannot find the file specified", and
     * trying again in a slightly different way until it ran out of steps.
     */
    static List<String> wrapperIn(Path dir) {
        String maven = scriptIn(dir, windows() ? "mvnw.cmd" : "mvnw");
        if (maven != null) {
            return List.of(maven, "-q", "-DskipTests", "compile");
        }
        String gradle = scriptIn(dir, windows() ? "gradlew.bat" : "gradlew");
        return gradle == null ? List.of() : List.of(gradle, "build", "-x", "test");
    }

    /** The same wrapper, asked to run the tests instead of skipping them. */
    static List<String> wrapperTestIn(Path dir) {
        String maven = scriptIn(dir, windows() ? "mvnw.cmd" : "mvnw");
        if (maven != null) {
            return List.of(maven, "-q", "test");
        }
        String gradle = scriptIn(dir, windows() ? "gradlew.bat" : "gradlew");
        return gradle == null ? List.of() : List.of(gradle, "test");
    }

    private static String scriptIn(Path dir, String name) {
        Path script = dir.resolve(name);
        return Files.isRegularFile(script) ? script.toString() : null;
    }

    /**
     * Whether a command exists to be run: a path that is there, or a name on the PATH.
     *
     * <p>The IDE's own answer, so that what this tells the model and what starting the process
     * does cannot disagree - which they did, on Windows, over the difference between the {@code
     * mvn} shell script on the PATH and the {@code mvn.cmd} next to it.
     */
    static boolean canRun(String command) {
        return com.smide.api.execution.Executables.canRun(command);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ------------------------------------------------------------------ writing

    /** What writing this file would do, for the developer to look at before it happens. */
    public Change proposeWrite(String relative, String content) {
        Path file = root.resolve(relative).normalize();
        String before = Files.isRegularFile(file) ? readOrEmpty(file) : null;
        return new Change(before == null ? Change.Kind.CREATE : Change.Kind.CHANGE, file, before, content);
    }

    /** What removing a file would do. */
    public Change proposeDelete(String relative) {
        Path file = resolve(relative);
        if (file == null) {
            throw new IllegalArgumentException("There is no such file in this project: " + relative);
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException(relative + " is a folder, and this only removes files.");
        }
        return new Change(Change.Kind.DELETE, file, readOrEmpty(file), null);
    }

    /**
     * What replacing one piece of a file would do.
     *
     * <p>The piece must appear exactly once. A model that has read the file can quote a line from
     * it precisely, and one that has not should not be editing it: anything else is a guess at
     * which of three similar lines was meant.
     */
    public Change proposeReplace(String relative, String find, String replacement) {
        Path file = resolve(relative);
        if (file == null) {
            throw new IllegalArgumentException("There is no such file in this project: " + relative);
        }
        String before = openText(file);
        if (before == null) {
            before = readOrEmpty(file);
        }
        int at = before.indexOf(find);
        if (at < 0) {
            throw new IllegalArgumentException("That text is not in " + relative + ". Read the file again.");
        }
        if (before.indexOf(find, at + 1) >= 0) {
            throw new IllegalArgumentException("That text appears more than once in " + relative
                    + ". Quote more of it, so there is only one place it can mean.");
        }
        return new Change(Change.Kind.CHANGE, file, before,
                before.substring(0, at) + replacement + before.substring(at + find.length()));
    }

    /**
     * Writes an accepted change.
     *
     * <p>Through the editor when the file is open, so the developer can undo it with Ctrl+Z like
     * anything they typed themselves, and straight to disk when it is not.
     */
    public String apply(Change change) {
        if (change.kind() == Change.Kind.CONFIG) {
            return onWindow(() -> saveRunConfig(change), "Could not save that run configuration.");
        }
        return onWindow(() -> write(change), "Could not write " + relative(change.file()));
    }

    // ------------------------------------------------------- how the project is run

    /** Where a run configuration's settings are written for the card to show. */
    private static final String TYPE_LINE = "# type: ";

    /**
     * Every way this project can be run, and what each of them is set to.
     *
     * <p>Both kinds: the ones somebody saved and the ones the IDE worked out by looking at the
     * project. The settings are the same flat map the dialog edits and the same one the file on
     * disk holds, so what the model reads here is what it can write back.
     */
    public String runConfigurations() {
        return onWindow(() -> {
            Optional<Workspace> workspace = workspace();
            if (workspace.isEmpty()) {
                return "No project is open, so there is nothing to run.";
            }
            List<RunConfiguration> all = ide.execution().configurations(workspace.get());
            if (all.isEmpty()) {
                return "This project has no run configurations yet." + kinds();
            }
            StringBuilder out = new StringBuilder();
            for (RunConfiguration configuration : all) {
                out.append(configuration.name())
                        .append("  [").append(configuration.type().id()).append(']')
                        .append(configuration.isTemporary() ? "  (worked out from the project,"
                                + " and saved as soon as it is changed)" : "")
                        .append('\n')
                        .append(settings(configuration.toMap()))
                        .append('\n');
            }
            return out + kinds();
        }, "Could not read this project's run configurations.");
    }

    private String kinds() {
        StringBuilder out = new StringBuilder("\nKinds a new one can be: ");
        for (RunConfigurationType type : ide.execution().configurationTypes()) {
            out.append(type.id()).append(" (").append(type.displayName()).append("), ");
        }
        return out.substring(0, Math.max(0, out.length() - 2));
    }

    /** One configuration's settings, as the card shows them and as they are read back. */
    private static String settings(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> out.append("  ").append(entry.getKey()).append(" = ")
                        .append(entry.getValue() == null ? "" : entry.getValue()).append('\n'));
        return out.isEmpty() ? "  (nothing set)\n" : out.toString();
    }

    /**
     * What changing a run configuration would do, for the developer to look at first.
     *
     * <p>Held as text rather than as the configuration itself, because the card between here and
     * the change shows text and because what is shown has to be exactly what would happen. It is
     * read back in {@link #saveRunConfig}.
     */
    public Proposal proposeRunConfig(String name, String typeId, Map<String, String> values) {
        return onWindow(() -> {
            Workspace workspace = workspace().orElse(null);
            if (workspace == null) {
                return new Proposal(null, "No project is open.");
            }
            if (name == null || name.isBlank()) {
                return new Proposal(null, "A run configuration needs a name.");
            }
            RunConfiguration existing = find(workspace, name);
            if (existing == null && (typeId == null || typeId.isBlank())) {
                return new Proposal(null, "There is no run configuration called \"" + name
                        + "\". To make one, say which kind it is." + kinds());
            }
            String kind = existing != null ? existing.type().id() : typeId;
            RunConfigurationType type = typeOf(kind);
            if (type == null) {
                return new Proposal(null, "There is no kind of run configuration called \""
                        + kind + "\"." + kinds());
            }
            Map<String, String> before = existing == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.toMap());
            Map<String, String> after = new LinkedHashMap<>(before);
            after.putAll(values);
            if (existing == null) {
                after.put("name", name);
            }
            return new Proposal(new Change(Change.Kind.CONFIG,
                    root.resolve(".smide/run-configurations.json"),
                    existing == null ? "" : settings(before),
                    TYPE_LINE + kind + "\n" + settings(after),
                    "run configuration \"" + name + "\""), null);
        }, new Proposal(null, "The IDE did not answer about its run configurations."));
    }

    /** A change to offer, or the reason there is none to offer. */
    public record Proposal(Change change, String problem) {
    }

    /** Puts the settings from the card on the configuration, making it if it is not there yet. */
    private String saveRunConfig(Change change) {
        Workspace workspace = workspace().orElse(null);
        if (workspace == null) {
            return "No project is open.";
        }
        String name = change.what() == null ? "" : change.what()
                .replace("run configuration ", "").replace("\"", "").strip();
        Map<String, String> values = new LinkedHashMap<>();
        String typeId = "";
        for (String line : change.after().split("\n")) {
            if (line.startsWith(TYPE_LINE)) {
                typeId = line.substring(TYPE_LINE.length()).strip();
            } else if (line.contains(" = ")) {
                int at = line.indexOf(" = ");
                values.put(line.substring(0, at).strip(), line.substring(at + 3));
            }
        }
        RunConfiguration configuration = find(workspace, name);
        boolean made = configuration == null;
        if (made) {
            RunConfigurationType type = typeOf(typeId);
            if (type == null) {
                return "There is no kind of run configuration called \"" + typeId + "\".";
            }
            configuration = type.create(workspace);
        }
        configuration.fromMap(values);
        configuration.setName(name);
        ide.execution().saveConfiguration(configuration);
        ide.execution().selectConfiguration(configuration);
        return (made ? "Made " : "Changed ") + "the run configuration \"" + name
                + "\" and selected it. " + settings(configuration.toMap()).strip();
    }

    private RunConfiguration find(Workspace workspace, String name) {
        for (RunConfiguration configuration : ide.execution().configurations(workspace)) {
            if (configuration.name().equalsIgnoreCase(name)) {
                return configuration;
            }
        }
        return null;
    }

    private RunConfigurationType typeOf(String id) {
        for (RunConfigurationType type : ide.execution().configurationTypes()) {
            if (type.id().equalsIgnoreCase(id) || type.displayName().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }

    private String write(Change change) {
        try {
            if (change.kind() == Change.Kind.DELETE) {
                ide.editors().find(change.file()).ifPresent(editor -> ide.editors().close(editor));
                Files.deleteIfExists(change.file());
                return "Removed " + relative(change.file());
            }
            Optional<Editor> open = ide.editors().find(change.file());
            if (open.isPresent() && open.get().asText().isPresent()) {
                open.get().asText().get().setText(change.after());
                open.get().save();
            } else {
                Files.createDirectories(change.file().getParent());
                Files.writeString(change.file(), change.after(), StandardCharsets.UTF_8);
                ide.editors().open(change.file());
            }
            return (change.isNew() ? "Created " : "Changed ") + relative(change.file());
        } catch (IOException | RuntimeException e) {
            return "Could not write " + relative(change.file()) + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Workspace> workspace() {
        return ide.workspaces().all().stream().filter(w -> w.root().equals(root)).findFirst()
                .or(() -> ide.workspaces().active());
    }

    /** A path inside the project, or null for one that is outside it or not there. */
    private Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path file = root.resolve(relative.strip()).normalize();
        return file.startsWith(root) && Files.exists(file) ? file : null;
    }

    /** A path as the project sees it: an absolute one the model sent, shortened to the root. */
    public String shortened(String given) {
        if (given == null || given.isBlank()) {
            return "the project";
        }
        try {
            Path file = root.resolve(given.strip()).normalize();
            return file.startsWith(root) ? relative(file) : given;
        } catch (RuntimeException e) {
            return given;
        }
    }

    public String relative(Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return String.valueOf(file);
        }
    }

    private String openText(Path file) {
        return onWindow(() -> ide.editors().find(file).flatMap(Editor::asText)
                .map(com.smide.api.editor.TextEditor::text).orElse(null), null);
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private boolean worthReading(Path file) {
        for (Path part : root.relativize(file)) {
            if (SKIPPED.contains(part.toString())) {
                return false;
            }
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return !Set.of("jar", "class", "png", "jpg", "jpeg", "gif", "pdf", "zip", "gz", "exe", "dll", "so",
                "bin", "ico", "woff", "woff2", "ttf", "mp4", "webm").contains(extension);
    }
}
