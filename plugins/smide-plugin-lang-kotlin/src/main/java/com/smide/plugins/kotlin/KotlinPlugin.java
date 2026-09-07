package com.smide.plugins.kotlin;

import com.smide.api.lang.FileType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

import java.util.Set;

/** Registers Kotlin: the file type, highlighting and the kotlin-language-server launcher. */
public final class KotlinPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        context.registerFileType(new FileType("kotlin", "Kotlin source",
                Set.of("kt", "kts"), Set.of(), "mdi2l-language-kotlin", false));
        context.registerLanguage(new KotlinLanguage());
    }
}
