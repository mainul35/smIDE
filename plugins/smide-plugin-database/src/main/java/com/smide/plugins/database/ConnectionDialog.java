package com.smide.plugins.database;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The form for a connection: which database, where it is, and who to connect as.
 *
 * <p>The URL is shown as it is built, and can be typed over. That covers the databases
 * in the list, a server with options none of them expect, and - with {@code Other} and a
 * driver class - anything with a JDBC driver at all.
 *
 * <p>Test Connection is the point of the dialog. Getting a host or a password wrong is
 * the normal case, and finding out when the tree fails to expand later is no way to
 * learn it.
 */
final class ConnectionDialog {

    private final DatabaseUi ui;

    ConnectionDialog(DatabaseUi ui) {
        this.ui = ui;
    }

    /** Shows the form; calls back with the connection when the user accepts it. */
    void show(DataSource initial, Consumer<DataSource> onAccept) {
        Stage stage = new Stage();
        stage.initOwner(ui.ide().window().stage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(initial == null ? "New Connection" : "Edit Connection");
        DataSource start = initial == null ? DataSource.blank() : initial;

        ChoiceBox<DatabaseKind> kind = new ChoiceBox<>();
        kind.getItems().setAll(DatabaseKind.values());
        kind.setValue(start.kind());
        kind.setMaxWidth(Double.MAX_VALUE);
        TextField name = new TextField(start.name());
        TextField host = new TextField(start.host());
        TextField port = new TextField(String.valueOf(start.port()));
        TextField database = new TextField(start.database());
        TextField user = new TextField(start.user());
        PasswordField password = new PasswordField();
        password.setPromptText("asked for when connecting if left empty");
        TextField url = new TextField(start.url());
        TextField driver = new TextField(start.driver());
        driver.setPromptText("the JDBC driver class, for a driver that is not bundled");
        TextField driverPath = new TextField(start.driverPath());
        driverPath.setPromptText("empty to use the bundled driver");
        Button browse = new Button("Browse...");
        Button fetch = new Button("Download");
        /* A text area, not a field: the point is to paste the <dependency> block from a
           driver's page, and a single-line field throws the newlines away. */
        TextArea maven = new TextArea();
        maven.setPrefRowCount(3);
        maven.setWrapText(true);
        maven.setPromptText("group:artifact:version, or paste a <dependency> block");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        Label databaseLabel = new Label("Database");
        grid.addRow(0, new Label("Type"), kind);
        grid.addRow(1, new Label("Name"), name);
        grid.addRow(2, new Label("Host"), host);
        grid.addRow(3, new Label("Port"), port);
        grid.addRow(4, databaseLabel, database);
        grid.addRow(5, new Label("User"), user);
        grid.addRow(6, new Label("Password"), password);
        grid.addRow(7, new Label("URL"), url);
        grid.addRow(8, new Label("Driver class"), driver);
        HBox jarRow = new HBox(6, driverPath, browse);
        HBox.setHgrow(driverPath, Priority.ALWAYS);
        grid.addRow(9, new Label("Driver jar"), jarRow);
        HBox mavenRow = new HBox(6, maven, fetch);
        HBox.setHgrow(maven, Priority.ALWAYS);
        grid.addRow(10, new Label("From Maven"), mavenRow);

        /* The URL follows the fields until it is edited, and then it is left alone: a URL
           someone typed is the one they meant, not a preview to be overwritten. */
        boolean[] urlEdited = {initial != null && !start.customUrl().isBlank()};
        url.textProperty().addListener((o, was, now) -> {
            if (url.isFocused()) {
                urlEdited[0] = true;
            }
        });
        Runnable rebuild = () -> {
            DatabaseKind selected = kind.getValue();
            boolean file = selected.isFile();
            host.setDisable(file);
            port.setDisable(file || selected == DatabaseKind.OTHER);
            user.setDisable(selected == DatabaseKind.SQLITE);
            databaseLabel.setText(file ? "File" : "Database");
            // The driver fields stay usable for every type: a bundled driver can be
            // replaced by the version a particular server needs.
            driver.setDisable(false);
            if (!urlEdited[0]) {
                int number;
                try {
                    number = Integer.parseInt(port.getText().strip());
                } catch (NumberFormatException e) {
                    number = selected.defaultPort();
                }
                url.setText(selected.url(host.getText().strip(), number, database.getText().strip()));
            }
        };
        kind.valueProperty().addListener((o, was, now) -> {
            if (now != null && was != null && now.defaultPort() != was.defaultPort()) {
                port.setText(String.valueOf(now.defaultPort()));
            }
            rebuild.run();
        });
        for (TextField field : new TextField[]{host, port, database}) {
            field.textProperty().addListener((o, was, now) -> rebuild.run());
        }
        rebuild.run();

        Label result = new Label();
        result.getStyleClass().add("settings-note");
        result.setWrapText(true);

        browse.setOnAction(e -> ui.ide().window()
                .chooseFile("JDBC driver jar", java.nio.file.Path.of(System.getProperty("user.home")))
                .ifPresent(file -> driverPath.setText(file.toString())));
        fetch.setOnAction(e -> {
            String coordinates = maven.getText().strip();
            if (coordinates.isEmpty()) {
                return;
            }
            result.setText("Downloading " + coordinates + "...");
            ui.withProgress("Downloading " + coordinates,
                    () -> Drivers.fetch(ui.ide(), coordinates, (message, fraction) -> { }),
                    jar -> {
                        driverPath.setText(jar.toString());
                        result.setText("Driver in " + jar.getFileName());
                    });
        });

        Label note = new Label("The password is kept in memory for this session and is never written"
                + " to disk; leave it empty to be asked when the connection opens. Drivers for MySQL,"
                + " MariaDB, PostgreSQL, SQL Server, SQLite and H2 are bundled; for any other database,"
                + " attach its driver jar, or paste its Maven dependency and fetch it.");
        note.getStyleClass().add("settings-note");
        note.setWrapText(true);

        Button test = new Button("Test Connection");
        Button ok = new Button(initial == null ? "Add" : "Save");
        Button cancel = new Button("Cancel");
        ok.setDefaultButton(true);
        cancel.setCancelButton(true);

        Supplier<DataSource> read = () -> {
            DatabaseKind selected = kind.getValue();
            int number;
            try {
                number = Integer.parseInt(port.getText().strip());
            } catch (NumberFormatException e) {
                number = selected.defaultPort();
            }
            String typed = url.getText().strip();
            String custom = urlEdited[0] && !typed.equals(selected.url(host.getText().strip(), number,
                    database.getText().strip())) ? typed : "";
            return new DataSource(selected,
                    name.getText().strip().isEmpty() ? "Connection" : name.getText().strip(),
                    host.getText().strip(), number, database.getText().strip(), user.getText().strip(),
                    custom, driver.getText().strip(), driverPath.getText().strip());
        };

        test.setOnAction(e -> {
            DataSource candidate = read.get();
            ui.databases().setPassword(candidate, password.getText());
            result.setText("Connecting...");
            ui.withProgress("Testing " + candidate.name(),
                    () -> ui.databases().test(candidate),
                    banner -> result.setText("Connected to " + banner));
        });
        ok.setOnAction(e -> {
            DataSource candidate = read.get();
            if (!password.getText().isEmpty()) {
                ui.databases().setPassword(candidate, password.getText());
            }
            stage.close();
            onAccept.accept(candidate);
        });
        cancel.setOnAction(e -> stage.close());

        ButtonBar buttons = new ButtonBar();
        /* Every button's role is stated. Left to infer it, the bar decides for itself
           where each one goes, and a button can end up under the panel above it - which
           is a button that looks fine and does nothing when clicked. */
        ButtonBar.setButtonData(test, ButtonBar.ButtonData.LEFT);
        ButtonBar.setButtonData(ok, ButtonBar.ButtonData.OK_DONE);
        ButtonBar.setButtonData(cancel, ButtonBar.ButtonData.CANCEL_CLOSE);
        buttons.getButtons().addAll(test, ok, cancel);
        buttons.setPadding(new Insets(10, 14, 14, 14));

        // One column, so nothing can overlap the buttons however tall the form gets.
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        VBox root = new VBox(6, grid, note, result, gap, buttons);
        root.setFillWidth(true);
        root.setPadding(new Insets(0, 14, 0, 14));
        stage.setScene(new Scene(root, 560, 540));
        ui.ide().theme().style(stage);
        stage.show();
    }
}
