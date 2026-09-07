package com.smide.api.ui;

import java.util.function.Consumer;

public interface StatusBar {

    /** Shows a message on the left for a few seconds. Any thread. */
    void message(String text);

    /**
     * Shows a progress indicator for a background task and returns a handle to update
     * and finish it. Any thread.
     */
    Progress progress(String title, boolean cancellable);

    interface Progress {
        void update(String message, double fraction);

        void done();

        boolean isCancelled();

        void onCancel(Consumer<Progress> handler);
    }
}
