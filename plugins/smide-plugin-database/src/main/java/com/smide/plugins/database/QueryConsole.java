package com.smide.plugins.database;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * A SQL console over one connection: type a statement, run it, look at what came back.
 *
 * <p>One statement at a time, and at most {@link Databases#ROW_LIMIT} rows: this is for
 * looking at data while writing code, not for exporting it.
 */
final class QueryConsole {

    private final DatabaseUi ui;
    private final DataSource source;
    private final CodeArea editor = new CodeArea();
    private final TableView<List<String>> results = new TableView<>();
    private final Label status = new Label("Ctrl+Enter runs the statement.");

    QueryConsole(DatabaseUi ui, DataSource source) {
        this.ui = ui;
        this.source = source;
    }

    void show(String initialSql) {
        show(initialSql, false);
    }

    /** @param runNow true to execute the statement as the window opens */
    void show(String initialSql, boolean runNow) {
        editor.getStyleClass().add("code-area");
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.replaceText(initialSql == null ? "" : initialSql);
        editor.moveTo(editor.getLength());

        results.setPlaceholder(new Label("No results yet."));
        results.getStyleClass().add("query-results");
        status.getStyleClass().add("muted-small");

        Button run = new Button("Run");
        run.setGraphic(new FontIcon("fth-play"));
        run.setDefaultButton(false);
        run.setTooltip(com.smide.api.ui.Tooltips.of("Run the statement (Ctrl+Enter)"));
        run.setOnAction(e -> run());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label where = new Label(source.describe());
        where.getStyleClass().add("muted-small");
        HBox bar = new HBox(8, run, gap, status, where);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));

        SplitPane split = new SplitPane(new VirtualizedScrollPane<>(editor), results);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.35);
        com.smide.api.ui.Splits.grabbable(split);

        BorderPane root = new BorderPane(split);
        root.setTop(bar);
        Stage stage = new Stage();
        com.smide.api.ui.Windows.belongsTo(stage, ui.ide().window().stage());
        stage.setTitle("Query - " + source.name());
        Scene scene = new Scene(root, 900, 600);
        // Ctrl+Enter from anywhere in the window, which is where the hands already are.
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN),
                this::run);
        stage.setScene(scene);
        ui.ide().theme().style(stage);
        stage.setOnHidden(e -> editor.dispose());
        stage.show();
        editor.requestFocus();
        if (runNow && !editor.getText().isBlank()) {
            // Opened by picking a table: the rows are what was asked for, not the SQL.
            run();
        }
    }

    /** Runs whatever is selected, or the whole text when nothing is. */
    private void run() {
        String sql = editor.getSelectedText().isBlank() ? editor.getText() : editor.getSelectedText();
        if (sql.isBlank()) {
            return;
        }
        status.setText("Running...");
        ui.read(() -> ui.databases().execute(source, sql.strip()), this::show);
    }

    private void show(Databases.Result result) {
        results.getColumns().clear();
        results.getItems().clear();
        status.setText(result.summary());
        if (result.headers().isEmpty()) {
            results.setPlaceholder(new Label(result.summary()));
            return;
        }
        for (int i = 0; i < result.headers().size(); i++) {
            int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(result.headers().get(i));
            column.setCellValueFactory(row -> new javafx.beans.property.SimpleStringProperty(
                    // A null is a NULL, and saying so beats an empty cell that could be either.
                    index < row.getValue().size() && row.getValue().get(index) != null
                            ? row.getValue().get(index) : "NULL"));
            column.setPrefWidth(140);
            results.getColumns().add(column);
        }
        results.getItems().setAll(result.rows());
    }
}
