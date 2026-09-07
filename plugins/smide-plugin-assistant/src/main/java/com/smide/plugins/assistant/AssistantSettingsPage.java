package com.smide.plugins.assistant;

import com.mdviewer.ai.AiConfig;
import com.smide.api.Ide;
import com.smide.api.settings.SettingsPage;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Settings &gt; Tools &gt; Assistant: which model answers, and what it is allowed to see.
 *
 * <p>Two different kinds of setting sit here, and they are kept apart. Provider and model
 * are preferences, staged like everything else in this dialog and written on Apply. The
 * address, the key and the host permission are written straight into
 * {@code ~/.smide/ai.properties} by the button that changes them - they are not the
 * dialog's to hold, and a key that appears to be saved because OK has not been pressed yet
 * is a bad way to find out otherwise.
 *
 * <p>Allowing a host is its own tick, deliberately. Choosing a provider is a preference;
 * letting it receive this codebase is not, and one control doing both would make the
 * second an accident of the first.
 */
final class AssistantSettingsPage implements SettingsPage {

    private final Ide ide;
    private final Assistant assistant;
    private final AssistantConfig config;

    AssistantSettingsPage(Ide ide, Assistant assistant) {
        this.ide = ide;
        this.assistant = assistant;
        this.config = assistant.config();
    }

    @Override
    public String path() {
        return "Tools/Assistant";
    }

    @Override
    public String keywords() {
        return "assistant ai llm model provider review practice openai ollama litellm key host";
    }

    @Override
    public Node create(SettingsEditor editor) {
        AiConfig ai = config.ai();

        ComboBox<String> provider = new ComboBox<>();
        provider.getItems().setAll(ai.providerNames());
        provider.setValue(config.provider());
        provider.setMaxWidth(Double.MAX_VALUE);

        TextField baseUrl = new TextField();
        ComboBox<String> model = new ComboBox<>();
        model.setEditable(true);
        model.setMaxWidth(Double.MAX_VALUE);
        PasswordField key = new PasswordField();
        key.setPromptText("leave empty to keep whatever is set");
        CheckBox saveKey = new CheckBox("Write this key into " + config.file().getFileName());
        CheckBox allowHost = new CheckBox("Allow this host to receive code from this IDE");
        Label result = new Label();
        result.getStyleClass().add("settings-note");
        result.setWrapText(true);

        Runnable load = () -> {
            String name = provider.getValue();
            AiConfig.Endpoint endpoint = ai.endpoint(name == null ? "" : name);
            baseUrl.setText(endpoint.baseUrl());
            model.getItems().setAll(endpoint.model().isBlank() ? List.of() : List.of(endpoint.model()));
            model.setValue(name != null && name.equals(config.provider())
                    ? config.model() : endpoint.model());
            allowHost.setSelected(ai.isAllowed(endpoint.baseUrl()));
            allowHost.setText(endpoint.host() == null
                    ? "Allow this host to receive code from this IDE"
                    : "Allow " + endpoint.host() + " to receive code from this IDE");
            key.clear();
            result.setText(ai.hasKey(name == null ? "" : name)
                    ? "A key is set for this provider." : "No key is set for this provider.");
        };
        load.run();
        provider.valueProperty().addListener((o, was, now) -> {
            if (now != null) {
                editor.staged().set(AssistantConfig.PROVIDER_KEY, now);
                load.run();
            }
        });
        model.valueProperty().addListener((o, was, now) ->
                editor.staged().set(AssistantConfig.MODEL_KEY, now == null ? "" : now.strip()));

        Button saveEndpoint = new Button("Save address");
        saveEndpoint.setOnAction(e -> {
            String name = provider.getValue();
            if (name == null) {
                return;
            }
            boolean ok = ai.saveEndpoint(name, baseUrl.getText(), model.getValue());
            if (ok && !key.getText().isBlank()) {
                if (saveKey.isSelected()) {
                    ai.saveKey(name, key.getText());
                } else {
                    ai.setRuntimeKey(name, key.getText());
                }
                key.clear();
            }
            /* The host follows the address, and never the other way round: an address
               edited to point somewhere new is not permission to send anything there. */
            if (allowHost.isSelected()) {
                ai.saveAllowedHost(baseUrl.getText());
            }
            result.setText(ok ? "Saved to " + config.file() : "Could not write " + config.file());
            load.run();
        });

        Button fetchModels = new Button("List models");
        fetchModels.setTooltip(new javafx.scene.control.Tooltip(
                "Asks the endpoint what it offers. Sends no code."));
        fetchModels.setOnAction(e -> {
            String name = provider.getValue();
            AiConfig.Endpoint endpoint = ai.endpoint(name == null ? "" : name);
            result.setText("Asking " + endpoint.host() + "...");
            ide.window().runInBackground(() -> {
                List<String> names = assistant.models(endpoint);
                ide.window().runLater(() -> {
                    if (names.isEmpty()) {
                        result.setText("No model list came back. Check the address, the key,"
                                + " and that the host is allowed.");
                        return;
                    }
                    String chosen = model.getValue();
                    model.getItems().setAll(names);
                    model.setValue(names.contains(chosen) ? chosen : names.get(0));
                    result.setText(names.size() + " models offered by " + endpoint.host() + ".");
                });
            });
        });

        Button test = new Button("Test connection");
        test.setOnAction(e -> {
            String name = provider.getValue();
            AiConfig.Endpoint endpoint = ai.endpoint(name == null ? "" : name);
            result.setText("Testing " + endpoint.host() + "...");
            ide.window().runInBackground(() -> {
                String answer = assistant.test(endpoint);
                ide.window().runLater(() -> result.setText(answer));
            });
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(90);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        grid.addRow(0, new Label("Provider"), provider);
        grid.addRow(1, new Label("Address"), baseUrl);
        HBox modelRow = new HBox(6, model, fetchModels);
        HBox.setHgrow(model, Priority.ALWAYS);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(2, new Label("Model"), modelRow);
        grid.addRow(3, new Label("API key"), key);

        HBox buttons = new HBox(8, saveEndpoint, test);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(4, 0, 0, 0));

        Label section = new Label("MODEL");
        section.getStyleClass().add("settings-section");
        Label permissions = new Label("WHAT IT MAY SEE");
        permissions.getStyleClass().add("settings-section");

        Label endpointNote = new Label("Provider and model apply on OK. The address, the key"
                + " and the host permission are written to " + config.file()
                + " when Save address is pressed. A key can also be left in the environment:"
                + " put ${env:NAME} in that file instead of the key itself.");
        endpointNote.getStyleClass().add("settings-note");
        endpointNote.setWrapText(true);

        Label hostNote = new Label("The assistant sends the file you are reviewing, and the"
                + " files around it, to the endpoint above. A host that is not allowed is"
                + " refused before the request is built, so a mistyped address cannot"
                + " quietly ship this codebase to a stranger.");
        hostNote.getStyleClass().add("settings-note");
        hostNote.setWrapText(true);

        CheckBox scope = new CheckBox("Send related files with a review");
        scope.setSelected(config.projectScope());
        scope.selectedProperty().addListener((o, was, now) ->
                editor.staged().set(AssistantConfig.SCOPE_KEY, now ? "project" : "file"));
        Label scopeNote = new Label("With this off, a review sees only the open file - which is"
                + " less useful and sends less. Either way nothing is sent until you press"
                + " Review.");
        scopeNote.getStyleClass().add("settings-note");
        scopeNote.setWrapText(true);

        VBox box = new VBox(8, section, grid, buttons, endpointNote,
                permissions, allowHost, saveKey, hostNote, scope, scopeNote, result);
        box.setFillWidth(true);
        return box;
    }
}
