package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.ui.StatusBarWidget;
import com.smide.editor.CodeEditor;
import com.smide.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.Optional;

/** "JDT LS: ready" on the status bar, for the active editor's language. Click to restart. */
public final class LspStatusWidget implements StatusBarWidget {

    private final Ide ide;
    private final LspManager manager;
    private final HBox box = new HBox(4);
    private final Label label = new Label();
    private Editor current;

    public LspStatusWidget(Ide ide, LspManager manager) {
        this.ide = ide;
        this.manager = manager;
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(label);
        box.setOnMouseClicked(e -> {
            if (current != null) {
                manager.restart(current);
            }
        });
        Tooltip.install(box, com.smide.api.ui.Tooltips.of("Language server for this file. Click to restart."));
        ide.editors().addActiveListener(e -> {
            current = e.orElse(null);
            update();
        });
        manager.addSessionListener(s -> s.addStateListener(state -> update()));
        update();
    }

    private void update() {
        Optional<LspSession> session = current == null ? Optional.empty() : manager.sessionFor(current);
        if (current instanceof CodeEditor code && session.isEmpty()
                && code.language().languageServer().isPresent()) {
            label.setText(code.language().languageServer().get().displayName() + ": not installed");
            label.setGraphic(Icons.of("fth-download", 12));
            box.setVisible(true);
            box.setManaged(true);
            return;
        }
        if (session.isEmpty()) {
            box.setVisible(false);
            box.setManaged(false);
            return;
        }
        LspSession s = session.get();
        String state = switch (s.state()) {
            case STARTING -> "starting";
            case READY -> "ready";
            case FAILED -> "failed";
            case STOPPED -> "stopped";
        };
        label.setText(s.displayName() + ": " + state);
        label.setGraphic(Icons.of(switch (s.state()) {
            case READY -> "fth-check-circle";
            case STARTING -> "fth-loader";
            default -> "fth-alert-circle";
        }, 12));
        box.setVisible(true);
        box.setManaged(true);
    }

    @Override
    public String id() {
        return "lsp";
    }

    @Override
    public Node node() {
        return box;
    }

    @Override
    public int order() {
        return 50;
    }
}
