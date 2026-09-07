package com.smide.api.ui;

import java.util.List;
import java.util.Optional;

public interface ToolWindows {

    void show(String id);

    void hide(String id);

    void toggle(String id);

    boolean isVisible(String id);

    List<ToolWindowFactory> all();

    Optional<ToolWindowFactory> byId(String id);
}
