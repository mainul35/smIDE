package com.smide.api.execution;

import com.smide.api.ui.Notifications;

import java.util.List;

/**
 * Why a configuration cannot run, together with what the reader can do about it.
 *
 * <p>Thrown from {@link RunConfiguration#prepare}; the actions appear on the notification
 * that reports it. A missing debugger, say, comes with a button that installs it, rather
 * than a sentence telling the reader to go and find out how.
 */
public class CannotRunException extends Exception {

    private final List<Notifications.NotificationAction> actions;

    public CannotRunException(String message, Notifications.NotificationAction... actions) {
        super(message);
        this.actions = List.of(actions);
    }

    public List<Notifications.NotificationAction> actions() {
        return actions;
    }
}
