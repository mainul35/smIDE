package com.smide.api.plugin;

import java.nio.file.Path;
import java.util.List;

/**
 * What {@code META-INF/smide-plugin.properties} says about a plugin.
 *
 * @param id       stable identifier, e.g. {@code com.smide.java}
 * @param name     display name
 * @param version  plugin version string
 * @param vendor   who ships it
 * @param description one sentence for the Plugins settings page
 * @param depends  ids of plugins that must start first
 * @param home     the plugin's directory on disk, or {@code null} when loaded from the class path
 */
public record PluginDescriptor(String id,
                               String name,
                               String version,
                               String vendor,
                               String description,
                               List<String> depends,
                               Path home) {
}
