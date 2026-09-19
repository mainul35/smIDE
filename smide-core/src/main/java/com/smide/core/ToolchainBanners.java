package com.smide.core;

import com.smide.api.Ide;
import com.smide.api.editor.Editor;
import com.smide.api.lang.Toolchain;
import com.smide.editor.CodeEditor;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The strip across the top of an editor whose language has nothing to run it - "No Python
 * interpreter found. Download Python... Choose location..." - as IntelliJ shows over a .py
 * file with no interpreter configured.
 *
 * <p>Looked for off the UI thread when an editor opens, since finding an interpreter can mean
 * searching the disk. Closed with its x for the rest of the session; gone from every editor
 * once the toolchain has been installed or pointed at.
 */
public final class ToolchainBanners {

    private final Ide ide;
    private final ExtensionRegistry registry;
    private ToolchainInstaller installer;
    private final Set<String> dismissed = ConcurrentHashMap.newKeySet();

    public ToolchainBanners(Ide ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    void setInstaller(ToolchainInstaller installer) {
        this.installer = installer;
    }

    /** Looks at an editor that has just opened. */
    public void opened(Editor editor) {
        if (!(editor instanceof CodeEditor code) || editor.path() == null) {
            return;
        }
        List<Toolchain> toolchains = registry.toolchains();
        Path file = editor.path();
        if (toolchains.stream().noneMatch(t -> runs(t, file))) {
            return;
        }
        ide.window().runInBackground(() -> missing(file).ifPresent(t -> ide.window().runLater(() -> show(code, t))));
    }

    /** Checks every open editor again - after something was installed, the banners it answered go. */
    public void refresh() {
        for (Editor editor : ide.editors().open()) {
            if (editor instanceof CodeEditor code && code.banner() != null) {
                Path file = editor.path();
                ide.window().runInBackground(() -> {
                    Optional<Toolchain> still = missing(file);
                    ide.window().runLater(() -> {
                        if (still.isEmpty()) {
                            code.clearBanner();
                        }
                    });
                });
            }
        }
    }

    /** The toolchain that runs this file, if it is not on the machine and nobody has closed its banner. */
    private Optional<Toolchain> missing(Path file) {
        for (Toolchain t : registry.toolchains()) {
            try {
                if (runs(t, file) && !dismissed.contains(t.id()) && t.locate(ide).isEmpty()) {
                    return Optional.of(t);
                }
            } catch (RuntimeException e) {
                System.err.println("smIDE: toolchain " + t.id() + " could not look for itself: " + e);
            }
        }
        return Optional.empty();
    }

    private static boolean runs(Toolchain toolchain, Path file) {
        try {
            return toolchain.runsFile(file);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void show(CodeEditor code, Toolchain toolchain) {
        String name = ToolchainInstaller.shortName(toolchain);
        Label text = new Label("No " + toolchain.displayName() + " found on this machine.");
        text.setTooltip(new javafx.scene.control.Tooltip(toolchain.purpose()));
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.SOMETIMES);
        HBox strip = new HBox(text);
        strip.getStyleClass().add("editor-banner");

        if (installer != null && (ToolchainInstaller.canInstall(toolchain) || toolchain.downloadUrl() != null)) {
            Hyperlink download = new Hyperlink("Download " + name + "...");
            download.getStyleClass().add("editor-banner-download");
            download.setMinWidth(Region.USE_PREF_SIZE);
            download.setOnAction(e -> installer.offer(toolchain));
            strip.getChildren().add(download);
        }
        if (installer != null && toolchain.homeSetting() != null) {
            Hyperlink choose = new Hyperlink("Choose location...");
            choose.setMinWidth(Region.USE_PREF_SIZE);
            choose.setOnAction(e -> installer.chooseHome(toolchain));
            strip.getChildren().add(choose);
        }
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("editor-banner-close");
        close.setOnAction(e -> {
            dismissed.add(toolchain.id());
            for (Editor editor : ide.editors().open()) {
                if (editor instanceof CodeEditor other && other.banner() != null
                        && toolchain.id().equals(other.banner().getProperties().get("toolchain"))) {
                    other.clearBanner();
                }
            }
        });
        strip.getChildren().addAll(gap, close);
        strip.getProperties().put("toolchain", toolchain.id());
        code.setBanner(strip);
    }
}
