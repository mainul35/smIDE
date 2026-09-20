package com.smide.plugins.java.run;

import com.smide.api.execution.RunConfiguration;
import com.smide.api.project.ProjectModel;
import com.smide.plugins.java.JavaProjectInfo;
import com.smide.plugins.java.JavaProjectRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detect on a configuration made by hand: what the project can answer, it answers. */
class ApplicationDetectTest {

    @Test
    void itFillsInTheMainClassAndTheModuleThatAssemblesIt(@TempDir Path root) throws IOException {
        JavaProjectRegistry registry = registryFor(root);
        ApplicationRunType type = new ApplicationRunType(null, registry);
        RunConfiguration configuration = type.create(workspace(root));

        List<String> filled = type.complete(workspace(root), configuration);

        assertEquals(List.of("Main class", "Classpath of module", "Working directory"), filled);
        assertEquals("com.app.Main", configuration.toMap().get("mainClass"));
        // Not "core", where the class is: "dist" is what puts the application together.
        assertEquals("dist", configuration.toMap().get("module"));
        assertTrue(configuration.toMap().get("workingDir").endsWith("dist"));
    }

    @Test
    void andLeavesAloneWhatSomebodyAlreadyFilledIn(@TempDir Path root) throws IOException {
        ApplicationRunType type = new ApplicationRunType(null, registryFor(root));
        RunConfiguration configuration = type.create(workspace(root));
        configuration.fromMap(java.util.Map.of("mainClass", "com.app.Other", "module", "core"));

        List<String> filled = type.complete(workspace(root), configuration);

        assertEquals(List.of("Working directory"), filled);
        assertEquals("com.app.Other", configuration.toMap().get("mainClass"));
        assertEquals("core", configuration.toMap().get("module"));
    }

    /** A reactor of two: "core" holds the main class, "dist" has no sources and depends on it. */
    private static JavaProjectRegistry registryFor(Path root) throws IOException {
        Path core = Files.createDirectories(root.resolve("core"));
        Path dist = Files.createDirectories(root.resolve("dist"));
        Files.writeString(core.resolve("pom.xml"), pom("core", ""));
        Files.writeString(dist.resolve("pom.xml"), pom("dist",
                "<dependencies><dependency><groupId>com.app</groupId><artifactId>core</artifactId>"
                        + "</dependency></dependencies>"));
        Path source = Files.createDirectories(core.resolve("src/main/java/com/app")).resolve("Main.java");
        Files.writeString(source, "package com.app; public class Main { public static void main(String[] a) {} }");

        ProjectModel model = new ProjectModel("maven", "app", root, List.of(
                new ProjectModel.ProjectModule("core", core, List.of(core.resolve("src/main/java")),
                        List.of(), List.of(), null),
                new ProjectModel.ProjectModule("dist", dist, List.of(), List.of(), List.of(), null)),
                List.of());
        JavaProjectInfo info = new JavaProjectInfo("maven", model, Set.of(),
                List.of(new JavaProjectInfo.RunnableClass("com.app.Main", core, source)),
                List.of(), "pom", "app", "1.0", List.of(), List.of(), 21);
        JavaProjectRegistry registry = new JavaProjectRegistry();
        registry.put(root, info);
        return registry;
    }

    private static String pom(String artifactId, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>com.app</groupId>"
                + "<artifactId>" + artifactId + "</artifactId><version>1.0</version>" + body + "</project>";
    }

    /** A workspace that is only its root, which is all this asks of it. */
    private static com.smide.api.workspace.Workspace workspace(Path root) {
        return (com.smide.api.workspace.Workspace) java.lang.reflect.Proxy.newProxyInstance(
                com.smide.api.workspace.Workspace.class.getClassLoader(),
                new Class<?>[] {com.smide.api.workspace.Workspace.class},
                (proxy, method, args) -> "root".equals(method.getName()) ? root
                        : "name".equals(method.getName()) ? String.valueOf(root.getFileName()) : null);
    }
}
