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
import java.util.Map;
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
    private final JarEntries jars;
    /** Asked to show a file that is in no open project - a dependency's - somewhere else: the Libraries window. */
    private java.util.function.BiPredicate<Path, Boolean> outside = (file, focus) -> false;
    private FileWatchService watcher;
    private BorderPane root;

    public ExplorerToolWindow(Ide ide, ExtensionRegistry registry, LanguageRegistry languages,
                              Consumer<Set<Path>> onDirectoriesChanged) {
        this.ide = ide;
        this.registry = registry;
        this.languages = languages;
        this.onDirectoriesChanged = onDirectoriesChanged;
        this.jars = new JarEntries(ide);
        tree.setRoot(hiddenRoot);
        tree.setShowRoot(false);
        tree.getStyleClass().add("file-tree");
        tree.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        tree.setCellFactory(v -> new PathCell());
        // A file with errors is red in the tree, and so is every folder above it.
        ide.problems().addListener(file -> javafx.application.Platform.runLater(this::refreshErrors));
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
                open(p.getValue());
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
            showContextMenu(e.getPickResult().getIntersectedNode(), e.getScreenX(), e.getScreenY());
            e.consume();
        });
        // Press and hold, for the machines where the tree is touched rather than clicked.
        // Nothing turns a long press into a context-menu request on its own.
        com.smide.ui.LongPress.install(tree, this::showContextMenu);
        /* Any click puts it away again; the auto-hide only covers clicks outside the tree.
           Not a synthesized one, though: a touch that has just held long enough to open
           the menu ends with a synthetic press, and closing the menu with the gesture
           that opened it is the same as never opening it. */
        tree.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing() && !e.isSynthesized()) {
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
            // Not what is inside a jar: it is read, never renamed or deleted.
            if (item != null && item.getValue() != null
                    && item.getValue().getFileSystem() == java.nio.file.FileSystems.getDefault()) {
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
                open(p.getValue());
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
            // Only folders on disk: a jar opened in the tree cannot be watched.
            if (watcher != null && dir.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
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

    /** Where a file in no open project is shown instead; answers whether it was. */
    public void setOutside(java.util.function.BiPredicate<Path, Boolean> outside) {
        this.outside = outside;
    }

    /** Opens a file of the tree - one inside a jar by taking it out first. */
    private void open(Path path) {
        jars.open(path);
    }

    private void select(TreeItem<Path> item, boolean focus) {
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(item);
        int row = tree.getRow(item);
        if (row >= 0) {
            tree.scrollTo(Math.max(0, row - 5));
        }
        if (focus) {
            tree.requestFocus();
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

    /**
     * Expands to the file and selects it: in its project, or - for a dependency's pom, a file
     * from a jar - wherever {@link #setOutside} shows such files.
     */
    public boolean reveal(Path file, boolean focus) {
        for (TreeItem<Path> rootItem : hiddenRoot.getChildren()) {
            if (rootItem instanceof PathTreeItem p && file.startsWith(p.getValue())) {
                PathTreeItem item = descend(p, file, true);
                if (item != null) {
                    select(item, focus);
                    return true;
                }
            }
        }
        return outside.test(file, focus);
    }

    /** Opens the menu where the gesture happened, on the row it happened on. */
    private void showContextMenu(javafx.scene.Node picked, double screenX, double screenY) {
        selectUnderCursor(picked);
        buildContextMenu(contextMenu);
        if (!contextMenu.getItems().isEmpty()) {
            contextMenu.show(tree, screenX, screenY);
        }
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

    /** Files with errors, and how many each has: read by the cells, rebuilt when problems change. */
    private Map<Path, Integer> errorFiles = Map.of();

    /**
     * Recounts the errors per file and repaints the tree.
     *
     * <p>From every source - language servers, the pom check, builds - because a file is
     * broken whoever noticed. Errors only: a warning is not a reason to paint a file red.
     */
    private void refreshErrors() {
        Map<Path, Integer> counted = new java.util.HashMap<>();
        for (com.smide.api.problems.Diagnostic d : ide.problems().all()) {
            if (d.severity() == com.smide.api.problems.Diagnostic.Severity.ERROR && d.file() != null) {
                counted.merge(d.file().toAbsolutePath().normalize(), 1, Integer::sum);
            }
        }
        if (!counted.equals(errorFiles)) {
            errorFiles = Map.copyOf(counted);
            tree.refresh();
        }
    }

    /** How many errors are in this file, or - for a folder - in everything under it. */
    private int errorsIn(Path path, boolean dir) {
        Path at = path.toAbsolutePath().normalize();
        if (!dir) {
            return errorFiles.getOrDefault(at, 0);
        }
        int total = 0;
        for (Map.Entry<Path, Integer> e : errorFiles.entrySet()) {
            if (e.getKey().startsWith(at)) {
                total += e.getValue();
            }
        }
        return total;
    }

    private final class PathCell extends TreeCell<Path> {
        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            getStyleClass().removeAll("workspace-root", "dimmed", "has-errors");
            setTooltip(null);
            if (empty || path == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String name = PathTreeItem.name(path);
            boolean isRoot = getTreeItem() != null && getTreeItem().getParent() == hiddenRoot;
            String label = getTreeItem() instanceof PathTreeItem labelled ? labelled.label() : null;
            setText(label != null ? label : name);
            if (label != null) {
                // A library: its name, and where it is kept when pointed at.
                setTooltip(new javafx.scene.control.Tooltip(path.toString()));
                FontIcon library = Icons.of("fth-package");
                if (library != null) {
                    library.getStyleClass().add("file-icon-folder");
                }
                setGraphic(library);
                return;
            }
            if (isRoot) {
                getStyleClass().add("workspace-root");
            } else if (DIMMED.contains(name)) {
                getStyleClass().add("dimmed");
            }
            boolean dir = getTreeItem() instanceof PathTreeItem p ? p.isDirectory() : Files.isDirectory(path);
            int errors = errorsIn(path, dir);
            if (errors > 0) {
                getStyleClass().add("has-errors");
                setTooltip(new javafx.scene.control.Tooltip(errors + (errors == 1 ? " error" : " errors")
                        + (dir ? " in files under " + name : " in " + name)));
            }
            FontIcon icon;
            if (PathTreeItem.isArchive(path)) {
                icon = Icons.of("fth-archive");
            } else if (dir) {
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
