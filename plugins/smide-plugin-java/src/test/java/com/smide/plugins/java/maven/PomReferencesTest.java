package com.smide.plugins.java.maven;

import com.smide.api.editor.DeclarationProvider;
import com.smide.api.problems.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pom's references against a local repository and a project made for the purpose: what
 * is drawn red, what leads where on Ctrl+click, and - as important - what is never drawn red.
 */
class PomReferencesTest {

    @TempDir
    Path temp;

    private Path repository;
    private Path project;
    private PomResolver resolver;
    private PomReferences references;
    private Map<String, Path> modules;

    @BeforeEach
    void setUp() throws IOException {
        repository = Files.createDirectories(temp.resolve("repository"));
        project = Files.createDirectories(temp.resolve("project"));

        artifact("org.openjfx", "javafx-controls", "21", "jar", "");
        artifact("com.google.code.gson", "gson", "2.11.0", "jar", "");
        artifact("com.example", "lib-a", "2.0", "jar", "");
        artifact("org.apache.maven.plugins", "maven-compiler-plugin", "3.13.0", "jar", "");
        artifact("com.example", "tj", "1.0", "jar", "tests");
        // A snapshot fetched from a remote repository: kept under its timestamp, not its plain name.
        Path snap = Files.createDirectories(repository.resolve("com/example/snap/1.0-SNAPSHOT"));
        Files.writeString(snap.resolve("snap-1.0-SNAPSHOT.pom"), pom("com.example", "snap", "1.0-SNAPSHOT", ""));
        Files.writeString(snap.resolve("snap-1.0-20260918.101010-3.jar"), "jar");
        // A BOM managing two libraries, one of which has never been downloaded.
        Path bom = Files.createDirectories(repository.resolve("com/example/bom/1.0"));
        Files.writeString(bom.resolve("bom-1.0.pom"), pom("com.example", "bom", "1.0", """
                <packaging>pom</packaging>
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.example</groupId><artifactId>lib-a</artifactId><version>2.0</version></dependency>
                  <dependency><groupId>com.example</groupId><artifactId>lib-b</artifactId><version>3.0</version></dependency>
                </dependencies></dependencyManagement>"""));

        // The project: an aggregator with properties and managed versions, and two modules.
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.smide</groupId>
                  <artifactId>smide-parent</artifactId>
                  <version>0.1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>api</module><module>core</module></modules>
                  <properties><javafx.version>21</javafx.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-a</artifactId><version>${project.version}</version></dependency>
                  </dependencies></dependencyManagement>
                  <build><pluginManagement><plugins>
                    <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                  </plugins></pluginManagement></build>
                </project>""");
        Path api = Files.createDirectories(project.resolve("api"));
        Files.writeString(api.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.smide</groupId><artifactId>smide-parent</artifactId><version>0.1.0</version></parent>
                  <artifactId>smide-api</artifactId>
                </project>""");
        Files.createDirectories(project.resolve("core"));

        modules = Map.of("com.smide:smide-api", api.resolve("pom.xml"),
                "com.smide:smide-parent", project.resolve("pom.xml"));
        resolver = new PomResolver(new LocalRepository(repository), () -> modules);
        references = new PomReferences(resolver);
    }

    // ------------------------------------------------------------------ red, or not

    @Test
    void aDependencyMavenHasIsNotRed_andLeadsToItsPom() {
        String core = core("""
                <dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId>
                  <version>${javafx.version}</version></dependency>""");
        PomReferences.Reference r = only(core, "javafx-controls");
        assertEquals(PomReferences.State.RESOLVED, r.state(), r.message());
        assertEquals(repository.resolve("org/openjfx/javafx-controls/21/javafx-controls-21.pom"), r.target());
        assertTrue(diagnostics(core).isEmpty());
    }

    @Test
    void aDependencyMavenDoesNotHaveIsRed_onExactlyItsArtifactId() {
        String core = core("""
                <dependency><groupId>com.example</groupId><artifactId>lib-b</artifactId><version>3.0</version></dependency>""");
        PomReferences.Reference r = only(core, "lib-b");
        assertEquals(PomReferences.State.MISSING, r.state());
        assertEquals("lib-b", core.substring(r.start(), r.end()), "the red is the name, not the element");

        List<Diagnostic> red = diagnostics(core);
        assertEquals(1, red.size());
        assertEquals(Diagnostic.UNRESOLVED, red.get(0).code(), "drawn as an unresolved name, not underlined");
        assertTrue(red.get(0).message().contains("com.example:lib-b:3.0"), red.get(0).message());
        assertTrue(red.get(0).message().contains("not in the local repository"), red.get(0).message());
    }

    @Test
    void aVersionFromAnImportedBomIsFollowed() {
        String core = core("""
                <dependency><groupId>com.example</groupId><artifactId>lib-a</artifactId></dependency>
                <dependency><groupId>com.example</groupId><artifactId>lib-b</artifactId></dependency>""",
                """
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.example</groupId><artifactId>bom</artifactId><version>1.0</version>
                    <type>pom</type><scope>import</scope></dependency>
                </dependencies></dependencyManagement>""");
        List<PomReferences.Reference> all = references.scan(coreFile(), core).orElseThrow();
        // lib-a is managed twice: explicitly by the parent, and by the BOM. The explicit one wins, as in Maven.
        PomReferences.Reference a = find(all, core, "lib-a", PomReferences.Kind.DEPENDENCY);
        assertEquals("com.example:lib-a:0.1.0", a.coordinates(), "the parent's explicit entry, in the child's context");
        PomReferences.Reference b = find(all, core, "lib-b", PomReferences.Kind.DEPENDENCY);
        assertEquals("com.example:lib-b:3.0", b.coordinates(), "the BOM's version");
        assertEquals(PomReferences.State.MISSING, b.state());
        PomReferences.Reference bom = find(all, core, "bom", PomReferences.Kind.BOM);
        assertEquals(PomReferences.State.RESOLVED, bom.state());
    }

    @Test
    void aParentsProjectVersionMeansTheChildsVersion() {
        // The parent manages lib-a at ${project.version}; this child is version 2.0, and lib-a 2.0 is there.
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.smide</groupId><artifactId>smide-parent</artifactId><version>0.1.0</version></parent>
                  <artifactId>versioned</artifactId>
                  <version>2.0</version>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib-a</artifactId></dependency>
                  </dependencies>
                </project>""";
        PomReferences.Reference r = only(pom, "lib-a", project.resolve("versioned/pom.xml"));
        assertEquals("com.example:lib-a:2.0", r.coordinates());
        assertEquals(PomReferences.State.RESOLVED, r.state());
    }

    @Test
    void anotherModuleOfTheProjectLeadsToItsFolder_notTheRepository() {
        String core = core("""
                <dependency><groupId>com.smide</groupId><artifactId>smide-api</artifactId></dependency>""");
        PomReferences.Reference r = only(core, "smide-api");
        assertEquals(PomReferences.State.RESOLVED, r.state());
        assertEquals(project.resolve("api/pom.xml"), r.target());
    }

    @Test
    void aVersionThatCannotBeWorkedOutIsNeverRed() {
        String core = core("""
                <dependency><groupId>com.example</groupId><artifactId>lib-c</artifactId>
                  <version>${version.from.the.command.line}</version></dependency>
                <dependency><groupId>com.example</groupId><artifactId>lib-d</artifactId><version>[1.0,2.0)</version></dependency>""");
        assertEquals(PomReferences.State.UNKNOWN, only(core, "lib-c").state());
        assertEquals(PomReferences.State.UNKNOWN, only(core, "lib-d").state());
        assertTrue(diagnostics(core).isEmpty(), "unknown is not missing");
    }

    @Test
    void aManagedVersionNothingUsesIsNotRed() {
        String core = core("", """
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.example</groupId><artifactId>lib-b</artifactId><version>3.0</version></dependency>
                </dependencies></dependencyManagement>""");
        assertEquals(PomReferences.State.UNKNOWN, only(core, "lib-b").state());
        assertTrue(diagnostics(core).isEmpty(), "Maven only fetches a managed version when something uses it");
    }

    @Test
    void theTypeDecidesWhichFileCounts_andATimestampedSnapshotCounts() {
        String core = core("""
                <dependency><groupId>com.example</groupId><artifactId>tj</artifactId><version>1.0</version><type>test-jar</type></dependency>
                <dependency><groupId>com.example</groupId><artifactId>snap</artifactId><version>1.0-SNAPSHOT</version></dependency>""");
        assertEquals(PomReferences.State.RESOLVED, only(core, "tj").state(), "tj-1.0-tests.jar");
        assertEquals(PomReferences.State.RESOLVED, only(core, "snap").state());
    }

    @Test
    void aParentThatCannotBeFoundIsRed() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.example</groupId><artifactId>gone</artifactId><version>1</version></parent>
                  <artifactId>orphan</artifactId>
                </project>""";
        PomReferences.Reference r = only(pom, "gone", project.resolve("orphan/pom.xml"));
        assertEquals(PomReferences.Kind.PARENT, r.kind());
        assertEquals(PomReferences.State.MISSING, r.state());
    }

    @Test
    void theParentAtItsRelativePathIsFound() {
        PomReferences.Reference r = only(core(""), "smide-parent");
        assertEquals(PomReferences.State.RESOLVED, r.state());
        assertEquals(project.resolve("pom.xml"), r.target());
    }

    @Test
    void pluginsAreCheckedWhenTheirVersionIsKnown() {
        String core = core("", """
                <build><plugins>
                  <plugin><artifactId>maven-compiler-plugin</artifactId></plugin>
                  <plugin><artifactId>maven-surefire-plugin</artifactId><version>9.9</version></plugin>
                  <plugin><artifactId>maven-jar-plugin</artifactId></plugin>
                </plugins></build>""");
        assertEquals(PomReferences.State.RESOLVED, only(core, "maven-compiler-plugin").state(), "version from the parent's pluginManagement");
        assertEquals(PomReferences.State.MISSING, only(core, "maven-surefire-plugin").state());
        assertEquals(PomReferences.State.UNKNOWN, only(core, "maven-jar-plugin").state(), "Maven picks its version; the pom does not say");
    }

    @Test
    void aModuleWithNoPomIsRed() {
        String aggregator = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.smide</groupId><artifactId>agg</artifactId><version>1</version><packaging>pom</packaging>
                  <modules><module>api</module><module>not-written-yet</module></modules>
                </project>""";
        Path file = project.resolve("pom.xml");
        List<PomReferences.Reference> all = references.scan(file, aggregator).orElseThrow();
        assertEquals(PomReferences.State.RESOLVED, find(all, aggregator, "api", PomReferences.Kind.MODULE).state());
        PomReferences.Reference missing = find(all, aggregator, "not-written-yet", PomReferences.Kind.MODULE);
        assertEquals(PomReferences.State.MISSING, missing.state());
        assertEquals(project.resolve("api/pom.xml"), find(all, aggregator, "api", PomReferences.Kind.MODULE).target());
    }

    // ------------------------------------------------------------------ Ctrl+click

    @Test
    void ctrlClickAnywhereOnTheCoordinatesLeadsToThePom() {
        String core = core("""
                <dependency><groupId>org.openjfx</groupId><artifactId>javafx-controls</artifactId><version>21</version></dependency>""");
        Path file = coreFile();
        for (String part : List.of("org.openjfx", "javafx-controls")) {
            int offset = core.indexOf(part) + 2;
            Optional<DeclarationProvider.Declaration> found = declarationAt(file, core, offset);
            assertEquals(repository.resolve("org/openjfx/javafx-controls/21/javafx-controls-21.pom"),
                    found.orElseThrow().file(), "Ctrl+click on " + part);
        }
    }

    @Test
    void ctrlClickOnSomethingMissingSaysWhyRatherThanAskingTheXmlServer() {
        String core = core("""
                <dependency><groupId>com.example</groupId><artifactId>lib-b</artifactId><version>3.0</version></dependency>""");
        DeclarationProvider.Declaration d = declarationAt(coreFile(), core, core.indexOf("lib-b") + 1).orElseThrow();
        assertNull(d.file());
        assertTrue(d.unavailable().contains("not in the local repository"), d.unavailable());
    }

    @Test
    void ctrlClickAwayFromAnyCoordinateIsLeftToTheXmlServer() {
        String core = core("");
        assertTrue(declarationAt(coreFile(), core, core.indexOf("modelVersion") + 2).isEmpty());
    }

    @Test
    void aPomHalfTypedIsNotAFailure() {
        assertTrue(references.scan(coreFile(), "<project><dependencies><dependency><groupId>com.ex").isEmpty());
    }

    // ------------------------------------------------------------------ settings

    @Test
    void theRepositoryInSettingsIsUsed_butNotTheOneInTheComment() throws IOException {
        Path settings = temp.resolve("settings.xml");
        Files.writeString(settings, """
                <settings>
                  <!-- <localRepository>/path/to/local/repo</localRepository> -->
                  <localRepository>${user.home}/maven-cache</localRepository>
                </settings>""");
        assertEquals(System.getProperty("user.home") + "/maven-cache", LocalRepository.configuredIn(settings));

        Files.writeString(settings, "<settings><!-- <localRepository>/path/to/local/repo</localRepository> --></settings>");
        assertNull(LocalRepository.configuredIn(settings), "the default settings.xml only has it in a comment");
    }

    // ------------------------------------------------------------------ helpers

    /** The core module's pom, with these dependencies and anything else after them. */
    private static String core(String dependencies, String... rest) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.smide</groupId><artifactId>smide-parent</artifactId><version>0.1.0</version></parent>
                  <artifactId>smide-core</artifactId>
                  <dependencies>
                """ + dependencies + """
                  </dependencies>
                """ + String.join("\n", rest) + """
                </project>""";
    }

    private Path coreFile() {
        return project.resolve("core/pom.xml");
    }

    private PomReferences.Reference only(String pom, String artifactId) {
        return only(pom, artifactId, project.resolve("core/pom.xml"));
    }

    private PomReferences.Reference only(String pom, String artifactId, Path file) {
        List<PomReferences.Reference> all = references.scan(file, pom).orElseThrow();
        return all.stream().filter(r -> artifactId.equals(pom.substring(r.start(), r.end())))
                .findFirst().orElseThrow(() -> new AssertionError("no reference to " + artifactId + " in " + all));
    }

    private static PomReferences.Reference find(List<PomReferences.Reference> all, String pom, String name,
                                                PomReferences.Kind kind) {
        return all.stream().filter(r -> r.kind() == kind && name.equals(pom.substring(r.start(), r.end())))
                .findFirst().orElseThrow(() -> new AssertionError("no " + kind + " " + name));
    }

    private List<Diagnostic> diagnostics(String pom) {
        Path file = project.resolve("core/pom.xml");
        return MavenPomSupport.diagnostics(file, pom, references.scan(file, pom).orElseThrow());
    }

    private Optional<DeclarationProvider.Declaration> declarationAt(Path file, String text, int offset) {
        return references.at(file, text, offset).map(r -> r.target() != null
                ? DeclarationProvider.Declaration.at(r.target(), 0, 0)
                : DeclarationProvider.Declaration.nowhere(r.message()));
    }

    private void artifact(String groupId, String artifactId, String version, String extension, String classifier)
            throws IOException {
        Path dir = Files.createDirectories(repository.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version));
        Files.writeString(dir.resolve(artifactId + "-" + version + ".pom"), pom(groupId, artifactId, version, ""));
        Files.writeString(dir.resolve(artifactId + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier)
                + "." + extension), "binary");
    }

    private static String pom(String groupId, String artifactId, String version, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + groupId + "</groupId><artifactId>"
                + artifactId + "</artifactId><version>" + version + "</version>" + body + "</project>";
    }
}
