package com.smide.core;

import com.smide.api.Ide;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds, loads and starts plugins.
 *
 * <p>Two sources, deduplicated by id:
 * <ul>
 *   <li>{@code plugins/<id>/*.jar} beside the application (each directory gets its own
 *       class loader whose parent is the core's, so plugins share the API and JavaFX but
 *       nothing else);</li>
 *   <li>the class path, through {@link ServiceLoader}, which is how {@code mvn exec:exec}
 *       runs the whole IDE unpackaged during development.</li>
 * </ul>
 * Plugins start in dependency order. A plugin that throws during {@code start} is
 * reported and skipped; it never takes the IDE down with it.
 */
public final class PluginManager {

    public record LoadedPlugin(PluginDescriptor descriptor, Plugin plugin, ClassLoader loader, String error) {
        public boolean isStarted() {
            return error == null;
        }
    }

    private static final String DESCRIPTOR = "META-INF/smide-plugin.properties";

    private final Ide ide;
    private final ExtensionRegistry registry;
    private final List<LoadedPlugin> loaded = new ArrayList<>();

    public PluginManager(Ide ide, ExtensionRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    public List<LoadedPlugin> loaded() {
        return loaded;
    }

    /** Discovers and starts everything. Runs on the JavaFX thread. */
    public void startAll(Path pluginsDir, Set<String> disabled) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (Candidate c : fromDirectory(pluginsDir)) {
            candidates.putIfAbsent(c.descriptor.id(), c);
        }
        for (Candidate c : fromClassPath()) {
            candidates.putIfAbsent(c.descriptor.id(), c);
        }

        Set<String> started = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (Candidate c : candidates.values()) {
            start(c, candidates, started, visiting, disabled);
        }
    }

    private void start(Candidate c, Map<String, Candidate> all, Set<String> started, Set<String> visiting,
                       Set<String> disabled) {
        String id = c.descriptor.id();
        if (started.contains(id) || visiting.contains(id)) {
            return;
        }
        visiting.add(id);
        if (disabled.contains(id)) {
            loaded.add(new LoadedPlugin(c.descriptor, c.plugin, c.loader, "disabled"));
            started.add(id);
            return;
        }
        for (String dep : c.descriptor.depends()) {
            Candidate d = all.get(dep);
            if (d == null) {
                loaded.add(new LoadedPlugin(c.descriptor, c.plugin, c.loader, "missing dependency " + dep));
                started.add(id);
                return;
            }
            start(d, all, started, visiting, disabled);
            if (!isStarted(dep)) {
                // A plugin whose dependency is off or broken would start half-working.
                loaded.add(new LoadedPlugin(c.descriptor, c.plugin, c.loader, "needs " + d.descriptor.name()
                        + ", which is " + (disabled.contains(dep) ? "disabled" : "not running")));
                started.add(id);
                return;
            }
            // A plugin built on another sees its classes: Spring Boot's run type is a Java one.
            if (c.loader instanceof PluginLoader own) {
                own.dependOn(d.loader);
            }
        }
        String error = null;
        Plugin plugin = c.plugin;
        try {
            if (plugin == null) {
                // Loaded only now, with its dependencies running and their classes reachable.
                plugin = (Plugin) Class.forName(c.mainClass, true, c.loader).getDeclaredConstructor().newInstance();
            }
            plugin.start(new PluginContextImpl(c.descriptor, ide, registry));
        } catch (Throwable t) {
            error = t.toString();
            System.err.println("smIDE: plugin " + id + " failed to start: " + t);
            t.printStackTrace();
        }
        loaded.add(new LoadedPlugin(c.descriptor, plugin, c.loader, error));
        started.add(id);
    }

    private boolean isStarted(String id) {
        return loaded.stream().anyMatch(p -> p.descriptor().id().equals(id) && p.isStarted());
    }

    /**
     * A directory plugin's own jars, and through them the plugins it depends on.
     *
     * <p>Each directory plugin has a loader of its own over the core's, so plugins share the API
     * and nothing else - except that a plugin that names another in {@code depends} can use
     * its classes, which it is built against.
     */
    static final class PluginLoader extends URLClassLoader {
        private final List<ClassLoader> dependencies = new java.util.concurrent.CopyOnWriteArrayList<>();

        PluginLoader(String name, URL[] urls, ClassLoader parent) {
            super(name, urls, parent);
        }

        void dependOn(ClassLoader loader) {
            if (loader != this && loader != getParent() && !dependencies.contains(loader)) {
                dependencies.add(loader);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                return super.findClass(name);
            } catch (ClassNotFoundException notMine) {
                for (ClassLoader dependency : dependencies) {
                    try {
                        return dependency.loadClass(name);
                    } catch (ClassNotFoundException notThere) {
                        // The next one, then.
                    }
                }
                throw notMine;
            }
        }
    }

    public void stopAll() {
        for (int i = loaded.size() - 1; i >= 0; i--) {
            LoadedPlugin p = loaded.get(i);
            if (p.isStarted()) {
                try {
                    p.plugin().stop();
                } catch (Throwable t) {
                    System.err.println("smIDE: plugin " + p.descriptor().id() + " failed to stop: " + t);
                }
            }
        }
    }

    // ------------------------------------------------------------ discovery

    /** @param plugin null for a directory plugin, which is created from {@code mainClass} when it starts */
    private record Candidate(PluginDescriptor descriptor, Plugin plugin, ClassLoader loader, String mainClass) {
    }

    private List<Candidate> fromDirectory(Path pluginsDir) {
        List<Candidate> out = new ArrayList<>();
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return out;
        }
        try (Stream<Path> dirs = Files.list(pluginsDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Candidate c = loadDirectory(dir);
                if (c != null) {
                    out.add(c);
                }
            }
        } catch (IOException e) {
            System.err.println("smIDE: cannot list " + pluginsDir + ": " + e);
        }
        return out;
    }

    private Candidate loadDirectory(Path dir) {
        List<URL> urls = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir, 2)) {
            for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                urls.add(jar.toUri().toURL());
            }
        } catch (IOException e) {
            System.err.println("smIDE: cannot read plugin directory " + dir + ": " + e);
            return null;
        }
        if (urls.isEmpty()) {
            return null;
        }
        PluginLoader loader = new PluginLoader(
                "smide-plugin:" + dir.getFileName(), urls.toArray(URL[]::new), PluginManager.class.getClassLoader());
        try (InputStream in = loader.getResourceAsStream(DESCRIPTOR)) {
            if (in == null) {
                System.err.println("smIDE: " + dir + " has no " + DESCRIPTOR + "; ignored");
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            PluginDescriptor descriptor = descriptor(props, dir);
            String mainClass = props.getProperty("mainClass", "").strip();
            if (mainClass.isEmpty()) {
                System.err.println("smIDE: " + dir + " names no mainClass; ignored");
                return null;
            }
            return new Candidate(descriptor, null, loader, mainClass);
        } catch (Exception e) {
            System.err.println("smIDE: cannot load plugin in " + dir + ": " + e);
            return null;
        }
    }

    private List<Candidate> fromClassPath() {
        List<Candidate> out = new ArrayList<>();
        ClassLoader loader = PluginManager.class.getClassLoader();
        Map<String, Properties> descriptorsByMain = new LinkedHashMap<>();
        try {
            Enumeration<URL> resources = loader.getResources(DESCRIPTOR);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (InputStream in = url.openStream()) {
                    Properties props = new Properties();
                    props.load(in);
                    descriptorsByMain.put(props.getProperty("mainClass", "").strip(), props);
                }
            }
        } catch (IOException e) {
            System.err.println("smIDE: cannot enumerate plugin descriptors: " + e);
        }
        /* Iterated by hand rather than with a for-each: ServiceLoader throws
           ServiceConfigurationError from hasNext/next when one provider is missing or
           cannot be instantiated, and a single broken plugin must not stop the IDE from
           starting. Each failure is reported and skipped. */
        Iterator<Plugin> plugins = ServiceLoader.load(Plugin.class, loader).iterator();
        while (true) {
            Plugin plugin;
            try {
                if (!plugins.hasNext()) {
                    break;
                }
                plugin = plugins.next();
            } catch (ServiceConfigurationError | RuntimeException e) {
                System.err.println("smIDE: skipping an unloadable plugin: " + e.getMessage());
                continue;
            }
            Properties props = descriptorsByMain.get(plugin.getClass().getName());
            if (props == null) {
                props = new Properties();
                props.setProperty("id", plugin.getClass().getName());
                props.setProperty("name", plugin.getClass().getSimpleName());
            }
            out.add(new Candidate(descriptor(props, null), plugin, loader, plugin.getClass().getName()));
        }
        return out;
    }

    private static PluginDescriptor descriptor(Properties props, Path home) {
        String depends = props.getProperty("depends", "");
        List<String> deps = Arrays.stream(depends.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        return new PluginDescriptor(
                props.getProperty("id", "unknown").strip(),
                props.getProperty("name", "Unnamed plugin").strip(),
                props.getProperty("version", "0").strip(),
                props.getProperty("vendor", "").strip(),
                props.getProperty("description", "").strip(),
                deps,
                home);
    }
}
