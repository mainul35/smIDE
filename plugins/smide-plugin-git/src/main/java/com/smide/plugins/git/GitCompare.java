package com.smide.plugins.git;

import com.smide.api.Ide;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Comparing a file, or part of one, with another branch, tag or revision.
 *
 * <p>Two steps: pick something to compare against, then look at the two versions side by
 * side. Both the picking and the reading happen off the JavaFX thread through
 * {@link GitUi}; only the windows are built here.
 */
public final class GitCompare {

    private GitCompare() {
    }

    /**
     * Compares the whole file with a revision the user picks.
     *
     * @param lines when non-null, only these lines take part - {@code [first, last]},
     *              zero-based and inclusive, which is how a selection is compared
     */
    public static void open(Ide ide, GitUi ui, Path file, int[] lines) {
        ui.activeRoot().ifPresentOrElse(root -> ui.git().relativize(root, file).ifPresentOrElse(
                path -> choose(ide, ui, root, revision ->
                        ui.read(() -> ui.git().fileAt(root, revision, path), text ->
                                show(ide, ui, file, path, revision, text, lines))),
                () -> ide.statusBar().message(file.getFileName() + " is not in this repository")),
                () -> ide.statusBar().message("This workspace is not a Git repository"));
    }

    /** Asks which branch, tag or revision, then hands it over. */
    private static void choose(Ide ide, GitUi ui, Path root, Consumer<String> onChosen) {
        ui.read(() -> ui.git().refs(root), refs -> {
            Stage stage = new Stage();
            stage.initOwner(ide.window().stage());
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setTitle("Compare with");

            ListView<RefInfo> list = new ListView<>();
            List<RefInfo> sorted = new ArrayList<>(refs);
            // Local branches first, then remotes, then tags: nearest to hand at the top.
            sorted.sort(Comparator.comparing((RefInfo r) -> r.kind().ordinal()).thenComparing(RefInfo::name));
            list.getItems().setAll(sorted);
            list.setCellFactory(v -> new ListCell<>() {
                @Override
                protected void updateItem(RefInfo ref, boolean empty) {
                    super.updateItem(ref, empty);
                    setText(empty || ref == null ? null
                            : ref.name() + (ref.current() ? "   (current branch)" : "")
                            + "   " + ref.kind().title().toLowerCase(java.util.Locale.ROOT));
                }
            });

            TextField revision = new TextField();
            revision.setPromptText("...or any revision: a commit id, HEAD~2, v1.0^");

            Button ok = new Button("Compare");
            Button cancel = new Button("Cancel");
            ok.setDefaultButton(true);
            cancel.setCancelButton(true);
            Runnable accept = () -> {
                String typed = revision.getText().strip();
                RefInfo picked = list.getSelectionModel().getSelectedItem();
                if (typed.isEmpty() && picked == null) {
                    return;
                }
                stage.close();
                onChosen.accept(typed.isEmpty() ? picked.name() : typed);
            };
            ok.setOnAction(e -> accept.run());
            cancel.setOnAction(e -> stage.close());
            list.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    accept.run();
                }
            });

            ButtonBar buttons = new ButtonBar();
            buttons.getButtons().addAll(ok, cancel);
            buttons.setPadding(new Insets(10));
            VBox top = new VBox(6, new Label("Compare with a branch, a tag, or a revision:"), revision);
            top.setPadding(new Insets(10, 10, 4, 10));
            BorderPane root2 = new BorderPane(list);
            root2.setTop(top);
            root2.setBottom(buttons);
            stage.setScene(new Scene(root2, 460, 420));
            ide.theme().style(stage);
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().select(0);
            }
            stage.show();
        });
    }

    /** Opens the window with the two versions in it. */
    private static void show(Ide ide, GitUi ui, Path file, String path, String revision,
                             String revisionText, int[] lines) {
        String working = read(file);
        String leftText = revisionText;
        String rightText = working;
        String leftLabel = path + "  at  " + revision;
        String rightLabel = path + "  (working tree)";
        if (lines != null) {
            /* A selection is compared against the same line numbers on the other side.
               That is the honest reading of "compare this with main": it says whether
               these lines differ there, without pretending to track where they moved. */
            leftText = slice(revisionText, lines[0], lines[1]);
            rightText = slice(working, lines[0], lines[1]);
            String range = "lines " + (lines[0] + 1) + "-" + (lines[1] + 1);
            leftLabel = path + "  " + range + "  at  " + revision;
            rightLabel = path + "  " + range + "  (working tree)";
        }
        SideBySideDiff diff = new SideBySideDiff(ide.theme());
        diff.setContent(leftLabel, leftText, rightLabel, rightText);

        Stage stage = new Stage();
        stage.initOwner(ide.window().stage());
        stage.setTitle(file.getFileName() + " - working tree against " + revision);
        stage.setScene(new Scene(diff, 1000, 640));
        ide.theme().style(stage);
        stage.show();
    }

    /** The lines of a text between two zero-based bounds, inclusive. */
    private static String slice(String text, int first, int last) {
        String[] all = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, first); i <= Math.min(last, all.length - 1); i++) {
            out.append(all[i]).append('\n');
        }
        return out.toString();
    }

    private static String read(Path file) {
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
