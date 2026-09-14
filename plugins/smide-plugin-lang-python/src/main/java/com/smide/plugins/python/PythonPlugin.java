package com.smide.plugins.python;

import com.smide.api.lang.FileType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

import java.util.Set;

/** Registers Python: file types, highlighting, requirements files and the pyright launcher. */
public final class PythonPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        // What running Python needs comes with the plugin: the interpreter, its run configurations, its settings.
        PythonToolchain python = new PythonToolchain();
        context.registerToolchain(python);
        context.registerRunConfigurationType(new PythonRunType(context.ide(), python::locate));
        context.registerSettingsPage(new com.smide.api.lang.ToolchainSettingsPage(context.ide(), "Languages/Python", python));

        context.registerFileType(new FileType("python", "Python source",
                Set.of("py", "pyi", "pyw"), Set.of(), "mdi2l-language-python", false));
        // pyproject.toml is TOML and belongs to the config plugin; these two are Python's own.
        context.registerFileType(new FileType("python-config", "Python project file",
                Set.of(), Set.of("requirements.txt", "requirements-dev.txt", "constraints.txt", "setup.cfg"),
                "mdi2l-language-python", false));
        context.registerLanguage(new PythonLanguage());
        context.registerLanguage(new RequirementsLanguage());
    }
}
