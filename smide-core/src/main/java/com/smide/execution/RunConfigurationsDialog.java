package com.smide.execution;

import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.workspace.Workspace;
import com.smide.core.IdeImpl;
import com.smide.ui.Icons;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/** Run/Debug Configurations: a list on the left, the chosen configuration's form on the right. */
public final class RunConfigurationsDialog {

    private final IdeImpl ide;
    private final Workspace workspace;
    private final ListView<RunConfiguration> list = new ListView<>();
    private final BorderPane form = new BorderPane();
    private final List<RunConfiguration> removed = new ArrayList<>();
    private final List<RunConfiguration> touched = new ArrayList<>();

    public RunConfigurationsDialog(IdeImpl ide, Workspace workspace) {
        this.ide = ide;
        this.workspace = workspace;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(ide.window().stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Run/Debug Configurations - " + workspace.name());

        list.getItems().setAll(ide.execution().configurations(workspace));
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(RunConfiguration c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(c.name() + (c.isTemporary() ? "  (detected)" : ""));
                    setGraphic(Icons.of(c.type().iconLiteral(), 13));
                }
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, c) -> showForm(c));

        MenuButton add = new MenuButton("Add");
        add.setGraphic(Icons.of("fth-plus", 13));
        for (RunConfigurationType type : ide.execution().configurationTypes()) {
            MenuItem item = new MenuItem(type.displayName());
            item.setGraphic(Icons.of(type.iconLiteral(), 13));
            item.setOnAction(e -> {
                RunConfiguration c = type.create(workspace);
                c.setName(uniqueName(type.displayName()));
                list.getItems().add(c);
                touched.add(c);
                list.getSelectionModel().select(c);
            });
            add.getItems().add(item);
        }
        Button remove = Icons.button("fth-minus", "Remove", () -> {
            RunConfiguration c = list.getSelectionModel().getSelectedItem();
            if (c != null) {
                list.getItems().remove(c);
                removed.add(c);
                touched.remove(c);
            }
        });
        HBox tools = new HBox(4, add, remove);
        tools.setPadding(new Insets(4));
        BorderPane left = new BorderPane(list);
        left.setTop(tools);

        Button ok = new Button("OK");
        Button cancel = new Button("Cancel");
        ok.setDefaultButton(true);
        cancel.setCancelButton(true);
        ok.setOnAction(e -> {
            for (RunConfiguration c : removed) {
                if (!c.isTemporary()) {
                    ide.execution().deleteConfiguration(c);
                }
            }
            for (RunConfiguration c : touched) {
                if (list.getItems().contains(c)) {
                    ide.execution().saveConfiguration(c);
                }
            }
            RunConfiguration selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                ide.execution().selectConfiguration(selected);
            }
            stage.close();
        });
        cancel.setOnAction(e -> stage.close());
        ButtonBar buttons = new ButtonBar();
        buttons.getButtons().addAll(ok, cancel);
        buttons.setPadding(new Insets(10));

        SplitPane split = new SplitPane(left, form);
        split.setDividerPositions(0.3);
        BorderPane root = new BorderPane(split);
        root.setBottom(buttons);
        stage.setScene(new Scene(root, 860, 560));
        ide.theme().style(stage);
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        } else {
            form.setCenter(hint());
        }
        stage.show();
    }

    private Node hint() {
        Label label = new Label(ide.execution().configurationTypes().isEmpty()
                ? "No run configuration types are installed. The Java plugin provides Application, JUnit, Spring Boot and Maven."
                : "Press Add to create a configuration.");
        label.getStyleClass().add("empty-hint");
        label.setWrapText(true);
        return label;
    }

    private void showForm(RunConfiguration c) {
        if (c == null) {
            form.setCenter(hint());
            return;
        }
        if (!touched.contains(c)) {
            touched.add(c);
        }
        TextField name = new TextField(c.name());
        name.textProperty().addListener((o, a, b) -> {
            c.setName(b);
            list.refresh();
        });
        HBox nameRow = new HBox(8, new Label("Name"), name);
        nameRow.setStyle("-fx-alignment: center-left;");
        javafx.scene.layout.HBox.setHgrow(name, javafx.scene.layout.Priority.ALWAYS);
        Label type = new Label(c.type().displayName());
        type.getStyleClass().add("muted-small");
        Node editor = c.type().editor(c);
        VBox box = new VBox(10, nameRow, type, editor);
        box.setPadding(new Insets(12));
        form.setCenter(box);
    }

    private String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (hasName(name)) {
            name = base + " " + n++;
        }
        return name;
    }

    private boolean hasName(String name) {
        return list.getItems().stream().anyMatch(c -> c.name().equals(name));
    }
}
