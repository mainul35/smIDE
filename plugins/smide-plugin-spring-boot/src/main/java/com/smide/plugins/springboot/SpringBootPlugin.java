package com.smide.plugins.springboot;

import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.plugins.java.GradleImporter;
import com.smide.plugins.java.JavaPlugin;
import com.smide.plugins.java.MavenImporter;
import com.smide.plugins.java.debug.JavaDebugger;
import com.smide.plugins.java.ui.DeployToolWindow;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Spring Boot, built on the Java plugin: the Spring Boot run configuration (spring-boot:run or
 * bootRun, with profiles, arguments and debugging, offered first for a Spring application), a
 * new Spring Boot project from start.spring.io, the spring-boot-maven-plugin's goals in the
 * Maven tool window and bootRun and bootJar for a Gradle build, and in Deploy, the actuator health check's and Spring Lens's notes.
 */
public final class SpringBootPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        context.registerRunConfigurationType(new SpringBootRunType(context.ide(), JavaPlugin.projects()));
        // Run with the JDWP agent, suspended, as an application is: the Java debugger attaches the same way.
        JavaDebugger.alsoDebug(SpringBootRunType.ID);
        context.registerNewProjectTemplate(new SpringBootTemplate());
        // spring-boot:run and the rest in the Maven tool window, for a pom that uses the plugin.
        MavenImporter.addKnownGoals("spring-boot-maven-plugin", List.of("run", "build-image", "repackage"));
        GradleImporter.addTasksFor("plugin:org.springframework.boot", List.of("bootRun", "bootJar"));
        DeployToolWindow.addHealthExtra((window, workspace, project) -> {
            if (SpringBoot.hasSpringLens(project)) {
                TextField lens = window.field(workspace, "deploy.lens.url", "http://localhost:8080/spring-lens", "Spring Lens URL");
                Button open = DeployToolWindow.button("Open Spring Lens", "fth-eye",
                        () -> context.ide().window().browse(lens.getText()));
                return new VBox(6, new HBox(6, lens, open), DeployToolWindow.note("Spring Lens is on this project's classpath."));
            }
            if (SpringBoot.isSpringBoot(project)) {
                return DeployToolWindow.note("Add spring-boot-starter-actuator for /actuator/health. Spring Lens (the user's"
                        + " observability tool) can be added as a dependency for runtime insight.");
            }
            return null;
        });
    }
}
