package com.smide.explorer;

import com.smide.api.Ide;
import com.smide.api.project.LibraryProvider;
import com.smide.api.project.LibraryProvider.Library;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.core.ExtensionRegistry;
import com.smide.lang.LanguageRegistry;
import com.smide.ui.Icons;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The libraries the active project builds against - IntelliJ's External Libraries, in a
 * window of its own beside the Project tree: each library by name, its folder in the
 * repository under it with the jar and the pom, and a jar opened like a folder.
 *
 * <p>Follows the active project: switching projects lists the other one's. A dependency's
 * file that is open - a pom followed with Ctrl+click, a file from inside a jar - is found
 * here by Select Opened File, in this window or in the Project one.
 */
public final class LibrariesToolWindow implements ToolWindowFactory {

    public static final String ID = "libraries";

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final LanguageRegistry languages;
    private final JarEntries jars;
    private final TreeView<Path> tree = new TreeView<>();
    private final TreeItem<Path> root = new TreeItem<>();
    private final Label heading = new Label();
    private final List<Runnable> afterLoading = new ArrayList<>();
    /** The project the list is for; null before one is open. */
    private Workspace listed;
    private boolean loaded;
    private boolean loading;
    private int generation;
    private BorderPane view;
    private boolean visible;

    public LibrariesToolWindow(Ide ide, ExtensionRegistry registry, LanguageRegistry languages) {
        this.ide = ide;
        this.registry = registry;
        this.languages = languages;
        this.jars = new JarEntries(ide);
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.getStyleClass().addAll("file-tree", "libraries-tree");
        tree.setCellFactory(v -> new LibraryCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && tree.getSelectionModel().getSelectedItem() instanceof PathTreeItem p && !p.isDirectory()) {
                jars.open(p.getValue());
            }
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && tree.getSelectionModel().getSelectedItem() instanceof PathTreeItem p) {
                if (p.isDirectory()) {
                    p.setExpanded(!p.isExpanded());
                } else {
                    jars.open(p.getValue());
                }
                e.consume();
            }
        });
        heading.getStyleClass().add("libraries-heading");
        heading.setMinWidth(0);

        ide.workspaces().addActiveListener(active -> Platform.runLater(this::projectChanged));
        ide.workspaces().addClosedListener(w -> Platform.runLater(this::projectChanged));
        ide.events().subscribe(Events.EditorActivated.class, ev -> {
            if (visible && ide.settings().getBoolean("explorer.autoscroll", true)) {
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
        return "Libraries";
    }

    @Override
    public String iconLiteral() {
        return "fth-package";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.LEFT;
    }

    /** Its button sits above Find, where the tools for looking things up are. */
    @Override
    public boolean lowerStripe() {
        return true;
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public Node create(ToolWindowContext context) {
        if (view == null) {
            view = new BorderPane(tree);
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox tools = new HBox(2, heading, gap,
                    Icons.button("fth-crosshair", "Select opened file", () -> ide.editors().active()
                            .ifPresent(e -> reveal(e.path(), true))),
                    Icons.button("fth-refresh-cw", "Refresh", () -> load(null)),
                    Icons.button("fth-minus-square", "Collapse all", this::collapseAll));
            tools.setStyle("-fx-padding: 2 4 2 8; -fx-alignment: center-left;");
            view.setTop(tools);
            // Read the first time it is looked at, not at startup: listing means reading poms.
            view.sceneProperty().addListener((obs, was, now) -> {
                visible = now != null;
                if (visible && !loaded) {
                    load(null);
                }
            });
            visible = true;
            if (!loaded) {
                load(null);
            }
        }
        return view;
    }

    /** The active project changed: its libraries replace the last one's, if the list was ever read. */
    private void projectChanged() {
        Workspace active = ide.workspaces().active().orElse(null);
        if (active == listed) {
            return;
        }
        if (loaded || loading) {
            load(null);
        } else {
            heading.setText(active == null ? "" : active.name());
        }
    }

    /** Asks every plugin for the active project's libraries, off the UI thread, then runs {@code then}. */
    private void load(Runnable then) {
        if (then != null) {
            afterLoading.add(then);
        }
        Workspace active = ide.workspaces().active().orElse(null);
        List<LibraryProvider> providers = List.copyOf(registry.libraryProviders());
        int mine = ++generation;
        loading = true;
        heading.setText(active == null ? "No project open" : active.name() + " - reading...");
        ide.window().runInBackground(() -> {
            Map<Path, Library> found = new LinkedHashMap<>();
            if (active != null) {
                for (LibraryProvider provider : providers) {
                    try {
                        for (Library l : provider.libraries(active)) {
                            found.putIfAbsent(l.root().toAbsolutePath().normalize(), l);
                        }
                    } catch (RuntimeException e) {
                        System.err.println("smIDE: listing libraries failed: " + e);
                    }
                }
            }
            Platform.runLater(() -> {
                if (mine != generation) {
                    return;  // A newer listing - another project - is on its way.
                }
                listed = active;
                show(found.values());
                loaded = true;
                loading = false;
                heading.setText(active == null ? "No project open"
                        : active.name() + "  ·  " + found.size() + (found.size() == 1 ? " library" : " libraries"));
                heading.setTooltip(new Tooltip("Libraries of " + (active == null ? "no project" : active.name())
                        + ", the ones they bring with them included"));
                List<Runnable> waiting = List.copyOf(afterLoading);
                afterLoading.clear();
                waiting.forEach(Runnable::run);
            });
        });
    }

    /** Lists the libraries by name, keeping rows already there - and whatever is opened out under them. */
    private void show(java.util.Collection<Library> found) {
        Map<Path, TreeItem<Path>> existing = new HashMap<>();
        for (TreeItem<Path> item : root.getChildren()) {
            existing.put(item.getValue(), item);
        }
        List<TreeItem<Path>> items = new ArrayList<>();
        for (Library l : found) {
            Path at = l.root().toAbsolutePath().normalize();
            TreeItem<Path> keep = existing.get(at);
            items.add(keep != null ? keep : new PathTreeItem(at, null, l.name()));
        }
        items.sort(BY_LABEL);
        root.getChildren().setAll(items);
    }

    private static final Comparator<TreeItem<Path>> BY_LABEL = Comparator.comparing(
            i -> i instanceof PathTreeItem p && p.label() != null ? p.label() : String.valueOf(i.getValue()));

    private void collapseAll() {
        for (TreeItem<Path> item : root.getChildren()) {
            collapse(item);
        }
    }

    private static void collapse(TreeItem<Path> item) {
        for (TreeItem<Path> child : item.getChildren()) {
            if (child.isExpanded()) {
                collapse(child);
            }
        }
        item.setExpanded(false);
    }

    /**
     * Finds a dependency's file - in its library's folder, or inside its jar - and selects it.
     * A file of the project's that no library lists, a parent pom say, is added for as long as
     * it is looked at. Answers whether the file is a library's at all; the selecting may come
     * a moment later, once the libraries have been read.
     */
    public boolean reveal(Path file, boolean focus) {
        Path target = JarEntries.origin(file);
        Path jar = PathTreeItem.archiveOf(target);
        Path onDisk = (jar != null ? jar : target).toAbsolutePath().normalize();
        if (!loaded || listed != ide.workspaces().active().orElse(null)) {
            if (!belongs(onDisk)) {
                return false;
            }
            load(() -> select(target, jar, onDisk, focus));
            return true;
        }
        return select(target, jar, onDisk, focus);
    }

    /** Whether a file could be a library's at all, before the libraries are read. */
    private boolean belongs(Path onDisk) {
        for (LibraryProvider provider : registry.libraryProviders()) {
            if (provider.libraryOf(onDisk).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean select(Path target, Path jar, Path onDisk, boolean focus) {
        PathTreeItem library = null;
        for (TreeItem<Path> item : root.getChildren()) {
            if (item instanceof PathTreeItem p && onDisk.startsWith(p.getValue())) {
                library = p;
                break;
            }
        }
        if (library == null) {
            for (LibraryProvider provider : registry.libraryProviders()) {
                Optional<Library> of = provider.libraryOf(onDisk);
                if (of.isPresent()) {
                    library = new PathTreeItem(of.get().root().toAbsolutePath().normalize(), null, of.get().name());
                    List<TreeItem<Path>> items = new ArrayList<>(root.getChildren());
                    items.add(library);
                    items.sort(BY_LABEL);
                    root.getChildren().setAll(items);
                    break;
                }
            }
        }
        if (library == null) {
            return false;
        }
        PathTreeItem item = library.getValue().equals(onDisk) ? library : descend(library, onDisk);
        if (item != null && jar != null) {
            item = descendInto(item, target);
        }
        if (item == null) {
            return false;
        }
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

    /** From a library's folder down to a file on disk under it. */
    private static PathTreeItem descend(PathTreeItem from, Path target) {
        PathTreeItem current = from;
        while (!current.getValue().equals(target)) {
            current.setExpanded(true);
            PathTreeItem next = null;
            for (TreeItem<Path> child : current.getChildren()) {
                if (child instanceof PathTreeItem p && p.getValue().getFileSystem() == target.getFileSystem()
                        && target.startsWith(p.getValue())) {
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

    /** From a jar's row down to a path inside it. */
    private static PathTreeItem descendInto(PathTreeItem jarItem, Path target) {
        PathTreeItem current = jarItem;
        while (true) {
            current.setExpanded(true);
            PathTreeItem next = null;
            for (TreeItem<Path> child : current.getChildren()) {
                if (child instanceof PathTreeItem p && p.getValue().getFileSystem() == target.getFileSystem()
                        && target.startsWith(p.getValue())) {
                    next = p;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            if (next.getValue().equals(target)) {
                return next;
            }
            current = next;
        }
    }

    public TreeView<Path> tree() {
        return tree;
    }

    private final class LibraryCell extends TreeCell<Path> {
        @Override
        protected void updateItem(Path path, boolean empty) {
            super.updateItem(path, empty);
            setTooltip(null);
            if (empty || path == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String label = getTreeItem() instanceof PathTreeItem p ? p.label() : null;
            FontIcon icon;
            if (label != null) {
                // A library: its name, and where it is kept when pointed at.
                setText(label);
                setTooltip(new Tooltip(path.toString()));
                icon = Icons.of("fth-package");
            } else {
                setText(PathTreeItem.name(path));
                boolean dir = getTreeItem() instanceof PathTreeItem p && p.isDirectory();
                if (PathTreeItem.isArchive(path)) {
                    icon = Icons.of("fth-archive");
                } else if (dir) {
                    icon = Icons.of(getTreeItem().isExpanded() ? "fth-folder-minus" : "fth-folder");
                } else {
                    icon = Icons.of(languages.iconFor(path));
                }
            }
            if (icon != null && (label != null || getTreeItem() instanceof PathTreeItem p && p.isDirectory())) {
                icon.getStyleClass().add("file-icon-folder");
            }
            setGraphic(icon);
        }
    }
}
