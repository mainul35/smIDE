package com.smide.plugins.java.maven;

import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocationTracker;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3ReaderEx;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every name in a pom that points at another pom, where it is written, and where it leads.
 *
 * <p>Dependencies, imported BOMs, the parent, plugins and modules. Each is placed in the text
 * exactly - the characters of its {@code <artifactId>}, as the editor shows them - so it can
 * be drawn in red when it leads nowhere and followed with Ctrl+click when it leads somewhere.
 *
 * <p>Three outcomes, never two. A name is resolved, or it is missing - Maven has not got it
 * and a build would fail - or it is unknown: its version depends on something this does not
 * follow, or the parent that would say is itself missing. Only missing is drawn red.
 */
public final class PomReferences {

    /** What a name in a pom is. */
    public enum Kind { DEPENDENCY, MANAGED, BOM, PARENT, PLUGIN, MANAGED_PLUGIN, MODULE }

    /** Whether it leads anywhere. */
    public enum State { RESOLVED, MISSING, UNKNOWN }

    /**
     * One name in the pom.
     *
     * @param start       offset of the first character of the artifactId (or module) text
     * @param end         offset just after its last character
     * @param spans       every span that counts as this reference for Ctrl+click: group, artifact, version
     * @param coordinates {@code group:artifact:version} as resolved, for messages
     * @param target      the pom it leads to, when it leads to one
     * @param message     for a missing or unknown reference, what is wrong in words
     */
    public record Reference(Kind kind, State state, int start, int end, List<int[]> spans,
                            String coordinates, Path target, String message) {

        boolean covers(int offset) {
            for (int[] span : spans) {
                if (offset >= span[0] && offset <= span[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    private final PomResolver resolver;

    public PomReferences(PomResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * The references in a pom's text; empty while it does not parse, which is most of the
     * time somebody is typing a new element.
     */
    public Optional<List<Reference>> scan(Path file, String text) {
        Model model;
        try {
            model = new MavenXpp3ReaderEx().read(new StringReader(text), false, new InputSource());
        } catch (Exception e) {
            return Optional.empty();
        }
        PomResolver.Effective effective = resolver.effective(model, file);
        Lines lines = new Lines(text);
        List<Reference> out = new ArrayList<>();

        if (model.getParent() != null) {
            out.add(parent(model, file, lines));
        }
        dependencies(model.getDependencies(), Kind.DEPENDENCY, effective, lines, out);
        if (model.getDependencyManagement() != null) {
            dependencies(model.getDependencyManagement().getDependencies(), Kind.MANAGED, effective, lines, out);
        }
        plugins(model.getBuild(), effective, lines, out);
        for (Profile profile : model.getProfiles()) {
            dependencies(profile.getDependencies(), Kind.DEPENDENCY, effective, lines, out);
            if (profile.getDependencyManagement() != null) {
                dependencies(profile.getDependencyManagement().getDependencies(), Kind.MANAGED, effective, lines, out);
            }
            plugins(profile.getBuild(), effective, lines, out);
            modules(profile.getModules(), profile, file, lines, out);
        }
        modules(model.getModules(), model, file, lines, out);
        return Optional.of(out);
    }

    /** The reference at an offset - for Ctrl+click - if there is one. */
    public Optional<Reference> at(Path file, String text, int offset) {
        return scan(file, text).flatMap(all -> all.stream().filter(r -> r.covers(offset)).findFirst());
    }

    // ------------------------------------------------------------------ kinds

    private Reference parent(Model model, Path file, Lines lines) {
        Parent parent = model.getParent();
        int[] artifact = lines.span(parent, "artifactId");
        List<int[]> spans = spans(lines, parent, "groupId", "artifactId", "version");
        String coordinates = parent.getGroupId() + ":" + parent.getArtifactId() + ":" + parent.getVersion();
        Optional<Path> found = resolver.parentPom(model, file);
        if (found.isPresent()) {
            return new Reference(Kind.PARENT, State.RESOLVED, artifact[0], artifact[1], spans, coordinates, found.get(), null);
        }
        return new Reference(Kind.PARENT, State.MISSING, artifact[0], artifact[1], spans, coordinates, null,
                "Parent " + coordinates + " is neither at " + parent.getRelativePath()
                        + " nor in the local repository (" + resolver.repository().shown() + "). Maven cannot read"
                        + " this pom without it; a build of the parent, or of the whole project, puts it there.");
    }

    private void dependencies(List<Dependency> dependencies, Kind declared, PomResolver.Effective effective,
                              Lines lines, List<Reference> out) {
        for (Dependency d : dependencies) {
            Kind kind = declared == Kind.MANAGED && "import".equals(d.getScope()) && "pom".equals(d.getType())
                    ? Kind.BOM : declared;
            int[] artifact = lines.span(d, "artifactId");
            if (artifact == null) {
                continue;
            }
            List<int[]> spans = spans(lines, d, "groupId", "artifactId", "version");
            String groupId = effective.interpolate(d.getGroupId());
            String artifactId = effective.interpolate(d.getArtifactId());
            String type = kind == Kind.BOM ? "pom" : d.getType();
            String classifier = effective.interpolate(d.getClassifier());
            if (groupId == null || artifactId == null) {
                out.add(unknown(kind, artifact, spans, d.getGroupId() + ":" + d.getArtifactId(),
                        "Its coordinates depend on a property this pom and its parents do not set."));
                continue;
            }
            Optional<Path> module = resolver.module(groupId, artifactId);
            if (module.isPresent()) {
                out.add(new Reference(kind, State.RESOLVED, artifact[0], artifact[1], spans,
                        groupId + ":" + artifactId, module.get(), null));
                continue;
            }
            String version = d.getVersion() != null ? effective.interpolate(d.getVersion())
                    : effective.managedVersions().get(PomResolver.key(groupId, artifactId, type, classifier));
            String name = groupId + ":" + artifactId + (version == null ? "" : ":" + version);
            if ("system".equals(d.getScope())) {
                String path = effective.interpolate(d.getSystemPath());
                boolean there = path != null && Files.isRegularFile(Path.of(path));
                out.add(new Reference(kind, there ? State.RESOLVED : State.MISSING, artifact[0], artifact[1], spans,
                        name, null, there ? "A system dependency: a jar at " + path + ", not a pom to open."
                        : "System dependency " + name + " points at " + d.getSystemPath() + ", and there is no file there."));
                continue;
            }
            if (version == null) {
                out.add(unknown(kind, artifact, spans, name, d.getVersion() == null
                        ? (effective.complete() ? "Nothing in this pom, its parents or its imported BOMs gives it a version."
                                : "Its version would come from a parent or a BOM that is not in the local repository.")
                        : "Its version, " + d.getVersion() + ", depends on something this pom and its parents do not set,"
                                + " or is a range."));
                continue;
            }
            if (resolver.repository().has(groupId, artifactId, version, type, classifier)) {
                Path pom = resolver.repository().pom(groupId, artifactId, version);
                out.add(new Reference(kind, State.RESOLVED, artifact[0], artifact[1], spans, name,
                        Files.isRegularFile(pom) ? pom : null, Files.isRegularFile(pom) ? null
                        : name + " is in the local repository without its pom, so there is nothing to open."));
                continue;
            }
            /* Missing. A managed entry is only a version waiting to be used: Maven fetches it
               when something depends on it, so it is not drawn red, only said to be absent. */
            String absent = (kind == Kind.BOM ? "BOM " : "Dependency ") + name + " is not in the local repository ("
                    + resolver.repository().shown() + "). Maven downloads it on the next build; until then the"
                    + " project cannot " + (kind == Kind.BOM ? "take versions from it." : "compile against it.");
            out.add(new Reference(kind, kind == Kind.MANAGED ? State.UNKNOWN : State.MISSING,
                    artifact[0], artifact[1], spans, name, null, kind == Kind.MANAGED
                    ? name + " has not been downloaded: nothing uses it yet, or nothing has been built since it was added."
                    : absent));
        }
    }

    private void plugins(BuildBase build, PomResolver.Effective effective, Lines lines, List<Reference> out) {
        if (build == null) {
            return;
        }
        List<Plugin> used = new ArrayList<>(build.getPlugins());
        List<Plugin> managed = build.getPluginManagement() == null ? List.of() : build.getPluginManagement().getPlugins();
        for (Plugin p : used) {
            plugin(p, Kind.PLUGIN, effective, lines, out);
            dependencies(p.getDependencies(), Kind.DEPENDENCY, effective, lines, out);
        }
        for (Plugin p : managed) {
            plugin(p, Kind.MANAGED_PLUGIN, effective, lines, out);
        }
    }

    private void plugin(Plugin p, Kind kind, PomResolver.Effective effective, Lines lines, List<Reference> out) {
        int[] artifact = lines.span(p, "artifactId");
        if (artifact == null) {
            return;
        }
        List<int[]> spans = spans(lines, p, "groupId", "artifactId", "version");
        String groupId = p.getGroupId() == null ? PomResolver.DEFAULT_PLUGIN_GROUP : effective.interpolate(p.getGroupId());
        String artifactId = effective.interpolate(p.getArtifactId());
        if (groupId == null || artifactId == null) {
            out.add(unknown(kind, artifact, spans, p.getArtifactId(), "Its coordinates depend on a property that is not set."));
            return;
        }
        String version = p.getVersion() != null ? effective.interpolate(p.getVersion())
                : effective.managedPlugins().get(groupId + ":" + artifactId);
        String name = groupId + ":" + artifactId + (version == null ? "" : ":" + version);
        if (version == null) {
            // Maven's own plugins take a version from Maven itself when the pom gives none; which one is not written here.
            out.add(unknown(kind, artifact, spans, name, "No version is written for it, so it is the one this"
                    + " Maven installation picks - which cannot be read from the pom."));
            return;
        }
        if (resolver.repository().has(groupId, artifactId, version, "maven-plugin", null)) {
            Path pom = resolver.repository().pom(groupId, artifactId, version);
            out.add(new Reference(kind, State.RESOLVED, artifact[0], artifact[1], spans, name,
                    Files.isRegularFile(pom) ? pom : null, null));
            return;
        }
        out.add(new Reference(kind, kind == Kind.PLUGIN ? State.MISSING : State.UNKNOWN, artifact[0], artifact[1],
                spans, name, null, kind == Kind.PLUGIN
                ? "Plugin " + name + " is not in the local repository (" + resolver.repository().shown()
                        + "). Maven downloads it on the next build that uses it."
                : name + " has not been downloaded: no build has used it yet."));
    }

    private void modules(List<String> modules, InputLocationTracker owner, Path file, Lines lines, List<Reference> out) {
        InputLocation all = owner.getLocation("modules");
        for (int i = 0; i < modules.size(); i++) {
            InputLocation at = all == null ? null : all.getLocation(i);
            int[] span = at == null ? null : lines.valueAt(at);
            if (span == null || file == null || file.getParent() == null) {
                continue;
            }
            String name = modules.get(i).strip();
            Path pom = file.getParent().resolve(name).normalize();
            if (Files.isDirectory(pom) || !name.endsWith(".xml")) {
                pom = pom.resolve("pom.xml");
            }
            boolean there = Files.isRegularFile(pom);
            out.add(new Reference(Kind.MODULE, there ? State.RESOLVED : State.MISSING, span[0], span[1], List.of(span),
                    name, there ? pom : null, there ? null
                    : "There is no pom.xml at " + name + ". Maven refuses to build this project until the module exists."));
        }
    }

    private static Reference unknown(Kind kind, int[] artifact, List<int[]> spans, String name, String why) {
        return new Reference(kind, State.UNKNOWN, artifact[0], artifact[1], spans, name, null, name + ": " + why);
    }

    private static List<int[]> spans(Lines lines, InputLocationTracker tracker, String... fields) {
        List<int[]> spans = new ArrayList<>();
        for (String field : fields) {
            int[] span = lines.span(tracker, field);
            if (span != null) {
                spans.add(span);
            }
        }
        return spans;
    }

    /**
     * Turns the reader's line and column into offsets in the text.
     *
     * <p>The reader reports each element at the character just after its start tag, which is
     * where its text begins; the text runs to the next {@code <}. Surrounding whitespace -
     * a value written on its own line - is not part of the name.
     */
    static final class Lines {
        private final String text;
        private final int[] starts;

        Lines(String text) {
            this.text = text;
            List<Integer> found = new ArrayList<>();
            found.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    found.add(i + 1);
                }
            }
            starts = found.stream().mapToInt(Integer::intValue).toArray();
        }

        int[] span(InputLocationTracker tracker, String field) {
            InputLocation at = tracker.getLocation(field);
            return at == null ? null : valueAt(at);
        }

        int[] valueAt(InputLocation at) {
            int line = at.getLineNumber() - 1;
            if (line < 0 || line >= starts.length) {
                return null;
            }
            int start = Math.min(text.length(), starts[line] + Math.max(0, at.getColumnNumber() - 1));
            int end = text.indexOf('<', start);
            if (end < 0) {
                return null;
            }
            while (start < end && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            return end > start ? new int[] {start, end} : null;
        }
    }
}
