package com.smide.core;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerLauncher.ProgressReporter;
import com.smide.api.lang.Toolchain;
import com.smide.api.lang.Toolchain.Download;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Downloads a missing toolchain, the way IntelliJ offers "Download Python...": asks first,
 * naming the version, the size and who it comes from, then downloads with a Cancel button,
 * checks the archive against its publisher's checksum, unpacks it under
 * {@code ~/.smide/tools/runtimes/<id>/<version>} and points the toolchain's setting at it.
 *
 * <p>Downloaded once, used from then on: the setting is saved at once, and what is on disk
 * is taken up again whenever the setting is missing or points nowhere - so a restart, a
 * reset settings file or a second Download click never fetches it again.
 *
 * <p>Nothing is installed on the machine itself - no installer runs, no PATH changes - so
 * removing the folder undoes it. Where a toolchain offers no portable archive, its download
 * page opens in the browser instead.
 */
public final class ToolchainInstaller {

    private final Ide ide;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Consumer<Toolchain> installed;

    /** @param installed told, on the UI thread, once a toolchain has been installed and set */
    public ToolchainInstaller(Ide ide, Consumer<Toolchain> installed) {
        this.ide = ide;
        this.installed = installed;
    }

    /** Whether this toolchain can be downloaded by the IDE, or only from its page in a browser. */
    public static boolean canInstall(Toolchain toolchain) {
        return toolchain.homeSetting() != null;
    }

    /** Finds the newest version, asks, and installs it. Call on the UI thread. */
    public void offer(Toolchain toolchain) {
        if (!running.add(toolchain.id())) {
            return;
        }
        ide.window().runInBackground(() -> {
            // One downloaded before is used as it is: no asking, no network.
            Optional<Path> earlier = downloaded(toolchain);
            if (earlier.isPresent()) {
                running.remove(toolchain.id());
                ide.window().runLater(() -> {
                    ide.settings().set(toolchain.homeSetting(), earlier.get().toString());
                    ide.notifications().info(toolchain.displayName(), "Using the one downloaded earlier, in "
                            + earlier.get() + ".");
                    installed.accept(toolchain);
                });
                return;
            }
            Optional<Download> download;
            try {
                download = toolchain.homeSetting() == null ? Optional.empty() : toolchain.latestDownload(ide);
            } catch (IOException | RuntimeException e) {
                running.remove(toolchain.id());
                ide.window().runLater(() -> couldNotFind(toolchain, e));
                return;
            }
            ide.window().runLater(() -> {
                if (download.isEmpty()) {
                    running.remove(toolchain.id());
                    browse(toolchain);
                    return;
                }
                if (!confirm(toolchain, download.get())) {
                    running.remove(toolchain.id());
                    return;
                }
                install(toolchain, download.get());
            });
        });
    }

    /** Lets someone point at an installation the search missed, and remembers it. */
    public void chooseHome(Toolchain toolchain) {
        if (toolchain.homeSetting() == null) {
            return;
        }
        ide.window().chooseDirectory(toolchain.displayName() + " location", Path.of(System.getProperty("user.home", ".")))
                .ifPresent(home -> {
                    Optional<Path> found;
                    try {
                        found = Files.isDirectory(home) ? home(toolchain, home) : Optional.empty();
                    } catch (IOException e) {
                        found = Optional.empty();
                    }
                    if (found.isEmpty()) {
                        ide.notifications().warn(toolchain.displayName(),
                                home + " does not look like a " + toolchain.displayName() + " installation.");
                        return;
                    }
                    ide.settings().set(toolchain.homeSetting(), found.get().toString());
                    ide.notifications().info(toolchain.displayName(), "Using " + found.get() + ".");
                    installed.accept(toolchain);
                });
    }

    private void couldNotFind(Toolchain toolchain, Exception e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (toolchain.downloadUrl() == null) {
            ide.notifications().error(toolchain.displayName(), "Could not find a version to download: " + reason);
            return;
        }
        ide.notifications().warn(toolchain.displayName(), "Could not find a version to download (" + reason
                + "). It can be installed from its own page instead.",
                new com.smide.api.ui.Notifications.NotificationAction("Open download page", () -> browse(toolchain)));
    }

    private void browse(Toolchain toolchain) {
        if (toolchain.downloadUrl() != null) {
            ide.window().browse(toolchain.downloadUrl());
        }
    }

    /** Where downloaded runtimes live, apart from the servers and packages beside them in tools. */
    Path runtimes(Toolchain toolchain) {
        return runtimesDir(ide.downloads().toolsDir(), toolchain);
    }

    static Path runtimesDir(Path toolsDir, Toolchain toolchain) {
        return toolsDir.resolve("runtimes").resolve(toolchain.id());
    }

    /** The folder a version is unpacked into. */
    Path target(Toolchain toolchain, Download download) {
        return runtimes(toolchain).resolve(folderName(download.version()));
    }

    /** The newest complete installation downloaded before, if any. Looks at the disk only. */
    Optional<Path> downloaded(Toolchain toolchain) {
        return newestIn(runtimes(toolchain), toolchain);
    }

    static Optional<Path> newestIn(Path runtimes, Toolchain toolchain) {
        if (!Files.isDirectory(runtimes)) {
            return Optional.empty();
        }
        List<Path> versions;
        try (Stream<Path> list = Files.list(runtimes)) {
            // A half-written download is named *.download.*; only unpacked versions count.
            versions = list.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().contains(".download"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), ToolchainInstaller::compareVersions)
                            .reversed())
                    .toList();
        } catch (IOException e) {
            return Optional.empty();
        }
        for (Path version : versions) {
            try {
                Optional<Path> home = home(toolchain, version);
                if (home.isPresent()) {
                    return home;
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable folder is not an installation; try the next.
            }
        }
        return Optional.empty();
    }

    /** 1.10.2 after 1.9.7: numbers compared as numbers, part by part. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return a.compareTo(b);
    }

    /**
     * Takes up a runtime downloaded earlier when the setting that should point at it is empty
     * or points at nothing, and nothing else is found on the machine. A home someone chose
     * that still works is never touched. Off the UI thread: finding means looking at disks.
     *
     * @return whether the setting was changed
     */
    public boolean adopt(Toolchain toolchain) {
        String key = toolchain.homeSetting();
        if (key == null) {
            return false;
        }
        String current = ide.settings().get(key, "");
        try {
            if (!current.isBlank() && Files.isDirectory(Path.of(current)) && toolchain.accepts(Path.of(current))) {
                return false;
            }
        } catch (RuntimeException e) {
            // An unreadable setting is as good as none.
        }
        Optional<Path> earlier = downloaded(toolchain);
        if (earlier.isEmpty()) {
            return false;
        }
        if (current.isBlank() && toolchain.locate(ide).isPresent()) {
            return false;
        }
        ide.settings().set(key, earlier.get().toString());
        return true;
    }

    /** {@link #adopt} for every toolchain, once the plugins are in. Call off the UI thread. */
    public void adoptAll(List<Toolchain> toolchains) {
        for (Toolchain t : toolchains) {
            try {
                adopt(t);
            } catch (RuntimeException e) {
                System.err.println("smIDE: could not look for a downloaded " + t.id() + ": " + e);
            }
        }
    }

    static String folderName(String version) {
        String v = version.startsWith("v") ? version.substring(1) : version;
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean confirm(Toolchain toolchain, Download download) {
        String name = shortName(toolchain);
        ButtonType yes = new ButtonType("Download", ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", yes, no);
        alert.setTitle("Download " + name);
        alert.setHeaderText("Download " + name + " " + plainVersion(download.version()) + "?");
        /* In a scroll pane of a size the dialog can measure exactly. A wrapped label is not:
           a dialog asks it how tall it is before it has been laid out and wrapped, the answer
           comes back short, and the buttons end up below the bottom edge of the window - where
           they cannot be pressed. Text longer than the view scrolls instead. */
        Label text = new Label("From " + download.source()
                + (download.size() > 0 ? ", " + size(download.size()) : "") + ".\n\n"
                + "It is unpacked into\n" + shortPath(target(toolchain, download)) + "\nand used from there. "
                + "Nothing else on this machine changes; deleting that folder removes it.\n\n"
                + (download.sha256() != null
                        ? "The download is checked against the checksum its publisher gives."
                        : "Its publisher gives no checksum to check the download against."));
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(text);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(520);
        scroll.setPrefViewportHeight(210);
        scroll.getStyleClass().add("dialog-text");
        alert.setGraphic(null);
        alert.getDialogPane().setContent(scroll);
        // And, whatever the text does to the layout, with its buttons inside the window.
        com.smide.ui.Dialogs.fitButtonsIn(alert, text);
        alert.getDialogPane().setMinWidth(560);
        alert.getDialogPane().getStyleClass().add("toolchain-download");
        com.smide.api.ui.Windows.belongsTo(alert, owner().orElse(null));
        style(alert.getDialogPane().getScene().getWindow());
        return alert.showAndWait().orElse(no) == yes;
    }

    private void install(Toolchain toolchain, Download download) {
        String name = shortName(toolchain) + " " + plainVersion(download.version());
        Progress progress = new Progress("Downloading " + name);
        progress.show();

        ide.window().runInBackground(() -> {
            Path target = target(toolchain, download);
            Path archive = target.resolveSibling(target.getFileName() + ".download" + extension(download.url()));
            try {
                ide.downloads().download(download.url(), archive, progress.reporter("Downloading " + name));
                if (download.sha256() != null) {
                    progress.reporter("Checking").progress("Checking the download against its checksum", -1);
                    String actual = sha256(archive);
                    if (!actual.equalsIgnoreCase(download.sha256())) {
                        throw new IOException("The download does not match its checksum, so it was not used "
                                + "(expected " + download.sha256() + ", got " + actual + "). Try again.");
                    }
                }
                deleteTree(target);
                ide.downloads().extract(archive, target, progress.reporter("Unpacking " + name));
                makeRunnable(target);
                Path home = home(toolchain, target)
                        .orElseThrow(() -> new IOException("The archive unpacked into " + target + ", but no "
                                + toolchain.displayName() + " was found in it."));
                ide.window().runLater(() -> {
                    progress.close();
                    ide.settings().set(toolchain.homeSetting(), home.toString());
                    ide.notifications().info(toolchain.displayName(), name + " is installed in " + home + " and in use.");
                    installed.accept(toolchain);
                });
            } catch (InterruptedIOException e) {
                deleteQuietly(target);
                ide.window().runLater(progress::close);
            } catch (IOException | RuntimeException e) {
                deleteQuietly(target);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ide.window().runLater(() -> {
                    progress.close();
                    ide.notifications().error("Could not install " + name, reason);
                });
            } finally {
                deleteQuietly(archive);
                running.remove(toolchain.id());
            }
        });
    }

    /** The step, and how far along it is - "Downloading Python 3.14.7 (3.2 MB of 21.7 MB)" - not the file it writes. */
    static String describe(String step, String text) {
        if (text == null || text.isBlank()) {
            return step;
        }
        if (text.startsWith("Extracting ")) {
            String entry = text.substring("Extracting ".length()).replace('\\', '/');
            entry = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
            return step + ": " + entry.substring(entry.lastIndexOf('/') + 1);
        }
        int open = text.lastIndexOf('(');
        if (open >= 0 && text.endsWith(")")) {
            return step + " " + text.substring(open);
        }
        return text.startsWith(step) ? text : step + ": " + text;
    }

    /**
     * The programs of an unpacked toolchain, runnable.
     *
     * <p>Whatever the archive said: a zip written on Windows, or by a tool that left the
     * permissions out, holds no execute bit at all, and what is unpacked from it is a file
     * nobody can run - "Permission denied" from a launcher that is right there.
     */
    public static void makeRunnable(Path unpacked) {
        if (!Files.isDirectory(unpacked) || !unpacked.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try (Stream<Path> walk = Files.walk(unpacked, 3)) {
            for (Path bin : walk.filter(Files::isDirectory).filter(p -> p.getFileName().toString().equals("bin")).toList()) {
                try (Stream<Path> programs = Files.list(bin)) {
                    programs.filter(Files::isRegularFile).forEach(ToolchainInstaller::allowRunning);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: could not look through " + unpacked + ": " + e);
        }
    }

    /** Adds the execute bit to a file of the IDE's own tools folder. */
    public static void allowRunning(Path program) {
        try {
            if (Files.isExecutable(program)) {
                return;
            }
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    new java.util.HashSet<>(Files.getPosixFilePermissions(program));
            permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(program, permissions);
        } catch (IOException | RuntimeException e) {
            // Windows has no execute bit, and a file that cannot be changed is reported when it is run.
        }
    }

    /** The installation in what was unpacked: the folder itself, or the one folder an archive usually wraps it in. */
    static Optional<Path> home(Toolchain toolchain, Path unpacked) throws IOException {
        if (toolchain.accepts(unpacked)) {
            return Optional.of(unpacked);
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(unpacked, Files::isDirectory)) {
            for (Path child : children) {
                if (toolchain.accepts(child)) {
                    return Optional.of(child);
                }
            }
        }
        return Optional.empty();
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String extension(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.endsWith(".zip") ? ".zip" : u.endsWith(".tgz") ? ".tgz" : ".tar.gz";
    }

    /** "Go toolchain" asks to download Go; "Node.js" and "Python" are already names. */
    static String shortName(Toolchain toolchain) {
        String name = toolchain.displayName();
        return name.endsWith(" toolchain") ? name.substring(0, name.length() - " toolchain".length()) : name;
    }

    /** A path as a person reads it, with the home folder as ~. */
    static String shortPath(Path path) {
        String home = System.getProperty("user.home", "");
        String text = path.toString();
        return !home.isBlank() && text.startsWith(home) ? "~" + text.substring(home.length()) : text;
    }

    private static String plainVersion(String version) {
        return version.startsWith("v") ? version.substring(1) : version;
    }

    static String size(long bytes) {
        if (bytes >= 1_000_000) {
            return Math.round(bytes / 1_000_000.0) + " MB";
        }
        return Math.max(1, Math.round(bytes / 1000.0)) + " KB";
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteTree(path);
        } catch (IOException e) {
            // Left behind in ~/.smide/tools; the next download of this version replaces it.
        }
    }

    private void style(Window window) {
        try {
            ide.theme().style(window);
        } catch (RuntimeException e) {
            // The platform's colours will do.
        }
    }

    private Optional<Window> owner() {
        try {
            return Optional.of(ide.window().stage());
        } catch (RuntimeException e) {
            return Window.getWindows().stream().filter(Window::isShowing).findFirst();
        }
    }

    /** The small window a download runs in: what is happening, how far along, and Cancel. */
    private final class Progress {
        private final Stage stage = new Stage();
        private final Label message = new Label();
        private final ProgressBar bar = new ProgressBar(-1);
        private volatile boolean cancelled;

        Progress(String title) {
            stage.setTitle(title);
            com.smide.api.ui.Windows.belongsTo(stage, owner().orElse(null));
            stage.initModality(Modality.NONE);
            Label heading = new Label(title + "...");
            heading.getStyleClass().add("toolchain-download-title");
            message.setMinHeight(Region.USE_PREF_SIZE);
            message.setWrapText(true);
            bar.setMaxWidth(Double.MAX_VALUE);
            Button cancel = new Button("Cancel");
            cancel.setCancelButton(true);
            cancel.setOnAction(e -> {
                cancelled = true;
                cancel.setDisable(true);
                message.setText("Cancelling...");
            });
            stage.setOnCloseRequest(e -> {
                e.consume();
                cancel.fire();
            });
            HBox buttons = new HBox(cancel);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            VBox root = new VBox(10, heading, bar, message, buttons);
            VBox.setVgrow(message, Priority.ALWAYS);
            root.setPadding(new Insets(16));
            root.setPrefWidth(460);
            root.getStyleClass().add("toolchain-download-progress");
            stage.setScene(new Scene(root));
            style(stage);
        }

        void show() {
            stage.show();
        }

        void close() {
            stage.close();
        }

        ProgressReporter reporter(String step) {
            return new ProgressReporter() {
                @Override
                public void progress(String text, double fraction) {
                    Platform.runLater(() -> {
                        if (!cancelled) {
                            message.setText(describe(step, text));
                            bar.setProgress(fraction < 0 || fraction > 1 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
                        }
                    });
                }

                @Override
                public boolean cancelled() {
                    return cancelled;
                }
            };
        }
    }
}
