package com.smide.plugins.springboot;

import com.smide.api.project.ProjectModel;
import com.smide.plugins.java.GradleImporter;
import com.smide.plugins.java.JavaProjectInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether a project is a Spring Boot one, from what its build uses. */
class SpringBootTest {

    private static JavaProjectInfo project(Set<String> artifacts) {
        ProjectModel model = new ProjectModel("maven", "p", Path.of("p"), List.of(), List.of());
        return new JavaProjectInfo("maven", model, artifacts, List.of(), List.of(), "jar", "p", "1", List.of(), List.of(), 21);
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
