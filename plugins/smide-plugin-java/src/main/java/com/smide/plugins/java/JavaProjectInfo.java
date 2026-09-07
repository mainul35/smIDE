package com.smide.plugins.java;

import com.smide.api.project.ProjectModel;

import java.nio.file.Path;
import java.util.List;

/**
 * What the importer learned beyond the generic model: build tool, packaging, Spring Boot,
 * and the classes that can be run. Kept by the plugin per workspace root.
 *
 * @param buildTool   {@code maven} or {@code gradle}
 * @param springBoot  true when Spring Boot is on the build
 * @param springLens  true when the user's Spring Lens is on the build
 * @param mainClasses fully qualified names of classes with a {@code main} method, with their module root
 * @param testClasses fully qualified names of classes containing tests, with their module root
 * @param packaging   {@code jar}, {@code war}, {@code pom}
 * @param artifactId  the root artifact id
 * @param version     the root version
 * @param webModules  modules packaged as a war, which a servlet container can serve
 * @param profiles    Maven profile ids declared in the build
 * @param javaVersion the release the build compiles for, or 0
 */
public record JavaProjectInfo(String buildTool,
                              ProjectModel model,
                              boolean springBoot,
                              boolean springLens,
                              List<RunnableClass> mainClasses,
                              List<RunnableClass> testClasses,
                              String packaging,
                              String artifactId,
                              String version,
                              List<WebModule> webModules,
                              List<String> profiles,
                              int javaVersion) {

    /** A module that builds a war, and where it is. */
    public record WebModule(String name, Path dir) {
    }

    /** A class worth running, and the module it lives in. */
    public record RunnableClass(String fqn, Path moduleRoot, Path file) {
        public String simpleName() {
            int dot = fqn.lastIndexOf('.');
            return dot < 0 ? fqn : fqn.substring(dot + 1);
        }
    }

    public boolean isMaven() {
        return "maven".equals(buildTool);
    }

    public boolean isGradle() {
        return "gradle".equals(buildTool);
    }
}
