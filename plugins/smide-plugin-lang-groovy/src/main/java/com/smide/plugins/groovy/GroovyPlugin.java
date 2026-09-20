package com.smide.plugins.groovy;

import com.smide.api.lang.FileType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

import java.util.Set;

/**
 * Groovy, and with it Gradle's build scripts: {@code build.gradle} and {@code settings.gradle}
 * are Groovy, and were shown as plain text. ({@code build.gradle.kts} is Kotlin, and the Kotlin
 * plugin has it.)
 */
public final class GroovyPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        context.registerFileType(new FileType("groovy", "Groovy",
                Set.of("groovy", "gradle", "gvy", "gy"), Set.of("Jenkinsfile"), "mdi2l-language-kotlin", false));
        context.registerLanguage(new GroovyLanguage());
    }
}
