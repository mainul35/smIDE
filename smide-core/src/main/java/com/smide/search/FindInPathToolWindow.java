package com.smide.search;

import com.smide.api.Ide;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.workspace.Workspace;
import com.smide.lang.LanguageRegistry;
import com.smide.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find in Files: a query over every text file in the open workspaces, results as
 * {@code file:line  text}, double-click to open at the hit.
 */
public final class FindInPathToolWindow implements ToolWindowFactory {

    public static final String ID = "find";

    private record Hit(Path file, Path root, int line, int column, int length, String text) {
    }

    private final Ide ide;
    private final LanguageRegistry languages;
    private final FileIndex index;
    private final TextField query = new TextField();
    private final TextField mask = new TextField();
    private final ToggleButton matchCase = new ToggleButton("Aa");
    private final ToggleButton regex = new ToggleButton(".*");
    private final ToggleButton wholeWord = new ToggleButton("W");
    private final Label summary = new Label();
    private final ListView<Hit> results = new ListView<>();
    private final BorderPane root = new BorderPane();
    private ToolWindowContext context;
    private int generation;

    public FindInPathToolWindow(Ide ide, LanguageRegistry languages, FileIndex index) {
        this.ide = ide;
        this.languages = languages;
        this.index = index;
        query.setPromptText("Find in files (Enter to search)");
        query.getStyleClass().add("popup-field");
        HBox.setHgrow(query, Priority.ALWAYS);
        mask.setPromptText("File mask, e.g. *.java");
        mask.setPrefWidth(150);
        matchCase.setTooltip(new Tooltip("Match case"));
        regex.setTooltip(new Tooltip("Regular expression"));
        wholeWord.setTooltip(new Tooltip("Whole words"));
        for (ToggleButton t : List.of(matchCase, regex, wholeWord)) {
            t.setFocusTraversable(false);
        }
        summary.getStyleClass().add("muted-small");
        HBox bar = new HBox(6, query, matchCase, regex, wholeWord, mask,
                Icons.button("fth-search", "Search", this::search), summary);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-padding: 6;");
        bar.getStyleClass().add("find-bar");
        query.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                search();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                results.requestFocus();
                results.getSelectionModel().selectFirst();
                e.consume();
            }
        });
        results.setCellFactory(v -> new HitCell());
        results.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                open();
            }
        });
        results.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                open();
                e.consume();
            }
        });
        root.setTop(bar);
        root.setCenter(results);
    }

    private void open() {
        Hit hit = results.getSelectionModel().getSelectedItem();
        if (hit != null) {
            ide.editors().open(hit.file(), hit.line(), hit.column());
        }
    }

    public void focusWith(String initial) {
        if (context != null) {
            context.show();
        }
        if (initial != null && !initial.isBlank()) {
            query.setText(initial);
        }
        Platform.runLater(() -> {
            query.requestFocus();
            query.selectAll();
        });
    }

    private void search() {
        String text = query.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        int gen = ++generation;
        Pattern pattern;
        try {
            int flags = matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            String source = regex.isSelected() ? text : Pattern.quote(text);
            if (wholeWord.isSelected()) {
                source = "\\b(?:" + source + ")\\b";
            }
            pattern = Pattern.compile(source, flags);
        } catch (PatternSyntaxException e) {
            summary.setText("Bad pattern: " + e.getDescription());
            return;
        }
        Pattern fileMask = maskPattern(mask.getText());
        List<Workspace> workspaces = ide.workspaces().all();
        summary.setText("Searching...");
        results.getItems().clear();
        ide.window().runInBackground(() -> {
            List<Hit> hits = new ArrayList<>();
            int files = 0;
            for (Workspace w : workspaces) {
                for (Path file : index.files(List.of(w))) {
                    if (gen != generation) {
                        return;
                    }
                    if (fileMask != null && !fileMask.matcher(file.getFileName().toString()).matches()) {
                        continue;
                    }
                    if (!languages.isText(file)) {
                        continue;
                    }
                    try {
                        if (Files.size(file) > 2_000_000) {
                            continue;
                        }
                        files++;
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            Matcher m = pattern.matcher(lines.get(i));
                            while (m.find()) {
                                hits.add(new Hit(file, w.root(), i, m.start(), m.end() - m.start(), lines.get(i).strip()));
                                if (hits.size() >= 5000) {
                                    break;
                                }
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // Unreadable or not UTF-8; skip it.
                    }
                    if (hits.size() >= 5000) {
                        break;
                    }
                }
            }
            int scanned = files;
            Platform.runLater(() -> {
                if (gen != generation) {
                    return;
                }
                results.getItems().setAll(hits);
                summary.setText(hits.size() + (hits.size() >= 5000 ? "+" : "") + " matches in " + scanned + " files");
                if (context != null) {
                    context.setTitle("Find  " + hits.size() + " results");
                }
            });
        });
    }

    private static Pattern maskPattern(String mask) {
        if (mask == null || mask.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : mask.split("[,;]")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(p.replace(".", "\\.").replace("*", ".*").replace("?", "."));
        }
        return sb.length() == 0 ? null : Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Find";
    }

    @Override
    public String iconLiteral() {
        return "fth-search";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.BOTTOM;
    }

    @Override
    public String shortcut() {
        return "alt+3";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        return root;
    }

    private final class HitCell extends ListCell<Hit> {
        @Override
        protected void updateItem(Hit hit, boolean empty) {
            super.updateItem(hit, empty);
            if (empty || hit == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String rel = hit.root().relativize(hit.file()).toString().replace('\\', '/');
            Label where = new Label(rel + ":" + (hit.line() + 1));
            where.getStyleClass().add("muted-small");
            where.setMinWidth(220);
            Label text = new Label(hit.text().length() > 200 ? hit.text().substring(0, 200) : hit.text());
            HBox row = new HBox(12, where, text);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }
}
