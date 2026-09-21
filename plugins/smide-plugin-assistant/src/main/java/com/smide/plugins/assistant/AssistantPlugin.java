package com.smide.plugins.assistant;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

/**
 * A programming buddy in the IDE: it reads your code and it sets you problems.
 *
 * <p>It does not write code. Everything here is arranged around that: a review reports
 * findings and has no Apply button, and a practice session marks an answer without
 * supplying one. What it is for is the part of the work a second pair of eyes does -
 * noticing what a file does badly, and finding out whether you actually know a thing by
 * being asked.
 *
 * <p>Where it sends code is configured in Settings &gt; Tools &gt; Assistant, and refused
 * unless the host has been allowed. Nothing is sent until a button is pressed.
 */
public final class AssistantPlugin implements Plugin {

    private AssistantToolWindow toolWindow;

    @Override
    public void start(PluginContext context) {
        Ide ide = context.ide();
        AssistantConfig config = new AssistantConfig(ide);
        Assistant assistant = new Assistant(ide, config);
        toolWindow = new AssistantToolWindow(assistant);

        context.registerToolWindow(toolWindow);
        context.registerSettingsPage(new AssistantSettingsPage(ide, assistant));

        context.registerAction(Action.of("assistant.review", "Review This File")
                .menu("Tools").icon("fth-search").order(70)
                .description("Read the open file for code smells, security problems and technical debt")
                .contextMenu("editor").contextMenu("explorer")
                .shortcut("shortcut+alt+R")
                .enabledWhen(ctx -> ide.editors().active().isPresent())
                .perform(ctx -> {
                    // Shown first: a tool window that has never been created has no
                    // content to ask, so the review would go nowhere.
                    ide.toolWindows().show(AssistantToolWindow.ID);
                    toolWindow.reviewActiveFile();
                }));

        context.registerAction(Action.of("assistant.settings", "Assistant Settings...")
                .menu("Tools").icon("fth-sliders").order(72)
                .description("Which model the assistant uses, and what it may see")
                .perform(ctx -> ide.showSettings("Tools/Assistant")));

        context.registerAction(Action.of("assistant.ask", "Ask About This Project...")
                .menu("Tools").icon("fth-message-square").order(21)
                .shortcut("shortcut+alt+A")
                .perform(ctx -> {
                    ide.toolWindows().show(AssistantToolWindow.ID);
                    toolWindow.showAsk();
                }));
        context.registerAction(Action.of("assistant.practice", "Practice Session...")
                .menu("Tools").icon("fth-award").order(71)
                .description("Practise a topic: questions one at a time, marked when you submit")
                .perform(ctx -> {
                    ide.toolWindows().show(AssistantToolWindow.ID);
                    toolWindow.showPractice();
                }));
    }

    @Override
    public void stop() {
        if (toolWindow != null) {
            toolWindow.dispose();
        }
    }
}
