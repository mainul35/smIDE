package com.smide.plugins.kotlin;

import com.smide.api.lang.FileType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

import java.util.Set;

/** Registers Kotlin: the file type, highlighting and the kotlin-language-server launcher. */
public final class KotlinPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        // What running loose Kotlin needs comes with the plugin: kotlinc, run configurations, settings.
        KotlinToolchain kotlin = new KotlinToolchain();
        context.registerToolchain(kotlin);
        context.registerRunConfigurationType(new KotlinRunType(context.ide(), kotlin::locate));
        context.registerSettingsPage(new com.smide.api.lang.ToolchainSettingsPage(context.ide(), "Languages/Kotlin", kotlin));

        context.registerFileType(new FileType("kotlin", "Kotlin source",
                Set.of("kt", "kts"), Set.of(), "mdi2l-language-kotlin", false));
        context.registerLanguage(new KotlinLanguage());
    }
}
