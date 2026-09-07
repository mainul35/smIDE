package com.smide.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import org.kordamp.ikonli.javafx.FontIcon;

/** Ikonli helpers: an icon from a literal, a flat icon button. */
public final class Icons {

    private Icons() {
    }

    /** A FontIcon for the literal, or null if the literal is null or unknown. */
    public static FontIcon of(String literal) {
        return of(literal, 14);
    }

    public static FontIcon of(String literal, int size) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        try {
            FontIcon icon = new FontIcon(literal);
            icon.setIconSize(size);
            return icon;
        } catch (RuntimeException e) {
            System.err.println("smIDE: unknown icon " + literal);
            return null;
        }
    }

    public static Button button(String literal, String tooltip, Runnable onAction) {
        Button button = new Button();
        Node icon = of(literal);
        if (icon != null) {
            button.setGraphic(icon);
        } else {
            button.setText(tooltip);
        }
        button.getStyleClass().add("icon-button");
        if (tooltip != null) {
            button.setTooltip(new Tooltip(tooltip));
        }
        button.setFocusTraversable(false);
        button.setOnAction(e -> onAction.run());
        return button;
    }
}
