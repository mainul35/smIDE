package com.smide.plugins.git;

import com.smide.api.Ide;
import com.smide.api.editor.LineAnnotations;
import com.smide.api.editor.TextEditor;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Git blame in the editor gutter: who last changed each line, and in which commit.
 *
 * <p>The whole file is annotated in one pass and held in memory, because the gutter asks
 * line by line as it scrolls and a question per line would mean a git call per line.
 * Clicking an annotation hands the commit to whoever asked for it - the tool window,
 * which shows what that commit did.
 */
public final class GitBlame {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private GitBlame() {
    }

    /** Switches annotations on for an editor, or off again if they are already showing. */
    public static void toggle(Ide ide, GitUi ui, TextEditor editor, Path file, Consumer<String> onCommit) {
        if (editor.lineAnnotations() != null) {
            editor.setLineAnnotations(null);
            ide.statusBar().message("Annotations off");
            return;
        }
        ui.activeRoot().ifPresentOrElse(root -> ui.git().relativize(root, file).ifPresentOrElse(
                path -> ui.read(() -> ui.git().blame(root, path), lines -> {
                    if (lines.isEmpty()) {
                        ide.statusBar().message("No annotations: " + path + " has no committed history");
                        return;
                    }
                    editor.setLineAnnotations(annotations(lines, onCommit));
                    ide.statusBar().message("Annotated " + path + " - click a line to see its commit");
                }),
                () -> ide.statusBar().message(file.getFileName() + " is not in this repository")),
                () -> ide.statusBar().message("This workspace is not a Git repository"));
    }

    private static LineAnnotations annotations(List<BlameLine> lines, Consumer<String> onCommit) {
        String widest = lines.stream().map(GitBlame::label)
                .max(java.util.Comparator.comparingInt(String::length)).orElse("");
        return new LineAnnotations() {
            @Override
            public String text(int line) {
                return line >= 0 && line < lines.size() ? label(lines.get(line)) : "";
            }

            @Override
            public String tooltip(int line) {
                if (line < 0 || line >= lines.size()) {
                    return null;
                }
                BlameLine blame = lines.get(line);
                return blame.isCommitted()
                        ? blame.shortId() + "  " + blame.author() + "\n" + blame.summary()
                        : "Not committed yet";
            }

            @Override
            public void clicked(int line) {
                if (line >= 0 && line < lines.size() && lines.get(line).isCommitted()) {
                    onCommit.accept(lines.get(line).commitId());
                }
            }

            @Override
            public String widest() {
                return widest;
            }
        };
    }

    /** Date and author, which is what identifies a change at a glance. */
    private static String label(BlameLine blame) {
        if (!blame.isCommitted()) {
            return "uncommitted";
        }
        String when = blame.date() == null ? "" : DATE.format(blame.date().atZone(ZoneId.systemDefault()));
        String who = blame.author() == null ? "" : blame.author();
        // A full name would push the code off the screen; initials and a surname do not.
        if (who.length() > 18) {
            who = who.substring(0, 17) + "…";
        }
        return when + "  " + who;
    }
}
