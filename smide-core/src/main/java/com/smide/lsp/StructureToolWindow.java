package com.smide.lsp;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.editor.CodeEditor;
import com.smide.ui.Icons;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** The Structure tool window: the active file's symbols from its language server. */
public final class StructureToolWindow implements ToolWindowFactory {

    public static final String ID = "structure";

    private record Node2(String name, String detail, SymbolKind kind, int line, int column) {
    }

    private final Ide ide;
    private final LspManager manager;
    private final TreeView<Node2> tree = new TreeView<>();
    private final TreeItem<Node2> root = new TreeItem<>();
    private final Label empty = new Label("Open a file with a language server to see its structure.");
    private final StackPane pane = new StackPane();
    private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(800));
    private Editor current;
    /** Editors given a text listener already: an editor becomes active many times, and needs one. */
    private final java.util.Set<CodeEditor> listening = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private boolean visible;
    private int generation;

    public StructureToolWindow(Ide ide, LspManager manager) {
        this.ide = ide;
        this.manager = manager;
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setCellFactory(v -> new SymbolCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                TreeItem<Node2> item = tree.getSelectionModel().getSelectedItem();
                if (item != null && item.getValue() != null && current != null) {
                    current.asText().ifPresent(t -> {
                        t.moveCaret(item.getValue().line(), item.getValue().column());
                        t.focus();
                    });
                }
            }
        });
        empty.getStyleClass().add("empty-hint");
        empty.setWrapText(true);
        pane.getChildren().addAll(empty, tree);
        refreshDelay.setOnFinished(e -> refresh());
        ide.editors().addActiveListener(e -> {
            current = e.orElse(null);
            if (current instanceof CodeEditor code && listening.add(code)) {
                code.addTextListener(t -> {
                    if (visible && current == code) {
                        refreshDelay.playFromStart();
                    }
                });
            }
            if (visible) {
                refresh();
            }
        });
        manager.addSessionListener(s -> s.addStateListener(state -> {
            if (visible && state == LspSession.State.READY) {
                refreshDelay.playFromStart();
            }
        }));
    }

    private void refresh() {
        int gen = ++generation;
        Optional<EditorLspBinding> binding = current == null ? Optional.empty() : manager.bindingOf(current);
        if (binding.isEmpty() || !binding.get().session().isReady()
                || binding.get().session().capabilities().getDocumentSymbolProvider() == null) {
            root.getChildren().clear();
            empty.setVisible(true);
            tree.setVisible(false);
            return;
        }
        CodeEditor editor = binding.get().editor();
        binding.get().session().server().getTextDocumentService()
                .documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(Positions.uri(editor.path()))))
                .orTimeout(10, TimeUnit.SECONDS)
                .whenComplete((symbols, error) -> Platform.runLater(() -> {
                    if (gen != generation || error != null || symbols == null) {
                        return;
                    }
                    root.getChildren().clear();
                    for (Either<SymbolInformation, DocumentSymbol> s : symbols) {
                        if (s.isRight()) {
                            root.getChildren().add(item(s.getRight()));
                        } else {
                            SymbolInformation info = s.getLeft();
                            root.getChildren().add(new TreeItem<>(new Node2(info.getName(), info.getContainerName(),
                                    info.getKind(), info.getLocation().getRange().getStart().getLine(),
                                    info.getLocation().getRange().getStart().getCharacter())));
                        }
                    }
                    boolean any = !root.getChildren().isEmpty();
                    empty.setVisible(!any);
                    tree.setVisible(any);
                }));
    }

    private static TreeItem<Node2> item(DocumentSymbol s) {
        var range = s.getSelectionRange() != null ? s.getSelectionRange() : s.getRange();
        TreeItem<Node2> item = new TreeItem<>(new Node2(s.getName(), s.getDetail(), s.getKind(),
                range.getStart().getLine(), range.getStart().getCharacter()));
        item.setExpanded(true);
        if (s.getChildren() != null) {
            for (DocumentSymbol child : s.getChildren()) {
                item.getChildren().add(item(child));
            }
        }
        return item;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Structure";
    }

    @Override
    public String iconLiteral() {
        return "fth-list";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.LEFT;
    }

    @Override
    public String shortcut() {
        return "alt+7";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public Node create(ToolWindowContext context) {
        visible = true;
        refresh();
        return pane;
    }

    private static final class SymbolCell extends TreeCell<Node2> {
        @Override
        protected void updateItem(Node2 n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(n.name() + (n.detail() == null || n.detail().isBlank() ? "" : "  " + n.detail()));
            setGraphic(Icons.of(iconFor(n.kind()), 13));
        }

        private static String iconFor(SymbolKind kind) {
            if (kind == null) {
                return "fth-circle";
            }
            return switch (kind) {
                case Method, Function, Constructor -> "fth-zap";
                case Field, Property, Variable, Constant, EnumMember -> "fth-box";
                case Class, Interface, Struct, Enum, Object -> "fth-layers";
                case Module, Namespace, Package, File -> "fth-package";
                default -> "fth-circle";
            };
        }
    }
}
