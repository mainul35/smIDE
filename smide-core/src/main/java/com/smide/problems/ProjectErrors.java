package com.smide.problems;

import com.smide.api.Ide;
import com.smide.api.problems.Diagnostic;
import com.smide.api.problems.Problems;
import com.smide.api.workspace.Workspace;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The errors the IDE already knows a project has, and the question to ask before building
 * or running it anyway.
 *
 * <p>IntelliJ stops a build that cannot succeed before it starts, and says why: a type that
 * does not compile, a dependency Maven does not have. Starting it regardless only makes the
 * reader wait for the build tool to reach the same conclusion and then read it out of a
 * console. So before Run, Debug or Build, the errors the language servers and the pom check
 * have already reported are counted, and when there are any the reader is shown them and
 * asked - build anyway, look at them, or stop. Warnings do not ask; only errors.
 */
public final class ProjectErrors {

    /** How many errors the dialog lists before saying how many more there are. */
    private static final int LISTED = 6;

    private ProjectErrors() {
    }

    /** The errors reported for files inside this folder. */
    public static List<Diagnostic> in(Problems problems, Path root) {
        Path folder = root.toAbsolutePath().normalize();
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : problems.all()) {
            if (d.severity() == Diagnostic.Severity.ERROR && d.file() != null
                    && d.file().toAbsolutePath().normalize().startsWith(folder)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Whether to go ahead with {@code action} - "Run", "Debug", "Build" - on this project.
     * True at once when it has no errors; otherwise the reader decides. Must be called on the
     * JavaFX thread; from any other there is nobody to ask, and it goes ahead.
     */
    public static boolean proceed(Ide ide, Workspace workspace, String action) {
        if (workspace == null || !Platform.isFxApplicationThread()) {
            return true;
        }
        List<Diagnostic> errors = in(ide.problems(), workspace.root());
        if (errors.isEmpty()) {
            return true;
        }
        Set<Path> files = new LinkedHashSet<>();
        errors.forEach(d -> files.add(d.file()));

        StringBuilder listed = new StringBuilder();
        for (Diagnostic d : errors.subList(0, Math.min(LISTED, errors.size()))) {
            listed.append("• ").append(relative(workspace.root(), d.file())).append(':').append(d.startLine() + 1)
                    .append("  ").append(firstLine(d.message())).append('\n');
        }
        if (errors.size() > LISTED) {
            listed.append("… and ").append(errors.size() - LISTED).append(" more.\n");
        }

        ButtonType anyway = new ButtonType(action + " anyway", ButtonBar.ButtonData.OK_DONE);
        ButtonType show = new ButtonType("Show problems", ButtonBar.ButtonData.OTHER);
        ButtonType stop = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", anyway, show, stop);
        alert.setTitle(action + " with errors?");
        alert.setHeaderText(workspace.name() + " has " + count(errors.size(), "error") + " in "
                + count(files.size(), "file") + ". " + action + " will probably fail on "
                + (errors.size() == 1 ? "it" : "them") + ".");
        alert.setContentText(listed.toString().stripTrailing());
        alert.getDialogPane().setMinWidth(560);
        focusedWindow().ifPresent(alert::initOwner);
        try {
            ide.theme().style(alert.getDialogPane().getScene().getWindow());
        } catch (RuntimeException e) {
            // The platform's colours will do for one question.
        }
        ButtonType chosen = alert.showAndWait().orElse(stop);
        if (chosen == show) {
            ide.toolWindows().show(ProblemsToolWindow.ID);
            return false;
        }
        return chosen == anyway;
    }

    private static Optional<Window> focusedWindow() {
        return Window.getWindows().stream().filter(Window::isFocused).findFirst()
                .or(() -> Window.getWindows().stream().filter(Window::isShowing).findFirst());
    }

    private static String relative(Path root, Path file) {
        try {
            return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip();
        int end = line.indexOf('\n');
        line = end < 0 ? line : line.substring(0, end);
        return line.length() > 140 ? line.substring(0, 137) + "..." : line;
    }

    private static String count(int n, String thing) {
        return n + " " + thing + (n == 1 ? "" : "s");
    }
}
