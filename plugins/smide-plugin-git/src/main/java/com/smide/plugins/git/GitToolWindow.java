package com.smide.plugins.git;

import com.smide.api.Ide;
import com.smide.api.ui.Splits;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Git tool window: Changes on one tab, Log on the other, a diff underneath each.
 *
 * <p>Everything shown is a snapshot refreshed from {@link GitService}; nothing here
 * touches a repository directly, and every call goes through {@link GitUi} so the
 * JavaFX thread never waits on disk.
 */
public final class GitToolWindow implements ToolWindowFactory {

    public static final String ID = "git";

    private final GitUi ui;
    private final Ide ide;

    // Changes tab.
    private final TreeView<Object> changes = new TreeView<>();
    private final TreeItem<Object> changesRoot = new TreeItem<>();
    private final TextArea message = new TextArea();
    private final CheckBox amend = new CheckBox("Amend");
    private final Label branchLabel = new Label();
    private final DiffView changesDiff;
    private final StackPane changesPane = new StackPane();
    private final Label noRepo = new Label("This workspace is not a Git repository.");
    private final Button initButton = new Button("Initialize Git repository");

    // History tab: one file, every commit that touched it.
    private final ListView<CommitInfo> historyCommits = new ListView<>();
    private final DiffView historyDiff;
    private final Label historyTitle = new Label("Open a file and choose Git > Show File History.");
    private Path historyFile;
    private String historyPath;
    private Tab historyTab;
    private Tab logTab;
    private TabPane tabs;

    // Log tab.
    private final ListView<CommitInfo> commits = new ListView<>();
    private final ListView<FileChange> commitFiles = new ListView<>();
    private final DiffView logDiff;
    private final ComboBox<BranchInfo> branchChooser = new ComboBox<>();

    private BorderPane root;
    private ToolWindowContext context;
    private boolean refreshQueued;

    public GitToolWindow(GitUi ui) {
        this.ui = ui;
        this.ide = ui.ide();
        this.changesDiff = new DiffView(ide.theme());
        this.logDiff = new DiffView(ide.theme());
        this.historyDiff = new DiffView(ide.theme());

        buildChanges();
        buildLog();
        buildHistory();

        ui.git().addListener(this::scheduleRefresh);
        ide.workspaces().addActiveListener(w -> scheduleRefresh());
        ide.events().subscribe(Events.FileSaved.class, e -> scheduleRefresh());
        ide.events().subscribe(Events.FilesChanged.class, e -> scheduleRefresh());
    }

    // ------------------------------------------------------------- Changes

    private void buildChanges() {
        changes.setRoot(changesRoot);
        changes.setShowRoot(false);
        changes.setCellFactory(v -> new ChangeCell());
        changes.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> showDiffFor(item));
        changes.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                selectedChange().ifPresent(this::openFile);
            }
        });

        message.setPromptText("Commit message");
        message.getStyleClass().add("git-commit-message");
        message.setPrefRowCount(3);
        message.setWrapText(true);

        Button commit = new Button("Commit");
        commit.setDefaultButton(false);
        commit.setOnAction(e -> commit(false));
        Button commitPush = new Button("Commit and Push");
        commitPush.setOnAction(e -> commit(true));
        HBox buttons = new HBox(6, amend, new Region(), commit, commitPush);
        HBox.setHgrow(buttons.getChildren().get(1), Priority.ALWAYS);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox commitBox = new VBox(6, message, buttons);
        commitBox.setPadding(new Insets(6));

        // A split rather than a BorderPane bottom: the tool window is short, and a
        // BorderPane hands the commit box its preferred height and leaves the file
        // list with whatever is left - which, at this height, is nothing.
        changes.setMinHeight(50);
        commitBox.setMinHeight(64);
        message.setMinHeight(30);
        SplitPane leftSplit = new SplitPane(changes, commitBox);
        leftSplit.setOrientation(Orientation.VERTICAL);
        leftSplit.setDividerPositions(0.58);
        SplitPane.setResizableWithParent(commitBox, false);
        BorderPane left = new BorderPane(leftSplit);
        left.setTop(changesToolbar());
        SplitPane split = new SplitPane(left, changesDiff);
        split.setDividerPositions(0.42);
        Splits.grabbable(leftSplit);
        Splits.grabbable(split);

        noRepo.getStyleClass().add("empty-hint");
        initButton.setOnAction(e -> ide.workspaces().active().ifPresent(w ->
                ui.write("repository initialised", () -> ui.git().init(w.root()), this::refresh)));
        VBox empty = new VBox(10, noRepo, initButton);
        empty.setAlignment(Pos.CENTER);
        changesPane.getChildren().addAll(empty, split);
    }

    private Node changesToolbar() {
        branchLabel.getStyleClass().add("tool-window-title");
        HBox bar = new HBox(4, branchLabel, new Region(),
                icon("fth-refresh-cw", "Refresh", this::refresh),
                icon("fth-plus", "Stage all", () -> stageAll(true)),
                icon("fth-minus", "Unstage all", () -> stageAll(false)),
                icon("fth-rotate-ccw", "Revert selected", this::revertSelected));
        HBox.setHgrow(bar.getChildren().get(1), Priority.ALWAYS);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 6, 4, 8));
        return bar;
    }

    private static Button icon(String literal, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(new FontIcon(literal));
        b.getStyleClass().add("icon-button");
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private Optional<FileChange> selectedChange() {
        TreeItem<Object> item = changes.getSelectionModel().getSelectedItem();
        return item != null && item.getValue() instanceof FileChange c ? Optional.of(c) : Optional.empty();
    }

    private void openFile(FileChange change) {
        ui.activeRoot().ifPresent(root -> {
            Path file = ui.git().workTree(root).orElse(root).resolve(change.path());
            if (java.nio.file.Files.isRegularFile(file)) {
                ide.editors().open(file);
            }
        });
    }

    private void showDiffFor(TreeItem<Object> item) {
        if (item == null || !(item.getValue() instanceof FileChange change)) {
            return;
        }
        ui.activeRoot().ifPresent(root -> {
            GitService.DiffBase base = change.area() == FileChange.Area.STAGED
                    ? GitService.DiffBase.STAGED : GitService.DiffBase.INDEX;
            ui.read(() -> ui.git().diff(root, change.path(), base), changesDiff::setDiff);
        });
    }

    private void stageAll(boolean stage) {
        ui.activeRoot().ifPresent(root -> ui.read(() -> ui.git().status(root), status -> {
            List<String> paths = new ArrayList<>();
            if (stage) {
                status.unstaged().forEach(c -> paths.add(c.path()));
                status.untracked().forEach(c -> paths.add(c.path()));
            } else {
                status.staged().forEach(c -> paths.add(c.path()));
            }
            if (paths.isEmpty()) {
                ide.statusBar().message(stage ? "Nothing to stage" : "Nothing to unstage");
                return;
            }
            ui.write(stage ? "staged " + paths.size() + " files" : "unstaged " + paths.size() + " files",
                    () -> {
                        if (stage) {
                            ui.git().stage(root, paths);
                        } else {
                            ui.git().unstage(root, paths);
                        }
                    });
        }));
    }

    private void toggleStaged(FileChange change) {
        ui.activeRoot().ifPresent(root -> {
            boolean staged = change.area() == FileChange.Area.STAGED;
            ui.write(staged ? "unstaged " + change.fileName() : "staged " + change.fileName(),
                    () -> {
                        if (staged) {
                            ui.git().unstage(root, List.of(change.path()));
                        } else {
                            ui.git().stage(root, List.of(change.path()));
                        }
                    });
        });
    }

    private void revertSelected() {
        Optional<FileChange> change = selectedChange();
        if (change.isEmpty()) {
            ide.statusBar().message("Select a file to revert");
            return;
        }
        if (!ide.window().confirm("Revert", "Discard local changes to " + change.get().path() + "?")) {
            return;
        }
        ui.activeRoot().ifPresent(root -> ui.write("reverted " + change.get().fileName(),
                () -> ui.git().revert(root, List.of(change.get().path()))));
    }

    private void commit(boolean push) {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            ide.statusBar().message("Enter a commit message first");
            message.requestFocus();
            return;
        }
        ui.activeRoot().ifPresent(root -> ui.write("committed", () -> ui.git().commit(root, text, amend.isSelected()),
                () -> {
                    message.clear();
                    amend.setSelected(false);
                    if (push) {
                        GitCommands.push(ide, root);
                    }
                }));
    }

    /** Focus the commit message, for the Commit action. */
    public void focusCommitMessage() {
        if (context != null) {
            context.show();
        }
        message.requestFocus();
    }

    // ----------------------------------------------------------------- Log

    private void buildLog() {
        commits.setCellFactory(v -> new CommitCell());
        commits.getSelectionModel().selectedItemProperty().addListener((o, a, commit) -> {
            commitFiles.getItems().clear();
            logDiff.setDiff(null);
            if (commit == null) {
                return;
            }
            ui.activeRoot().ifPresent(root ->
                    ui.read(() -> ui.git().changedFiles(root, commit.id()), files -> commitFiles.getItems().setAll(files)));
        });
        commitFiles.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(FileChange c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.kind().letter() + "  " + c.path());
                setGraphic(null);
            }
        });
        commitFiles.getSelectionModel().selectedItemProperty().addListener((o, a, file) -> {
            CommitInfo commit = commits.getSelectionModel().getSelectedItem();
            if (file == null || commit == null) {
                return;
            }
            ui.activeRoot().ifPresent(root ->
                    ui.read(() -> ui.git().diffInCommit(root, commit.id(), file.path()), logDiff::setDiff));
        });

        branchChooser.setPromptText("Branch");
        branchChooser.setOnAction(e -> {
            BranchInfo selected = branchChooser.getValue();
            if (selected == null || selected.current() || selected.remote()) {
                return;
            }
            checkout(selected);
        });
    }

    private void checkout(BranchInfo branch) {
        ui.activeRoot().ifPresent(root -> ui.read(() -> ui.git().hasUncommittedChanges(root), dirty -> {
            if (dirty && !ide.window().confirm("Checkout " + branch.name(),
                    "The working tree has uncommitted changes. Check out anyway?")) {
                refresh();
                return;
            }
            ui.write("checked out " + branch.name(), () -> ui.git().checkout(root, branch.name()));
        }));
    }

    // ------------------------------------------------------------- history

    private void buildHistory() {
        historyCommits.setCellFactory(v -> new CommitCell());
        historyCommits.getSelectionModel().selectedItemProperty().addListener((o, a, commit) -> {
            historyDiff.setDiff(null);
            if (commit == null || historyPath == null) {
                return;
            }
            ui.activeRoot().ifPresent(root ->
                    ui.read(() -> ui.git().diffInCommit(root, commit.id(), historyPath), historyDiff::setDiff));
        });
        // Double-click opens that version beside the working copy, which is the next
        // question after "what did this commit do to the file".
        historyCommits.setOnMouseClicked(e -> {
            CommitInfo commit = historyCommits.getSelectionModel().getSelectedItem();
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && commit != null && historyFile != null) {
                compareWithRevision(commit.id(), commit.shortId());
            }
        });
        historyTitle.getStyleClass().add("tool-window-title");
    }

    /** Shows the history of one file, bringing the window forward. */
    public void showHistory(Path file) {
        ui.activeRoot().ifPresentOrElse(root -> ui.git().relativize(root, file).ifPresentOrElse(path -> {
            historyFile = file;
            historyPath = path;
            historyTitle.setText(path.toUpperCase());
            historyCommits.getItems().clear();
            historyDiff.setDiff(null);
            if (context != null) {
                context.show();
            }
            if (tabs != null && historyTab != null) {
                tabs.getSelectionModel().select(historyTab);
            }
            ui.read(() -> ui.git().fileHistory(root, path, 200), list -> {
                historyCommits.getItems().setAll(list);
                if (list.isEmpty()) {
                    historyTitle.setText(path.toUpperCase() + "   (no commits yet)");
                }
            });
        }, () -> ide.statusBar().message(file.getFileName() + " is not in this repository")),
                () -> ide.statusBar().message("This workspace is not a Git repository"));
    }

    /** Opens the file as it was at a revision beside the working copy. */
    private void compareWithRevision(String revision, String label) {
        ui.activeRoot().ifPresent(root -> ui.read(() -> ui.git().fileAt(root, revision, historyPath), text -> {
            SideBySideDiff diff = new SideBySideDiff(ide.theme());
            String working = "";
            try {
                working = java.nio.file.Files.readString(historyFile);
            } catch (java.io.IOException ignored) {
                // A file that is gone from the working tree compares against nothing.
            }
            diff.setContent(historyPath + "  at  " + label, text, historyPath + "  (working tree)", working);
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.initOwner(ide.window().stage());
            stage.setTitle(historyFile.getFileName() + " - working tree against " + label);
            stage.setScene(new javafx.scene.Scene(diff, 1000, 640));
            ide.theme().style(stage);
            stage.show();
        }));
    }

    /** Selects a commit in the Log tab; used when an annotation is clicked. */
    public void showCommit(String commitId) {
        if (context != null) {
            context.show();
        }
        if (tabs != null && logTab != null) {
            tabs.getSelectionModel().select(logTab);
        }
        for (CommitInfo commit : commits.getItems()) {
            if (commit.id().equals(commitId)) {
                commits.getSelectionModel().select(commit);
                commits.scrollTo(commit);
                return;
            }
        }
        ide.statusBar().message("Commit " + commitId.substring(0, Math.min(7, commitId.length()))
                + " is older than the log shown here");
    }

    // ------------------------------------------------------------- refresh

    private void scheduleRefresh() {
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        ide.window().runLater(() -> {
            refreshQueued = false;
            refresh();
        });
    }

    /** Re-reads status, log and branches for the active workspace. */
    public void refresh() {
        Optional<Workspace> workspace = ide.workspaces().active();
        Optional<Path> root = ui.activeRoot();
        boolean repo = root.isPresent();
        changesPane.getChildren().get(0).setVisible(!repo);
        changesPane.getChildren().get(1).setVisible(repo);
        initButton.setDisable(workspace.isEmpty());
        noRepo.setText(workspace.isEmpty() ? "No workspace is open."
                : workspace.get().name() + " is not a Git repository.");
        if (root.isEmpty()) {
            changesRoot.getChildren().clear();
            commits.getItems().clear();
            commitFiles.getItems().clear();
            branchChooser.getItems().clear();
            branchLabel.setText("");
            if (context != null) {
                context.setTitle("Git");
            }
            return;
        }
        Path repoRoot = root.get();
        ui.read(() -> ui.git().status(repoRoot), status -> {
            branchLabel.setText(status.branch().toUpperCase());
            fillChanges(status);
            if (context != null) {
                context.setTitle(status.total() == 0 ? "Git" : "Git  " + status.total() + " changed");
            }
        });
        ui.read(() -> ui.git().log(repoRoot, 100), list -> commits.getItems().setAll(list));
        ui.read(() -> ui.git().branches(repoRoot), list -> {
            branchChooser.getItems().setAll(list);
            list.stream().filter(BranchInfo::current).findFirst()
                    .ifPresent(b -> branchChooser.getSelectionModel().select(b));
        });
    }

    private void fillChanges(RepoStatus status) {
        changesRoot.getChildren().clear();
        addArea(FileChange.Area.STAGED, status.staged());
        addArea(FileChange.Area.CONFLICT, status.conflicting());
        addArea(FileChange.Area.UNSTAGED, status.unstaged());
        addArea(FileChange.Area.UNTRACKED, status.untracked());
    }

    private void addArea(FileChange.Area area, List<FileChange> files) {
        if (files.isEmpty()) {
            return;
        }
        TreeItem<Object> group = new TreeItem<>(area.title() + "  (" + files.size() + ")");
        group.setExpanded(true);
        for (FileChange f : files) {
            group.getChildren().add(new TreeItem<>(f));
        }
        changesRoot.getChildren().add(group);
    }

    // -------------------------------------------------------- ToolWindowFactory

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Git";
    }

    @Override
    public String iconLiteral() {
        return "fth-git-branch";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.BOTTOM;
    }

    @Override
    public String shortcut() {
        return "alt+9";
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public Node create(ToolWindowContext context) {
        this.context = context;
        if (root == null) {
            Tab changesTab = new Tab("Changes", changesPane);
            // Same reason as the Changes tab: the diff has to keep a share of the
            // height instead of being squeezed out by the file list above it.
            commitFiles.setMinHeight(50);
            logDiff.setMinHeight(50);
            SplitPane filesAndDiff = new SplitPane(commitFiles, logDiff);
            filesAndDiff.setOrientation(Orientation.VERTICAL);
            filesAndDiff.setDividerPositions(0.45);
            SplitPane logSplit = new SplitPane(commits, filesAndDiff);
            logSplit.setDividerPositions(0.4);
            Splits.grabbable(filesAndDiff);
            Splits.grabbable(logSplit);
            BorderPane logPane = new BorderPane(logSplit);
            HBox logBar = new HBox(6, new Label("Branch"), branchChooser, new Region(),
                    icon("fth-refresh-cw", "Refresh", this::refresh));
            HBox.setHgrow(logBar.getChildren().get(2), Priority.ALWAYS);
            logBar.setAlignment(Pos.CENTER_LEFT);
            logBar.setPadding(new Insets(4, 6, 4, 8));
            logPane.setTop(logBar);
            logTab = new Tab("Log", logPane);

            BorderPane historyPane = new BorderPane(new SplitPane(historyCommits, historyDiff) {
                {
                    setDividerPositions(0.45);
                    Splits.grabbable(this);
                }
            });
            HBox historyBar = new HBox(6, historyTitle, new Region(),
                    icon("fth-refresh-cw", "Refresh", () -> {
                        if (historyFile != null) {
                            showHistory(historyFile);
                        }
                    }));
            HBox.setHgrow(historyBar.getChildren().get(1), Priority.ALWAYS);
            historyBar.setAlignment(Pos.CENTER_LEFT);
            historyBar.setPadding(new Insets(4, 6, 4, 8));
            historyPane.setTop(historyBar);
            historyTab = new Tab("History", historyPane);

            tabs = new TabPane(changesTab, logTab, historyTab);
            tabs.getStyleClass().add("document-tabs");
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            root = new BorderPane(tabs);
        }
        refresh();
        return root;
    }

    // ----------------------------------------------------------------- cells

    private final class ChangeCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("git-area-header");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            if (item instanceof String header) {
                setText(header);
                setGraphic(null);
                getStyleClass().add("git-area-header");
                return;
            }
            FileChange change = (FileChange) item;
            CheckBox box = new CheckBox();
            box.setSelected(change.area() == FileChange.Area.STAGED);
            box.setOnAction(e -> toggleStaged(change));
            Label letter = new Label(String.valueOf(change.kind().letter()));
            letter.getStyleClass().add("git-status-letter");
            Label name = new Label(change.fileName());
            Label dir = new Label(change.path().contains("/")
                    ? change.path().substring(0, change.path().lastIndexOf('/')) : "");
            dir.getStyleClass().add("muted-small");
            HBox row = new HBox(6, box, letter, name, dir);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }

    private static final class CommitCell extends ListCell<CommitInfo> {
        @Override
        protected void updateItem(CommitInfo commit, boolean empty) {
            super.updateItem(commit, empty);
            if (empty || commit == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label id = new Label(commit.shortId());
            id.getStyleClass().add("git-status-letter");
            Label subject = new Label(commit.shortMessage());
            Label who = new Label(commit.author() + "  " + ago(commit.date()));
            who.getStyleClass().add("muted-small");
            HBox row = new HBox(8, id, subject, new Region(), who);
            HBox.setHgrow(row.getChildren().get(2), Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }

        private static String ago(Instant when) {
            if (when == null) {
                return "";
            }
            Duration d = Duration.between(when, Instant.now());
            if (d.toMinutes() < 60) {
                return Math.max(1, d.toMinutes()) + " min ago";
            }
            if (d.toHours() < 24) {
                return d.toHours() + " h ago";
            }
            if (d.toDays() < 31) {
                return d.toDays() + " d ago";
            }
            return when.toString().substring(0, 10);
        }
    }
}
