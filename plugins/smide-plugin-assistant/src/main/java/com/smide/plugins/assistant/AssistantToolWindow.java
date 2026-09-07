package com.smide.plugins.assistant;

import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * The Assistant tool window: two things a programming buddy does.
 *
 * <p>Review reads the file in front of you. Practice sets you problems and marks them.
 * They are tabs of one window rather than two windows because they are one relationship -
 * the same model, the same configuration, and the same rule that it explains rather than
 * writes.
 */
public final class AssistantToolWindow implements ToolWindowFactory {

    public static final String ID = "assistant";

    private final Assistant assistant;
    private TabPane tabs;
    private ReviewPanel review;
    private PracticePanel practice;

    public AssistantToolWindow(Assistant assistant) {
        this.assistant = assistant;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Assistant";
    }

    @Override
    public String iconLiteral() {
        return "fth-message-circle";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.RIGHT;
    }

    @Override
    public String shortcut() {
        return "alt+9";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public Node create(ToolWindowContext context) {
        if (tabs == null) {
            review = new ReviewPanel(assistant);
            practice = new PracticePanel(assistant);
            Tab reviewTab = new Tab("Review", review);
            reviewTab.setClosable(false);
            Tab practiceTab = new Tab("Practice", practice);
            practiceTab.setClosable(false);
            tabs = new TabPane(reviewTab, practiceTab);
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        }
        return tabs;
    }

    /** Brings Review forward and reads the open file. */
    void reviewActiveFile() {
        if (tabs != null) {
            tabs.getSelectionModel().select(0);
            review.reviewActive();
        }
    }

    /** Brings Practice forward, for the menu item that starts a session. */
    void showPractice() {
        if (tabs != null) {
            tabs.getSelectionModel().select(1);
        }
    }

    void dispose() {
        if (practice != null) {
            practice.dispose();
        }
    }
}
