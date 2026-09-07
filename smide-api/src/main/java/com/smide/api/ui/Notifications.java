package com.smide.api.ui;

import java.util.List;

/** Balloons in the corner and a list in the Notifications tool window. Any thread. */
public interface Notifications {

    void info(String title, String message, NotificationAction... actions);

    void warn(String title, String message, NotificationAction... actions);

    void error(String title, String message, NotificationAction... actions);

    List<Notification> history();

    record Notification(long time, Level level, String title, String message, List<NotificationAction> actions) {
    }

    record NotificationAction(String label, Runnable action) {
    }

    enum Level {
        INFO, WARNING, ERROR
    }
}
