package com.smide.plugins.java.maven;

import com.smide.api.project.LibraryProvider.Library;
import com.smide.api.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A project's External Libraries, worked out from its poms against a repository made for the purpose. */
class MavenLibrariesTest {

    @TempDir
    Path temp;

    private Path repository;

    private void library(String group, String artifact, String version, String body) throws IOException {
        Path dir = Files.createDirectories(repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version));
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
                  %s
                </project>""".formatted(group, artifact, version, body));
        Files.writeString(dir.resolve(artifact + "-" + version + ".jar"), "jar");
    }

    private static String dependency(String group, String artifact, String version, String extra) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>"
                + (version == null ? "" : "<version>" + version + "</version>") + extra + "</dependency>";
    }

    private static Workspace workspace(Path root) {
        return (Workspace) Proxy.newProxyInstance(Workspace.class.getClassLoader(), new Class<?>[] {Workspace.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "root" -> root;
                    case "name" -> root.getFileName().toString();
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "workspace";
                    default -> null;
                });
    }

    @Test
    void everyLibraryTheBuildUsesAsMavenWouldResolveIt() throws IOException {
        repository = Files.createDirectories(temp.resolve("repository"));
        Path project = Files.createDirectories(temp.resolve("project"));

        // a -> b (1.0, managed up to 2.0 by the project), c (optional), d (test), e -> f
        library("org.a", "a", "1.0", "<dependencies>"
                + dependency("org.b", "b", "1.0", "")
                + dependency("org.c", "c", "1.0", "<optional>true</optional>")
                + dependency("org.d", "d", "1.0", "<scope>test</scope>")
                + dependency("org.e", "e", "1.0", "")
                + "</dependencies>");
        library("org.b", "b", "1.0", "");
        library("org.b", "b", "2.0", "");
        library("org.c", "c", "1.0", "");
        library("org.d", "d", "1.0", "");
        library("org.e", "e", "1.0", "<dependencies>" + dependency("org.f", "f", "1.0", "") + "</dependencies>");
        library("org.f", "f", "1.0", "");
        library("org.junit", "junit", "4.13", "");
        library("org.lsp", "lsp", "0.24", "<dependencies>" + dependency("org.gone", "gone", "1.0", "") + "</dependencies>");

        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.me</groupId><artifactId>app</artifactId><version>1</version>
                  <properties><lsp.version>0.24</lsp.version></properties>
                  <dependencyManagement><dependencies>%s</dependencies></dependencyManagement>
                  <dependencies>%s%s%s%s</dependencies>
                </project>""".formatted(
                dependency("org.b", "b", "2.0", ""),
                dependency("org.a", "a", "1.0", "<exclusions><exclusion><groupId>org.f</groupId><artifactId>f</artifactId></exclusion></exclusions>"),
                dependency("org.junit", "junit", "4.13", "<scope>test</scope>"),
                dependency("org.lsp", "lsp", "${lsp.version}", ""),
                dependency("com.me", "other-module", "1", "")));
        Path other = Files.createDirectories(project.resolve("other"));
        Files.writeString(other.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.me</groupId><artifactId>other-module</artifactId><version>1</version>
                </project>""");

        PomResolver resolver = new PomResolver(new LocalRepository(repository),
                () -> Map.of("com.me:app", project.resolve("pom.xml"), "com.me:other-module", other.resolve("pom.xml")));
        MavenLibraries libraries = new MavenLibraries(resolver,
                () -> Map.of("com.me:app", project.resolve("pom.xml"), "com.me:other-module", other.resolve("pom.xml")),
                root -> List.of(project.resolve("pom.xml"), other.resolve("pom.xml")));

        List<Library> found = libraries.libraries(workspace(project));
        assertEquals(List.of(
                        "Maven: org.a:a:1.0",
                        "Maven: org.b:b:2.0",        // the project's management wins over a's 1.0
                        "Maven: org.e:e:1.0",        // f excluded, c optional, d test: all left out
                        "Maven: org.gone:gone:1.0",  // never downloaded, but still what the build wants
                        "Maven: org.junit:junit:4.13",
                        "Maven: org.lsp:lsp:0.24"),  // a version from a property
                found.stream().map(Library::name).toList());
        assertEquals(repository.resolve("org/lsp/lsp/0.24"), found.get(5).root());
        assertTrue(Files.isRegularFile(found.get(5).root().resolve("lsp-0.24.pom")));
    }

    @Test
    void aFileInTheRepositoryBelongsToItsVersionFolder() throws IOException {
        repository = Files.createDirectories(temp.resolve("repository"));
        library("org.eclipse.lsp4j", "org.eclipse.lsp4j", "0.24.0", "");
        MavenLibraries libraries = new MavenLibraries(new PomResolver(new LocalRepository(repository), Map::of),
                Map::of, root -> List.of());
        Path pom = repository.resolve("org/eclipse/lsp4j/org.eclipse.lsp4j/0.24.0/org.eclipse.lsp4j-0.24.0.pom");
        Library library = libraries.libraryOf(pom).orElseThrow();
        assertEquals("Maven: org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0", library.name());
        assertEquals(pom.getParent(), library.root());
        assertTrue(libraries.libraryOf(temp.resolve("elsewhere.pom")).isEmpty());
    }
}
