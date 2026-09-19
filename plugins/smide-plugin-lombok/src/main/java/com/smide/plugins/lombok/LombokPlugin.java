package com.smide.plugins.lombok;

import com.smide.api.Ide;
import com.smide.api.lang.LanguageServerContributor;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.workspace.Workspace;

import java.util.List;

/**
 * Lombok support: a project whose build names Lombok gets it as an agent in the Java language
 * server, so @Data's accessors and @RequiredArgsConstructor's constructor are known to the editor
 * rather than reported missing. Disabling this plugin in Settings > Plugins turns that off.
 */
public final class LombokPlugin implements Plugin {

    /** The Java plugin's server, by the id it registers it under. */
    static final String JAVA_SERVER = "jdtls";

    @Override
    public void start(PluginContext context) {
        context.registerLanguageServerContributor(new LanguageServerContributor() {
            @Override
            public String serverId() {
                return JAVA_SERVER;
            }

            @Override
            public List<String> jvmArguments(Ide ide, Workspace workspace) {
                return Lombok.agentFor(ide, workspace.root()).map(jar -> List.of("-javaagent:" + jar)).orElse(List.of());
            }
        });
    }
}
