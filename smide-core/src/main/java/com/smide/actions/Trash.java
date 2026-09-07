package com.smide.actions;

import java.awt.Desktop;
import java.nio.file.Path;

/** Moves files to the platform recycle bin through AWT's Desktop, where supported. */
final class Trash {

    private Trash() {
    }

    static boolean moveToTrash(Path path) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                    return desktop.moveToTrash(path.toFile());
                }
            }
        } catch (RuntimeException e) {
            // Fall back to a plain delete.
        }
        return false;
    }
}
