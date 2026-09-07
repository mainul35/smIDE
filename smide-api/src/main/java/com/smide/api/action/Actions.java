package com.smide.api.action;

import java.util.List;
import java.util.Optional;

public interface Actions {

    List<Action> all();

    Optional<Action> byId(String id);

    /** Runs the action against the current context, if it is enabled. */
    void invoke(String id);

    /** A context built from what is currently selected. */
    ActionContext currentContext();

    /** The shortcut in force for an action, after user overrides. */
    Optional<String> shortcutOf(String id);
}
