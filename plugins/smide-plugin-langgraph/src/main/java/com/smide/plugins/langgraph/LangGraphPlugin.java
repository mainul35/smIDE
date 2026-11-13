package com.smide.plugins.langgraph;

import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.problems.ManifestChecks;
import com.smide.plugins.python.PythonDebugger;

/**
 * LangGraph, built on the Python plugin: the CLI as a toolchain, run configurations for the dev
 * server and for invoking one graph, langgraph.json checked where it is written, and a graph's
 * own mistakes - an edge to a node that is not there, a node nothing leads to - said in the file
 * rather than by an exception at the end of a run.
 */
public final class LangGraphPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        LangGraphToolchain cli = new LangGraphToolchain();
        context.registerToolchain(cli);
        context.registerRunConfigurationType(new LangGraphRunType(context.ide(), cli));
        // An invoke runs the interpreter the Python plugin would, so debugpy debugs it unchanged.
        PythonDebugger.alsoDebug(LangGraphRunType.ID);
        context.registerSettingsPage(
                new com.smide.api.lang.ToolchainSettingsPage(context.ide(), "Languages/LangGraph", cli));
        // The graph beside the file that builds it, drawn from the calls as they are typed.
        context.registerToolWindow(new GraphToolWindow(context.ide()));
        /* File - New Project - LangGraph. A project that makes its own environment and opens
           a notebook you can run, rather than a folder of files and an afternoon of setup. */
        context.registerNewProjectTemplate(new LangGraphTemplate());

        // langgraph.json: a graph pointing at a file that is not there, a dependency that is not.
        ManifestChecks.watch(context.ide(), LangGraphJson.SOURCE, LangGraphJson::isConfig, LangGraphJson::problemsIn);
        // The graphs themselves, in the Python files of a LangGraph project.
        ManifestChecks.watch(context.ide(), GraphChecks.SOURCE, GraphChecks::isGraphFile, GraphChecks::problemsIn);
    }
}
