package com.smide.explorer;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.core.ExtensionRegistry;
import com.smide.lang.LanguageRegistry;
import com.smide.ui.Icons;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Project tool window: one lazy tree over every open workspace, every file shown,
 * build output and VCS directories dimmed.
 */
public final class ExplorerToolWindow implements ToolWindowFactory {

    public static final String ID = "explorer";
    private static final Set<String> DIMMED = Set.of(
            ".git", ".svn", ".hg", "target", "build", "out", "dist", "node_modules", ".idea", ".smide", ".gradle",
            ".mvn", "__pycache__", ".venv", "venv", ".settings", "bin", "obj", ".vs", ".vscode", ".cache");

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final LanguageRegistry languages;
    private final TreeView<Path> tree = new TreeView<>();
    private final ContextMenu contextMenu = new ContextMenu();
    private final TreeItem<Path> hiddenRoot = new TreeItem<>();
    private final Consumer<Set<Path>> onDirectoriesChanged;
    private FileWatchService watcher;
    private BorderPane root;

    public ExplorerToolWindow(Ide ide, ExtensionRegistry registry, LanguageRegistry languages,
                              Consumer<Set<Path>> onDirectoriesChanged) {
        this.ide = ide;
        this.registry = registry;
        this.languages = languages;
        this.onDirectoriesChanged = onDirectoriesChanged;
        tree.setRoot(hiddenRoot);
        tree.setShowRoot(false);
        tree.getStyleClass().add("file-tree");
        tree.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        tree.setCellFactory(v -> new PathCell());
        /* Double click opens a file. It does not touch a folder, and that is the fix:
           the tree's own cells already expand a folder on a double click, and this
           handler was toggling it a second time - open and shut inside the one gesture,
           which from the outside is a double click that does nothing at all. Only the
           disclosure arrow worked, and that is the part of the row nobody aims for.
           (Consuming the event does not help: the cells act on the press, not the
           click.) Enter still toggles, because nothing else does it for us there. */
        tree.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2) {
                return;
            }
            TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
            if (item instanceof PathTreeItem p && !p.isDirectory()) {
                ide.editors().open(p.getValue());
            }
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE) {
                ide.actions().invoke("file.delete");
                e.consume();
            } else if (e.getCode() == KeyCode.F2) {
                ide.actions().invoke("file.rename");
                e.consume();
            }
        });
        /* Built when the menu is asked for, and shown by hand.
           It used to be filled in the menu's own onShowing handler, which never ran:
           ContextMenu.show() returns before firing it when the menu has no items, and an
           empty menu is exactly what a menu that fills itself on showing starts as. So
           right-clicking the tree did nothing at all - no rename, no delete, no new file.

           Right-clicking a row selects it first, the way every file tree behaves: the
           actions read the selection, and a menu that acts on some other row is worse
           than no menu. A right click on empty space keeps whatever was selected. */
        tree.setOnContextMenuRequested(e -> {
            selectUnderCursor(e.getPickResult().getIntersectedNode());
            buildContextMenu(contextMenu);
            if (!contextMenu.getItems().isEmpty()) {
                contextMenu.show(tree, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
        // Any click puts it away again; the auto-hide only covers clicks outside the tree.
        tree.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });

        try {
            watcher = new FileWatchService(changed -> {
                for (Path dir : changed) {
                    refresh(dir);
                }
                onDirectoriesChanged.accept(changed);
            });
            watcher.start();
        } catch (IOException e) {
            System.err.println("smIDE: file watching unavailable: " + e);
        }

        ide.workspaces().addOpenedListener(this::addWorkspace);
        ide.workspaces().addClosedListener(this::removeWorkspace);
        for (Workspace w : ide.workspaces().all()) {
            addWorkspace(w);
        }
        ide.events().subscribe(Events.EditorActivated.class, ev -> {
            if (ide.settings().getBoolean("explorer.autoscroll", true)) {
                reveal(ev.editor().path(), false);
            }
        });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Project";
    }

    @Override
    public String iconLiteral() {
        return "fth-folder";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.LEFT;
    }

    @Override
    public String shortcut() {
        return "alt+1";
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Node create(ToolWindowContext context) {
        if (root == null) {
            root = new BorderPane(tree);
            HBox tools = new HBox(2,
                    Icons.button("fth-crosshair", "Select opened file", () -> ide.editors().active()
                            .ifPresent(e -> reveal(e.path(), true))),
                    Icons.button("fth-refresh-cw", "Refresh", this::refreshAll),
                    Icons.button("fth-minus-square", "Collapse all", this::collapseAll));
            tools.setStyle("-fx-padding: 2 4 2 4; -fx-alignment: center-right;");
            root.setTop(tools);
        }
        return root;
    }

    public TreeView<Path> tree() {
        return tree;
    }

    /** The paths selected, for the action context. */
    public List<Path> selectedPaths() {
        List<Path> out = new ArrayList<>();
        for (TreeItem<Path> item : tree.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) {
                out.add(item.getValue());
            }
        }
        return out;
    }

    private void openSelected() {
        TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
        if (item instanceof PathTreeItem p) {
            if (p.isDirectory()) {
                p.setExpanded(!p.isExpanded());
            } else {
                ide.editors().open(p.getValue());
            }
        }
    }

    private void addWorkspace(Workspace workspace) {
        for (TreeItem<Path> item : hiddenRoot.getChildren()) {
            if (workspace.root().equals(item.getValue())) {
                return;
            }
        }
        PathTreeItem item = new PathTreeItem(workspace.root(), dir -> {
            if (watcher != null) {
                watcher.watch(dir);
            }
        });
        hiddenRoot.getChildren().add(item);
        item.setExpanded(true);
    }

    private void removeWorkspace(Workspace workspace) {
        hiddenRoot.getChildren().removeIf(item -> workspace.root().equals(item.getValue()));
        if (watcher != null) {
            watcher.unwatchUnder(workspace.root());
        }
    }

    /** Re-reads one directory if it is loaded in the tree. */
    public void refresh(Path directory) {
        PathTreeItem item = find(directory);
        if (item != null && item.isDirectory() && item.isLoaded()) {
            item.reload();
        }
    }

    public void refreshAll() {
        for (TreeItem<Path> item : hiddenRoot.getChildren()) {
            if (item instanceof PathTreeItem p) {
                p.refreshDeep();
            }
        }
    }

    private void collapseAll() {
        for (TreeItem<Path> item : hiddenRoot.getChildren()) {
            collapse(item);
            item.setExpanded(true);
        }
    }

    private static void collapse(TreeItem<Path> item) {
        for (TreeItem<Path> child : item.getChildren()) {
            if (child.isExpanded()) {
                collapse(child);
                child.setExpanded(false);
            }
        }
        item.setExpanded(false);
    }

    private PathTreeItem find(Path path) {
        for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
            if (rootItem instanceof PathTreeItem p && path.startsWith(p.getValue())) {
                return descend(p, path, false);
            }
        }
        return null;
    }

    private static PathTreeItem descend(PathTreeItem from, Path target, boolean expand) {
        PathTreeItem current = from;
        while (!current.getValue().equals(target)) {
            if (expand) {
                current.setExpanded(true);
            } else if (!current.isLoaded()) {
                return null;
            }
            PathTreeItem next = null;
            for (TreeItem<Path> child : current.getChildren()) {
                if (child instanceof PathTreeItem p && target.startsWith(p.getValue())) {
                    next = p;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    /** Expands to the file and selects it. */
    public boolean reveal(Path file, boolean focus) {
        for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
            if (rootItem instanceof PathTreeItem p && file.startsWith(p.getValue())) {
                PathTreeItem item = descend(p, file, true);
                if (item != null) {
                    tree.getSelectionModel().clearSelection();
                    tree.getSelectionModel().select(item);
                    int row = tree.getRow(item);
                    if (row >= 0) {
                        tree.scrollTo(Math.max(0, row - 5));
                    }
                    if (focus) {
                        tree.requestFocus();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Selects the row a right click landed on, unless it is already part of the selection.
     *
     * <p>Already part of it matters: right-clicking one of five selected files to delete
     * all five should not quietly reduce the selection to one.
     */
    private void selectUnderCursor(javafx.scene.Node picked) {
        javafx.scene.Node node = picked;
        while (node != null && !(node instanceof javafx.scene.control.TreeCell)) {
            node = node.getParent();
        }
        if (!(node instanceof javafx.scene.control.TreeCell<?> cell) || cell.isEmpty()) {
            return;
        }
        TreeItem<?> item = cell.getTreeItem();
        int index = tree.getRow((TreeItem<Path>) item);
        if (index < 0 || tree.getSelectionModel().getSelectedIndices().contains(index)) {
            return;
        }
        tree.getSelectionModel().clearAndSelect(index);
    }

    private void buildContextMenu(ContextMenu menu) {
        menu.getItems().clear();
        ActionContext ctx = ide.actions().currentContext();
        List<Action> actions = new ArrayList<>();
        for (Action a : registry.actions()) {
            if (a.inContextMenu("explorer")) {
                actions.add(a);
            }
        }
        actions.sort((a, b) -> Integer.compare(a.order(), b.order()));
        int lastGroup = -1;
        for (Action a : actions) {
            int group = a.order() / 100;
            if (lastGroup >= 0 && group != lastGroup) {
                menu.getItems().add(new SeparatorMenuItem());
            }
            lastGroup = group;
            MenuItem item = new MenuItem(a.text());
            FontIcon icon = Icons.of(a.iconLiteral());
            if (icon != null) {
                item.setGraphic(icon);
            }
            item.setDisable(!a.isEnabled(ctx));
            item.setOnAction(e -> ide.actions().invoke(a.id()));
            menu.getItems().add(item);
        }
    }

    private final class PathCell extends TreeCell<Path> {
        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            getStyleClass().removeAll("workspace-root", "dimmed");
            if (empty || path == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            boolean isRoot = getTreeItem() != null && getTreeItem().getParent() == hiddenRoot;
            setText(isRoot ? name : name);
            if (isRoot) {
                getStyleClass().add("workspace-root");
            } else if (DIMMED.contains(name)) {
                getStyleClass().add("dimmed");
            }
            boolean dir = getTreeItem() instanceof PathTreeItem p ? p.isDirectory() : Files.isDirectory(path);
            FontIcon icon;
            if (dir) {
                icon = Icons.of(getTreeItem() != null && getTreeItem().isExpanded() ? "fth-folder-minus" : "fth-folder");
                if (icon != null) {
                    icon.getStyleClass().add("file-icon-folder");
                }
            } else {
                icon = Icons.of(languages.iconFor(path));
                if (icon != null && languages.forFile(path).isPresent()) {
                    icon.getStyleClass().add("file-icon-source");
                }
            }
            setGraphic(icon);
        }
    }
}
