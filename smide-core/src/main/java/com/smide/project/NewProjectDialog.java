package com.smide.project;

import com.smide.api.Ide;
import com.smide.api.project.NewProjectTemplate;
import com.smide.api.ui.StatusBar;
import com.smide.core.ExtensionRegistry;
import com.smide.ui.Icons;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** File → New Project: pick a template, a name and a location. */
public final class NewProjectDialog {

    private final Ide ide;
    private final ExtensionRegistry registry;

    public NewProjectDialog(Ide ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(ide.window().stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("New Project");

        List<NewProjectTemplate> templates = new ArrayList<>(registry.templates());
        templates.add(0, emptyTemplate());
        ListView<NewProjectTemplate> list = new ListView<>();
        list.getItems().setAll(templates);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(NewProjectTemplate t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(t.displayName());
                    setGraphic(Icons.of(t.iconLiteral(), 14));
                }
            }
        });
        list.setPrefWidth(220);

        TextField name = new TextField("untitled");
        TextField location = new TextField(Path.of(System.getProperty("user.home"), "IdeaProjects").toString());
        Button browse = Icons.button("fth-folder", "Choose folder", () -> ide.window()
                .chooseDirectory("Project location", Path.of(location.getText()))
                .ifPresent(p -> location.setText(p.toString())));
        HBox.setHgrow(location, Priority.ALWAYS);
        HBox locationRow = new HBox(6, location, browse);
        Label description = new Label();
        description.getStyleClass().add("settings-note");
        description.setWrapText(true);
        BorderPane formHost = new BorderPane();
        Map<String, String> values = new HashMap<>();

        VBox right = new VBox(10,
                description,
                labelled("Name", name),
                labelled("Location", locationRow),
                formHost);
        right.setPadding(new Insets(12));
        list.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            values.clear();
            if (t != null) {
                description.setText(t.description());
                Node form = t.form(values);
                formHost.setCenter(form);
            }
        });
        list.getSelectionModel().select(0);

        Button create = new Button("Create");
        Button cancel = new Button("Cancel");
        create.setDefaultButton(true);
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        create.setOnAction(e -> {
            NewProjectTemplate t = list.getSelectionModel().getSelectedItem();
            String projectName = name.getText().strip();
            if (t == null || projectName.isEmpty()) {
                return;
            }
            Path target = Path.of(location.getText().strip()).resolve(projectName);
            if (Files.exists(target) && !isEmptyDir(target)) {
                ide.window().alert("New Project", target + " exists and is not empty.");
                return;
            }
            stage.close();
            StatusBar.Progress progress = ide.statusBar().progress("Creating " + projectName, false);
            Map<String, String> snapshot = new HashMap<>(values);
            ide.window().runInBackground(() -> {
                try {
                    Files.createDirectories(target);
                    t.generate(ide, target, projectName, snapshot);
                    ide.window().runLater(() -> {
                        ide.workspaces().open(target);
                        ide.notifications().info("Project created", target.toString());
                    });
                } catch (Exception ex) {
                    ide.notifications().error("Cannot create project", ex.getMessage());
                } finally {
                    progress.done();
                }
            });
        });
        ButtonBar buttons = new ButtonBar();
        buttons.getButtons().addAll(create, cancel);
        buttons.setPadding(new Insets(10));

        BorderPane root = new BorderPane(right);
        root.setLeft(list);
        root.setBottom(buttons);
        stage.setScene(new Scene(root, 760, 480));
        ide.theme().style(stage);
        stage.show();
    }

    private static boolean isEmptyDir(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static Node labelled(String label, Node field) {
        Label l = new Label(label);
        l.setMinWidth(70);
        HBox row = new HBox(8, l, field);
        row.setStyle("-fx-alignment: center-left;");
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    private static NewProjectTemplate emptyTemplate() {
        return new NewProjectTemplate() {
            @Override
            public String id() {
                return "empty";
            }

            @Override
            public String displayName() {
                return "Empty project";
            }

            @Override
            public String description() {
                return "A folder with a README, opened as a workspace.";
            }

            @Override
            public String iconLiteral() {
                return "fth-folder-plus";
            }

            @Override
            public Node form(Map<String, String> values) {
                return new Label();
            }

            @Override
            public void generate(Ide ide, Path location, String name, Map<String, String> values) throws Exception {
                Files.writeString(location.resolve("README.md"), "# " + name + "\n");
            }
        };
    }
}
