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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Run/Debug Configurations: a list on the left, the chosen configuration's form on the right. */
public final class RunConfigurationsDialog {

    private final IdeImpl ide;
    private final Workspace workspace;
    private final ListView<RunConfiguration> list = new ListView<>();
    private final BorderPane form = new BorderPane();
    private final List<RunConfiguration> removed = new ArrayList<>();
    private final List<RunConfiguration> touched = new ArrayList<>();
    /** What each configuration looked like on the way in; see {@link #show()}. */
    private final Map<RunConfiguration, String> asOpened = new IdentityHashMap<>();
    /** What Detect did, said where the buttons are. */
    private final Label status = new Label();

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
        /* What each configuration looked like on the way in. A detected configuration is
           a fresh object every time the list is built, so editing one and pressing OK used
           to change nothing that outlived the dialog: the profile you set was gone by the
           next run. Anything that differs from its snapshot is saved, which turns a
           detected configuration into a real one the moment it is worth keeping. */
        for (RunConfiguration c : list.getItems()) {
            asOpened.put(c, snapshot(c));
        }
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
        Button detect = Icons.button("fth-zap", "Detect from the project structure", () -> detect());
        HBox tools = new HBox(4, add, remove, detect);
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
            for (RunConfiguration c : list.getItems()) {
                String opened = asOpened.get(c);
                if (opened == null || !opened.equals(snapshot(c))) {
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
        status.getStyleClass().add("muted-small");
        status.setWrapText(true);
        ButtonBar.setButtonData(status, ButtonBar.ButtonData.LEFT);
        buttons.getButtons().addAll(status, ok, cancel);
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

    /**
     * Detect: fills the open configuration from the project, and adds what the project suggests.
     *
     * <p>Two halves of one question - what can be worked out by looking at the project rather
     * than asked of the reader. The form in front of them is filled in first, because a
     * configuration with a main class and no module is the one that fails at run time with
     * nothing to go on; then the project is looked at again, and anything it suggests that is
     * not already listed is added, which brings back configurations for a project imported
     * before the plugin that knows it had finished.
     */
    private void detect() {
        List<String> filled = List.of();
        RunConfiguration open = list.getSelectionModel().getSelectedItem();
        if (open != null) {
            filled = open.type().complete(workspace, open);
            if (!filled.isEmpty()) {
                showForm(open);
                list.refresh();
            }
        }
        String first = filled.isEmpty() ? "" : "Filled in " + String.join(", ", filled) + " from the project. ";
        status.setText(first + "Looking at the project...");
        ide.executionService().detectNow(workspace, found -> {
            int added = 0;
            for (RunConfiguration candidate : found) {
                boolean listed = list.getItems().stream()
                        .anyMatch(c -> c.name().equals(candidate.name()) && c.type() == candidate.type());
                if (!listed) {
                    list.getItems().add(candidate);
                    // As it arrived: saved on OK only if it is edited, like the rest of the list.
                    asOpened.put(candidate, snapshot(candidate));
                    added++;
                }
            }
            status.setText(first + (added == 0
                    ? "Nothing further found: every configuration the project suggests is already listed."
                    : added + (added == 1 ? " configuration" : " configurations") + " found in the project."));
        });
    }

    /** Name and fields together, length-prefixed, so a rename counts as a change too. */
    private static String snapshot(RunConfiguration c) {
        return c.name().length() + ":" + c.name() + c.toMap();
    }

    private Node hint() {
        Label label = new Label(message());
        label.getStyleClass().add("empty-hint");
        label.setWrapText(true);
        return label;
    }

    /**
     * What to say when there is nothing to add.
     *
     * <p>An empty Add menu is a dead end, and "the Java plugin provides Application"
     * only helps somebody who knows why the Java plugin is not there. When nothing at
     * all has loaded - no languages either - the reason is the class path, and the
     * window that can fix it is the one that started this one.
     */
    private String message() {
        if (!ide.execution().configurationTypes().isEmpty()) {
            return "Press Add to create a configuration.";
        }
        if (ide.languages().all().isEmpty()) {
            return "Nothing to add: no plugins are loaded in this window, and run"
                    + " configuration types come from plugins - Application, JUnit, Spring"
                    + " Boot and Maven all come from the Java plugin.\n\n"
                    + "If another smIDE started this one, the configuration to change is"
                    + " that one's: Run > Edit Configurations > Classpath of module ="
                    + " the module that depends on the plugins (smide-dist in this"
                    + " repository).";
        }
        return "No run configuration types are installed. The Java plugin provides"
                + " Application, JUnit, Spring Boot and Maven.";
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
