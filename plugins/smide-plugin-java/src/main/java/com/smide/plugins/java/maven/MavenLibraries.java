package com.smide.plugins.java.maven;

import com.smide.api.project.LibraryProvider;
import com.smide.api.workspace.Workspace;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Maven project's libraries, as IntelliJ lists them under External Libraries: every
 * dependency of every module, and their dependencies in turn, each shown as its folder in
 * the local repository - the jar and the pom side by side.
 *
 * <p>Worked out from the poms alone, the way Maven does it but without asking Maven: the
 * nearest declaration of an artifact wins, a transitive dependency that is optional, test or
 * provided is left out, exclusions are honoured, and the project's own dependency management
 * decides versions all the way down. Other modules of the project are the project, not
 * libraries. Close enough to what a build resolves to find a file in it; not a build.
 */
public final class MavenLibraries implements LibraryProvider {

    private static final int MAX_DEPTH = 15;
    private static final int MAX_LIBRARIES = 3000;
    private static final Set<String> NOT_INHERITED = Set.of("test", "provided", "system", "import");

    private final PomResolver resolver;
    private final Supplier<Map<String, Path>> modules;
    private final Function<Path, List<Path>> pomsUnder;

    /**
     * @param modules   the open projects' modules by {@code group:artifact}
     * @param pomsUnder every pom of a project, by its root
     */
    public MavenLibraries(PomResolver resolver, Supplier<Map<String, Path>> modules, Function<Path, List<Path>> pomsUnder) {
        this.resolver = resolver;
        this.modules = modules;
        this.pomsUnder = pomsUnder;
    }

    private record Wanted(String groupId, String artifactId, String version, String scope,
                          Set<String> exclusions, Map<String, String> managed, int depth) {
    }

    @Override
    public List<Library> libraries(Workspace workspace) {
        List<Path> poms = pomsUnder.apply(workspace.root());
        if (poms.isEmpty()) {
            return List.of();
        }
        Map<String, Path> projectModules = modules.get();
        Deque<Wanted> queue = new ArrayDeque<>();
        for (Path pom : poms) {
            Optional<Model> model = resolver.read(pom);
            if (model.isEmpty() || "pom".equals(model.get().getPackaging()) && model.get().getDependencies().isEmpty()) {
                continue;
            }
            PomResolver.Effective effective = resolver.effective(model.get(), pom);
            for (Dependency d : declared(effective)) {
                wanted(effective, d, Set.of(), effective.managedVersions(), 1).ifPresent(queue::add);
            }
        }

        Map<String, Library> found = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty() && found.size() < MAX_LIBRARIES) {
            Wanted w = queue.poll();
            String ga = w.groupId() + ":" + w.artifactId();
            // Nearest wins: breadth first, so the first one met is the nearest.
            if (!seen.add(ga)) {
                continue;
            }
            if (projectModules.containsKey(ga)) {
                continue;
            }
            Path dir = resolver.repository().versionDir(w.groupId(), w.artifactId(), w.version());
            found.put(ga, new Library("Maven: " + ga + ":" + w.version(), dir));
            if (w.depth() >= MAX_DEPTH) {
                continue;
            }
            Path pom = resolver.repository().pom(w.groupId(), w.artifactId(), w.version());
            Optional<Model> model = resolver.read(pom);
            if (model.isEmpty()) {
                continue;
            }
            PomResolver.Effective effective = resolver.effectiveOf(model.get(), pom, 0);
            // The project's own management first, then what this library manages for itself.
            Map<String, String> managed = new LinkedHashMap<>(w.managed());
            effective.managedVersions().forEach(managed::putIfAbsent);
            for (Dependency d : declared(effective)) {
                if (d.isOptional() || NOT_INHERITED.contains(scope(d))) {
                    continue;
                }
                String group = effective.interpolate(d.getGroupId());
                String artifact = effective.interpolate(d.getArtifactId());
                if (group == null || artifact == null || excluded(w.exclusions(), group, artifact)) {
                    continue;
                }
                Set<String> exclusions = new HashSet<>(w.exclusions());
                for (Exclusion e : d.getExclusions()) {
                    exclusions.add(e.getGroupId() + ":" + e.getArtifactId());
                }
                wanted(effective, d, exclusions, managed, w.depth() + 1).ifPresent(queue::add);
            }
        }
        List<Library> libraries = new ArrayList<>(found.values());
        libraries.sort(Comparator.comparing(Library::name));
        return libraries;
    }

    /** A dependency with its coordinates filled in, or empty when its version cannot be told. */
    private static Optional<Wanted> wanted(PomResolver.Effective effective, Dependency d, Set<String> exclusions,
                                           Map<String, String> managed, int depth) {
        String group = effective.interpolate(d.getGroupId());
        String artifact = effective.interpolate(d.getArtifactId());
        if (group == null || artifact == null) {
            return Optional.empty();
        }
        String key = PomResolver.key(group, artifact, d.getType(), effective.interpolate(d.getClassifier()));
        // Management from the top decides, even over a version written lower down - as Maven does.
        String version = depth > 1 ? managed.get(key) : null;
        if (version == null) {
            version = d.getVersion() == null ? managed.get(key) : effective.interpolate(d.getVersion());
        }
        if (version == null) {
            version = effective.managedVersions().get(key);
        }
        if (version == null || version.contains("${") || version.startsWith("[") || version.startsWith("(")) {
            return Optional.empty();
        }
        Set<String> excluded = new HashSet<>(exclusions);
        for (Exclusion e : d.getExclusions()) {
            excluded.add(e.getGroupId() + ":" + e.getArtifactId());
        }
        return Optional.of(new Wanted(group, artifact, version, scope(d), excluded, managed, depth));
    }

    /** A pom's dependencies with the ones it inherits, a child's own declaration winning. */
    private static List<Dependency> declared(PomResolver.Effective effective) {
        Map<String, Dependency> byKey = new LinkedHashMap<>();
        for (Model link : effective.chain()) {
            for (Dependency d : link.getDependencies()) {
                byKey.putIfAbsent(d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getType() + ":" + d.getClassifier(), d);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static String scope(Dependency d) {
        return d.getScope() == null ? "compile" : d.getScope();
    }

    private static boolean excluded(Set<String> exclusions, String group, String artifact) {
        return exclusions.contains(group + ":" + artifact) || exclusions.contains(group + ":*")
                || exclusions.contains("*:*") || exclusions.contains("*:" + artifact);
    }

    /** A file in the local repository belongs to the version folder it is in: .../group/artifact/version/file. */
    @Override
    public Optional<Library> libraryOf(Path file) {
        Path root = resolver.repository().root().toAbsolutePath().normalize();
        Path at = file.toAbsolutePath().normalize();
        if (!at.startsWith(root) || !Files.isRegularFile(at)) {
            return Optional.empty();
        }
        Path relative = root.relativize(at);
        int n = relative.getNameCount();
        if (n < 4) {
            return Optional.empty();
        }
        String version = relative.getName(n - 2).toString();
        String artifact = relative.getName(n - 3).toString();
        StringBuilder group = new StringBuilder();
        for (int i = 0; i < n - 3; i++) {
            group.append(i == 0 ? "" : ".").append(relative.getName(i));
        }
        return Optional.of(new Library("Maven: " + group + ":" + artifact + ":" + version, at.getParent()));
    }
}
