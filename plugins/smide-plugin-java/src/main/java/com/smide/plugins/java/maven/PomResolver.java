package com.smide.plugins.java.maven;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a pom means once Maven has finished with it - enough of it, at least, to say which
 * version of each dependency it asks for.
 *
 * <p>A version is rarely written where it is used. It comes from a property, or a parent's
 * property, or a parent's {@code <dependencyManagement>}, or a BOM imported into one of those,
 * which has parents of its own. This follows all of that the way Maven does: parents found by
 * their relative path or in the local repository, properties merged down the chain, managed
 * versions child-first and imports after, and inherited entries read in the context of the
 * pom that inherits them - so a parent's {@code ${project.version}} means the child's version,
 * as it does to Maven.
 *
 * <p>What it does not follow it says it does not know. Profiles, properties set on the command
 * line, version ranges: a version depending on any of those is reported as unknown, never as
 * missing. Red text on a dependency that is actually fine would teach people to ignore red
 * text.
 */
public final class PomResolver {

    static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_DEPTH = 20;

    /**
     * A pom with everything it inherits and imports.
     *
     * @param chain             the pom first, then its parent, and so on up
     * @param properties        every property in scope, the pom's own built-ins included
     * @param managedVersions   {@code group:artifact:type:classifier} to version
     * @param managedPlugins    {@code group:artifact} to version
     * @param complete          false when a parent or an imported BOM could not be found, so
     *                          that a version not found might simply be in what is missing
     */
    public record Effective(Path file, List<Model> chain, Map<String, String> properties,
                            Map<String, String> managedVersions, Map<String, String> managedPlugins,
                            boolean complete) {

        public Model model() {
            return chain.get(0);
        }

        /** A value with its properties filled in; null when one of them is not known here. */
        public String interpolate(String value) {
            return PomResolver.interpolate(value, properties);
        }
    }

    private record Stamped<T>(long modified, long size, T value) {
    }

    private final LocalRepository repository;
    private final Supplier<Map<String, Path>> modules;
    private final Map<Path, Stamped<Model>> models = new ConcurrentHashMap<>();
    private final Map<Path, Stamped<Effective>> effectives = new ConcurrentHashMap<>();

    /**
     * @param modules the poms of the open projects, by {@code group:artifact} - a module of
     *                the project being worked on is resolved from its folder, not the repository
     */
    public PomResolver(LocalRepository repository, Supplier<Map<String, Path>> modules) {
        this.repository = repository;
        this.modules = modules;
    }

    public LocalRepository repository() {
        return repository;
    }

    /** The module of an open project with these coordinates, if there is one. */
    public Optional<Path> module(String groupId, String artifactId) {
        return Optional.ofNullable(modules.get().get(groupId + ":" + artifactId));
    }

    /** A pom as written, read once until it changes. */
    public Optional<Model> read(Path pom) {
        try {
            if (!Files.isRegularFile(pom)) {
                return Optional.empty();
            }
            long modified = Files.getLastModifiedTime(pom).toMillis();
            long size = Files.size(pom);
            Stamped<Model> cached = models.get(pom);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return Optional.of(cached.value());
            }
            try (Reader reader = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
                Model model = new MavenXpp3Reader().read(reader, false);
                models.put(pom, new Stamped<>(modified, size, model));
                return Optional.of(model);
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The pom being edited, as it stands in the editor: never cached, because it keeps changing. */
    public Effective effective(Model model, Path file) {
        return compute(model, file, 0);
    }

    /** A pom on disk - a parent, an imported BOM - cached until the file changes. */
    Effective effectiveOf(Model model, Path file, int depth) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            Stamped<Effective> cached = effectives.get(file);
            if (cached != null && cached.modified() == modified && cached.size() == size) {
                return cached.value();
            }
            Effective effective = compute(model, file, depth);
            effectives.put(file, new Stamped<>(modified, size, effective));
            return effective;
        } catch (Exception e) {
            return compute(model, file, depth);
        }
    }

    private Effective compute(Model model, Path file, int depth) {
        Effective parent = null;
        boolean complete = true;
        if (model.getParent() != null) {
            Optional<Path> parentPom = depth >= MAX_DEPTH ? Optional.empty() : parentPom(model, file);
            Optional<Model> parentModel = parentPom.flatMap(this::read);
            if (parentModel.isPresent()) {
                parent = effectiveOf(parentModel.get(), parentPom.get(), depth + 1);
                complete = parent.complete();
            } else {
                complete = false;
            }
        }

        List<Model> chain = new ArrayList<>();
        chain.add(model);
        if (parent != null) {
            chain.addAll(parent.chain());
        }

        Map<String, String> properties = new HashMap<>(parent == null ? Map.of() : parent.properties());
        model.getProperties().forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
        builtIns(model, properties);

        Map<String, String> managed = new LinkedHashMap<>();
        List<Dependency> imports = new ArrayList<>();
        for (Model link : chain) {
            DependencyManagement management = link.getDependencyManagement();
            if (management == null) {
                continue;
            }
            for (Dependency d : management.getDependencies()) {
                if ("import".equals(d.getScope()) && "pom".equals(d.getType())) {
                    imports.add(d);
                    continue;
                }
                String key = key(interpolate(d.getGroupId(), properties), interpolate(d.getArtifactId(), properties),
                        d.getType(), interpolate(d.getClassifier(), properties));
                String version = interpolate(d.getVersion(), properties);
                if (key != null && version != null) {
                    managed.putIfAbsent(key, version);
                }
            }
        }
        // Imported BOMs after everything written out: an explicit entry always wins over an imported one.
        for (Dependency bom : imports) {
            String groupId = interpolate(bom.getGroupId(), properties);
            String artifactId = interpolate(bom.getArtifactId(), properties);
            String version = interpolate(bom.getVersion(), properties);
            if (groupId == null || artifactId == null || version == null || depth >= MAX_DEPTH) {
                complete = false;
                continue;
            }
            Optional<Path> bomPom = module(groupId, artifactId)
                    .or(() -> Optional.of(repository.pom(groupId, artifactId, version)).filter(Files::isRegularFile));
            Optional<Model> bomModel = bomPom.flatMap(this::read);
            if (bomModel.isEmpty()) {
                complete = false;
                continue;
            }
            Effective imported = effectiveOf(bomModel.get(), bomPom.get(), depth + 1);
            imported.managedVersions().forEach(managed::putIfAbsent);
            complete &= imported.complete();
        }

        Map<String, String> plugins = new LinkedHashMap<>();
        for (Model link : chain) {
            Build build = link.getBuild();
            if (build == null) {
                continue;
            }
            List<Plugin> declared = new ArrayList<>(build.getPlugins());
            PluginManagement management = build.getPluginManagement();
            if (management != null) {
                declared.addAll(management.getPlugins());
            }
            for (Plugin p : declared) {
                String groupId = p.getGroupId() == null ? DEFAULT_PLUGIN_GROUP : interpolate(p.getGroupId(), properties);
                String artifactId = interpolate(p.getArtifactId(), properties);
                String version = interpolate(p.getVersion(), properties);
                if (groupId != null && artifactId != null && version != null) {
                    plugins.putIfAbsent(groupId + ":" + artifactId, version);
                }
            }
        }
        return new Effective(file, List.copyOf(chain), Map.copyOf(properties), managed, plugins, complete);
    }

    /**
     * Where a pom's parent is: at its relative path when the pom there is that parent, among
     * the open projects' modules, or in the local repository.
     */
    public Optional<Path> parentPom(Model model, Path file) {
        Parent parent = model.getParent();
        if (parent == null) {
            return Optional.empty();
        }
        // A version like ${revision} is filled in from the pom's own properties - all Maven itself allows.
        Map<String, String> own = new HashMap<>();
        model.getProperties().forEach((k, v) -> own.put(String.valueOf(k), String.valueOf(v)));
        String groupId = interpolate(parent.getGroupId(), own);
        String artifactId = interpolate(parent.getArtifactId(), own);
        String version = interpolate(parent.getVersion(), own);
        if (groupId == null || artifactId == null) {
            return Optional.empty();
        }
        String relative = parent.getRelativePath();
        if (file != null && file.getParent() != null && relative != null && !relative.isBlank()) {
            Path candidate = file.getParent().resolve(relative).normalize();
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            Optional<Model> there = read(candidate);
            if (there.isPresent() && artifactId.equals(there.get().getArtifactId())
                    && groupId.equals(groupOf(there.get()))) {
                return Optional.of(candidate);
            }
        }
        Optional<Path> open = module(groupId, artifactId);
        if (open.isPresent()) {
            return open;
        }
        if (version == null) {
            return Optional.empty();
        }
        Path inRepository = repository.pom(groupId, artifactId, version);
        return Files.isRegularFile(inRepository) ? Optional.of(inRepository) : Optional.empty();
    }

    // ------------------------------------------------------------------ pieces

    /** A pom's group, inherited from its parent when it does not write its own. */
    public static String groupOf(Model model) {
        if (model.getGroupId() != null) {
            return model.getGroupId();
        }
        return model.getParent() == null ? null : model.getParent().getGroupId();
    }

    /** A pom's version, inherited from its parent when it does not write its own. */
    public static String versionOf(Model model) {
        if (model.getVersion() != null) {
            return model.getVersion();
        }
        return model.getParent() == null ? null : model.getParent().getVersion();
    }

    /** The {@code ${project.*}} names, and the old spellings of them projects still use. */
    private static void builtIns(Model model, Map<String, String> properties) {
        Map<String, String> own = new HashMap<>();
        own.put("groupId", groupOf(model));
        own.put("artifactId", model.getArtifactId());
        own.put("version", versionOf(model));
        own.put("packaging", model.getPackaging() == null ? "jar" : model.getPackaging());
        if (model.getParent() != null) {
            own.put("parent.groupId", model.getParent().getGroupId());
            own.put("parent.artifactId", model.getParent().getArtifactId());
            own.put("parent.version", model.getParent().getVersion());
        }
        own.forEach((name, value) -> {
            if (value != null) {
                properties.put("project." + name, value);
                properties.put("pom." + name, value);
                if (!name.contains(".")) {
                    properties.put(name, value);
                }
            }
        });
    }

    /** The key a managed dependency is found by: group, artifact, type and classifier. */
    public static String key(String groupId, String artifactId, String type, String classifier) {
        if (groupId == null || artifactId == null) {
            return null;
        }
        return groupId + ":" + artifactId + ":" + (type == null || type.isBlank() ? "jar" : type) + ":"
                + (classifier == null ? "" : classifier);
    }

    /**
     * A value with its properties filled in, as deep as they go; null when one is not known,
     * or when the value is a version range - neither says which version it will be.
     */
    public static String interpolate(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String out = value.strip();
        for (int pass = 0; pass < 10 && out.contains("${"); pass++) {
            Matcher m = VARIABLE.matcher(out);
            StringBuilder next = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String replacement = properties.get(m.group(1));
                if (replacement != null) {
                    changed = true;
                }
                m.appendReplacement(next, Matcher.quoteReplacement(replacement == null ? m.group() : replacement.strip()));
            }
            m.appendTail(next);
            out = next.toString();
            if (!changed) {
                break;
            }
        }
        if (out.contains("${") || out.startsWith("[") || out.startsWith("(")) {
            return null;
        }
        return out;
    }
}
