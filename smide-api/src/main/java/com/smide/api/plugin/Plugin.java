package com.smide.api.plugin;

/**
 * The entry point of a plugin.
 *
 * <p>A plugin jar names its implementation in {@code META-INF/smide-plugin.properties}
 * ({@code mainClass=...}) and, for class-path development runs, in
 * {@code META-INF/services/com.smide.api.plugin.Plugin}. The IDE instantiates it with the
 * no-argument constructor and calls {@link #start} once the core services are ready.
 * Everything a plugin contributes is registered through the {@link PluginContext} it is
 * given; nothing is discovered by reflection beyond this class.
 */
public interface Plugin {

    /** Register extensions and take references to services. Runs on the JavaFX thread. */
    void start(PluginContext context);

    /** Release processes, threads and watchers. The IDE is shutting down or the plugin was disabled. */
    default void stop() {
    }
}
