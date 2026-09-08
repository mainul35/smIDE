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
import java.util.function.Supplier;

/**
 * Settings &gt; Tools &gt; Assistant: which model answers, and what it is allowed to see.
 *
 * <p>Two different kinds of setting sit here, and they go to two different places.
 * Provider and model are preferences, staged like everything else in this dialog and
 * written to the IDE's settings on Apply. The address, the key and the host permission
 * belong to {@code ~/.smide/ai.properties}, which the assistant and MDViewer both read.
 *
 * <p>Both are saved by OK, and by the Save now button for somebody who wants to test the
 * connection before closing the dialog. They did not used to be: the key was written only
 * by the button, and only with a tick that lived three sections away among the
 * permissions, so the ordinary case - type a key, press OK - kept the key in memory and
 * lost it at the next restart. A setting that appears to have been saved and was not is
 * worse than one that refuses to save at all.
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
        /* Remembering it is the default, and the tick sits under the field it is about.
           It used to be off, and three sections further down among the permissions, which
           made the ordinary case - type a key, press OK, use the assistant - the one that
           silently did not survive a restart. Somebody who wants a key to live only as
           long as the process is running can still say so; nobody wants to be told that
           by a 401 the next morning. */
        CheckBox saveKey = new CheckBox("Remember this key on this machine");
        saveKey.setSelected(true);
        saveKey.setTooltip(new javafx.scene.control.Tooltip(
                "Writes the key into " + config.file() + " as text. Untick to keep it only"
                        + " until the IDE closes."));
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
        /* Pin what is on screen, so OK writes it whether or not anything was touched. The
           choice was otherwise only staged when the dropdown changed, and a choice that
           was never written falls back to provider.default at the next start: configure
           one provider, restart, and the request goes to a different one whose key is
           stale or missing. That looks exactly like a key that was not saved. */
        editor.staged().set(AssistantConfig.PROVIDER_KEY,
                provider.getValue() == null ? "" : provider.getValue());
        editor.staged().set(AssistantConfig.MODEL_KEY,
                model.getValue() == null ? "" : model.getValue().strip());
        provider.valueProperty().addListener((o, was, now) -> {
            if (now != null) {
                editor.staged().set(AssistantConfig.PROVIDER_KEY, now);
                load.run();
            }
        });
        model.valueProperty().addListener((o, was, now) ->
                editor.staged().set(AssistantConfig.MODEL_KEY, now == null ? "" : now.strip()));

        /* Writes the address, the key and the host permission, and says what it did.
           Both the button and OK run it. The trap otherwise is a key typed into the field
           and OK pressed: the dialog closes looking exactly as it does when it worked, and
           the first anybody hears of it is a 401 after the next restart. */
        Supplier<String> persist = () -> {
            String name = provider.getValue();
            if (name == null) {
                return "";
            }
            String address = baseUrl.getText() == null ? "" : baseUrl.getText().strip();
            String chosen = model.getValue() == null ? "" : model.getValue().strip();
            AiConfig.Endpoint stored = ai.endpoint(name);
            boolean ok = true;
            if (!stored.baseUrl().equals(address) || !stored.model().equals(chosen)) {
                ok = ai.saveEndpoint(name, address, chosen);
            }
            String typed = key.getText();
            String note;
            if (typed != null && !typed.isBlank()) {
                if (saveKey.isSelected()) {
                    ok = ai.saveKey(name, typed) && ok;
                    note = "Key written to " + config.file() + ".";
                } else {
                    ai.setRuntimeKey(name, typed);
                    note = "Key kept for this session only; it is gone when the IDE closes.";
                }
            } else {
                note = ai.hasKey(name) ? "A key is set for this provider."
                        : "No key is set for this provider.";
            }
            /* The host follows the address, and never the other way round: an address
               edited to point somewhere new is not permission to send anything there. */
            if (allowHost.isSelected() && !ai.isAllowed(address)) {
                ai.saveAllowedHost(address);
            }
            return (ok ? "Saved " + name + ". " : "Could not write " + config.file() + ". ") + note;
        };

        Button saveEndpoint = new Button("Save now");
        saveEndpoint.setTooltip(new javafx.scene.control.Tooltip(
                "Writes the address, the key and the host permission straight away."
                        + " OK saves them too; this is for testing the connection first."));
        saveEndpoint.setOnAction(e -> {
            String said = persist.get();
            load.run();
            result.setText(said);
        });

        // OK and Apply save what was typed here, the same as the button does.
        editor.onApply(persist::get);

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
        grid.addRow(4, new Label(""), saveKey);

        HBox buttons = new HBox(8, saveEndpoint, test);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(4, 0, 0, 0));

        Label section = new Label("MODEL");
        section.getStyleClass().add("settings-section");
        Label permissions = new Label("WHAT IT MAY SEE");
        permissions.getStyleClass().add("settings-section");

        Label endpointNote = new Label("OK saves all of this: provider, model, address, key and"
                + " host permission. The address, the key and the host permission go into "
                + config.file() + ", which is a text file on this machine - the key is not"
                + " encrypted there. To keep it out of the file entirely, untick Remember and"
                + " retype it each session, or put ${env:NAME} in that file and set that"
                + " environment variable instead.");
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
                permissions, allowHost, hostNote, scope, scopeNote, result);
        box.setFillWidth(true);
        return box;
    }
}
