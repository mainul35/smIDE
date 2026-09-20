package com.smide.plugins.springboot;

import com.smide.api.project.ProjectModel;
import com.smide.plugins.java.GradleImporter;
import com.smide.plugins.java.JavaProjectInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether a project is a Spring Boot one, from what its build uses. */
class SpringBootTest {

    private static JavaProjectInfo project(Set<String> artifacts) {
        ProjectModel model = new ProjectModel("maven", "p", Path.of("p"), List.of(), List.of());
        return new JavaProjectInfo("maven", model, artifacts, List.of(), List.of(), "jar", "p", "1", List.of(), List.of(), 21);
    }

    /** A Gradle project whose build is at {@code buildRoot}. */
    private static JavaProjectInfo gradle(Path buildRoot) {
        ProjectModel model = new ProjectModel("gradle", "p", buildRoot, List.of(), List.of());
        return new JavaProjectInfo("gradle", model, Set.of("plugin:org.springframework.boot"), List.of(), List.of(),
                "jar", "p", "1", List.of(), List.of(), 21);
    }

    private static com.smide.api.workspace.Workspace workspace(Path root) {
        return (com.smide.api.workspace.Workspace) java.lang.reflect.Proxy.newProxyInstance(
                com.smide.api.workspace.Workspace.class.getClassLoader(),
                new Class<?>[] {com.smide.api.workspace.Workspace.class},
                (proxy, method, args) -> "root".equals(method.getName()) ? root
                        : "name".equals(method.getName()) ? root.getFileName().toString() : null);
    }

    @Test
    void theTaskIsNamedFromWhereGradleRuns() {
        Path project = Path.of("/home/me/POISYA-SAAS-E-commerce");
        Path build = project.resolve("POISYA-administration/poisya");
        // The build is the module: Gradle runs there, so the task has no path in front of it.
        assertEquals("bootRun", SpringBootRunType.gradleTask(workspace(project), gradle(build),
                "POISYA-administration/poisya", "bootRun"));
        // A module of that build keeps its path, relative to the build.
        assertEquals("app:bootRun", SpringBootRunType.gradleTask(workspace(project), gradle(build),
                "POISYA-administration/poisya/app", "bootRun"));
        // A build at the project's own root, with a module under it.
        assertEquals("service:bootRun", SpringBootRunType.gradleTask(workspace(project), gradle(project),
                "service", "bootRun"));
        assertEquals("bootRun", SpringBootRunType.gradleTask(workspace(project), gradle(project), "", "bootRun"));
    }

    @Test
    void aStarterTheParentOrThePluginMakeItSpringBoot() {
        assertTrue(SpringBoot.isSpringBoot(project(Set.of("org.springframework.boot:spring-boot-starter-web"))));
        assertTrue(SpringBoot.isSpringBoot(project(Set.of("org.springframework.boot:spring-boot-starter-parent"))));
        assertTrue(SpringBoot.isSpringBoot(project(Set.of("plugin:org.springframework.boot"))));
    }

    @Test
    void plainSpringOrNothingDoesNot() {
        assertFalse(SpringBoot.isSpringBoot(project(Set.of("org.springframework:spring-context", "org.projectlombok:lombok"))));
        assertFalse(SpringBoot.isSpringBoot(project(Set.of())));
    }

    @Test
    void aGradleScriptSaysWhatItUses() {
        Set<String> used = GradleImporter.artifactsIn("""
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.3.4'
                }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
                    compileOnly 'org.projectlombok:lombok:1.18.42'
                    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
                }
                """);
        assertTrue(used.contains("plugin:org.springframework.boot"), used.toString());
        assertTrue(used.contains("org.springframework.boot:spring-boot-starter-data-mongodb"), used.toString());
        assertTrue(used.contains("io.jsonwebtoken:jjwt-api"), used.toString());
        assertTrue(SpringBoot.isSpringBoot(project(used)));
        assertTrue(SpringBoot.hasSpringLens(project(Set.of("com.mainul35:spring-lens-starter"))));
    }
}
