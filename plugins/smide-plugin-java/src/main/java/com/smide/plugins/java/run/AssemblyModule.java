package com.smide.plugins.java.run;

import com.smide.api.project.ProjectModel;
import com.smide.plugins.java.MavenImporter;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The module whose class path an application actually runs with.
 *
 * <p>Usually the module the main class is in, and then there is nothing to do here. Not
 * when the application is assembled somewhere else: smIDE's own main class is in
 * smide-core, which knows nothing of the sixteen plugin modules, so running it with
 * smide-core's class path gives a window with no languages, no version control and no
 * tool windows - an IDE with its features missing and no hint as to why.
 *
 * <p>What names the assembly is that it has no sources of its own and depends on the
 * module being run. A module with sources is somebody's library or application in its own
 * right and is left alone however many siblings it depends on; a module with none exists
 * only to put a set of them together, which is exactly the class path wanted. If two
 * modules fit that description the guess is dropped - being wrong here is worse than
 * being silent, because the reader can always set the field themselves.
 */
final class AssemblyModule {

    private AssemblyModule() {
    }

    /**
     * @param model     the Maven project, or null when there is none
     * @param mainClassModule the module directory the main class was found in
     * @return the module to take the class path from, empty when it is the obvious one
     */
    static Optional<Path> of(ProjectModel model, Path mainClassModule) {
        if (model == null || mainClassModule == null || model.modules().size() < 2) {
            return Optional.empty();
        }
        Optional<String> artifact = artifactIdOf(mainClassModule);
        if (artifact.isEmpty()) {
            return Optional.empty();
        }
        List<Path> assemblies = new ArrayList<>();
        for (ProjectModel.ProjectModule module : model.modules()) {
            if (module.root().equals(mainClassModule)
                    || !module.sourceRoots().isEmpty() || !module.testRoots().isEmpty()) {
                continue;
            }
            if (dependsOn(module.root(), artifact.get())) {
                assemblies.add(module.root());
            }
        }
        return assemblies.size() == 1 ? Optional.of(assemblies.get(0)) : Optional.empty();
    }

    private static Optional<String> artifactIdOf(Path moduleDir) {
        return read(moduleDir).map(Model::getArtifactId);
    }

    private static boolean dependsOn(Path moduleDir, String artifactId) {
        Optional<Model> model = read(moduleDir);
        if (model.isEmpty()) {
            return false;
        }
        for (Dependency dependency : model.get().getDependencies()) {
            if (artifactId.equals(dependency.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Model> read(Path moduleDir) {
        Path pom = moduleDir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MavenImporter.read(pom));
        } catch (IOException | RuntimeException e) {
            // An unreadable pom simply has nothing to say about the class path.
            return Optional.empty();
        }
    }
}
