package com.smide.plugins.java.run;

import com.smide.api.Ide;
import com.smide.api.execution.BaseRunConfiguration;
import com.smide.api.execution.Forms;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.execution.RunConfigurationType;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.java.JavaProjectInfo;
import com.smide.plugins.java.JavaProjectRegistry;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a web application and serves it from a Tomcat the IDE starts and owns.
 *
 * <p>The install is left alone; each configuration deploys into its own
 * {@code CATALINA_BASE} under {@code ~/.smide/tomcat}. Debug puts a JDWP agent on
 * Tomcat's own command line, so breakpoints in a servlet, a filter or a Spring MVC
 * controller are hit the same way as in a plain application.
 */
public final class TomcatRunType implements RunConfigurationType {

    public static final String ID = "java.tomcat";

    private final Ide ide;
    private final JavaProjectRegistry registry;

    public TomcatRunType(Ide ide, JavaProjectRegistry registry) {
        this.ide = ide;
        this.registry = registry;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Tomcat Server";
    }

    @Override
    public String iconLiteral() {
        return "fth-server";
    }

    @Override
    public boolean supportsDebug() {
        return true;
    }

    @Override
    public RunConfiguration create(Workspace workspace) {
        Config c = new Config(workspace);
        c.set("port", "8080");
        c.set("context", "/");
        return c;
    }

    @Override
    public List<RunConfiguration> detect(Workspace workspace) {
        Optional<JavaProjectInfo> info = registry.get(workspace);
        if (info.isEmpty()) {
            return List.of();
        }
        List<RunConfiguration> out = new ArrayList<>();
        for (JavaProjectInfo.WebModule web : info.get().webModules()) {
            Config c = new Config(workspace);
            c.setName(web.name() + " (Tomcat)");
            c.set("module", Forms.relative(workspace.root(), web.dir()));
            c.set("port", "8080");
            // A war deployed at the root is what a single-application server usually wants;
            // the context can be changed and the URL follows it.
            c.set("context", "/");
            c.temporary();
            out.add(c);
        }
        return out;
    }

    @Override
    public Node editor(RunConfiguration configuration) {
        Config c = (Config) configuration;
        GridPane grid = Forms.grid();
        Forms.directory(grid, 0, "Tomcat home", c, "tomcatHome", ide);
        Forms.text(grid, 1, "Module (relative)", c, "module", "root module");
        Forms.text(grid, 2, "Context path", c, "context", "/");
        Forms.text(grid, 3, "HTTP port", c, "port", "8080");
        Forms.text(grid, 4, "VM options", c, "vmArgs", "-Xmx512m");
        Forms.text(grid, 5, "Artifact (optional)", c, "artifact", "target/app.war");
        Forms.text(grid, 6, "Environment (K=V;K=V)", c, "env", "SPRING_PROFILES_ACTIVE=dev");
        Forms.text(grid, 7, "Debug port", c, "debugPort", "5005");
        Forms.check(grid, 8, "Build the war before running", c, "build", true);
        Forms.check(grid, 9, "Open a browser when it is up", c, "browser", true);
        String found = Tomcat.home(ide).map(h -> {
            String version = Tomcat.version(h);
            return h + (version.isEmpty() ? "" : "  (" + version + ")");
        }).orElse("none found - set one above, or CATALINA_HOME");
        return new VBox(8, grid, Forms.note("Tomcat in use: " + found
                + ".\nThe install is not written to: the application is deployed into a private"
                + " CATALINA_BASE under ~/.smide/tomcat, so several configurations can share one Tomcat."));
    }

    private final class Config extends BaseRunConfiguration {
        Config(Workspace workspace) {
            super(TomcatRunType.this, workspace);
        }

        @Override
        public ProcessSpec prepare(Ide ide, ExecutionMode mode) throws Exception {
            Path home = home(ide);
            Path jdk = com.smide.plugins.java.JavaTools.launchJdk(ide, workspace, registry).home();
            Path module = moduleDir();
            int port = number(get("port", "8080"), 8080);

            if (flag("build", true)) {
                MavenLayout layout = MavenLayout.of(workspace.root(), module);
                List<String> build = layout.command(ide);
                build.add("-q");
                build.add("-B");
                build.add("-DskipTests");
                build.add("package");
                MavenBuild.run(ide, jdk, build, layout.directory(), "Building " + module.getFileName());
            }

            Path artifact = artifact(module);
            Path base = baseDir(ide);
            /* The shutdown port moves with the HTTP port, so two configurations on 8080
               and 8081 do not both try to listen on Tomcat's default 8005. */
            Tomcat.prepareBase(base, home, port, 8005 + (port - 8080));
            Tomcat.deploy(base, artifact, get("context", "/"));

            List<String> vmArgs = new ArrayList<>();
            if (mode == ExecutionMode.DEBUG) {
                vmArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:"
                        + get("debugPort", "5005"));
            }
            vmArgs.addAll(Forms.splitArgs(get("vmArgs", "")));
            List<String> cmd = Tomcat.command(jdk, home, base, vmArgs);

            String url = Tomcat.url(port, get("context", "/"));
            if (flag("browser", true)) {
                Tomcat.openWhenUp(ide, url, port, 120);
            }
            Map<String, String> env = new HashMap<>();
            env.put("JAVA_HOME", jdk.toString());
            env.put("CATALINA_HOME", home.toString());
            env.put("CATALINA_BASE", base.toString());
            // The configuration's own variables last, so they can override even these.
            env.putAll(Forms.environment(get("env", "")));
            ide.notifications().info("Tomcat", "Deploying " + artifact.getFileName() + " to " + url);
            return new ProcessSpec(name(), cmd, base, env);
        }

        private Path home(Ide ide) {
            String configured = get("tomcatHome", "");
            if (!configured.isBlank()) {
                Path path = Path.of(configured);
                if (!Tomcat.isTomcat(path)) {
                    throw new IllegalStateException(configured + " is not a Tomcat installation:"
                            + " it has no bin/bootstrap.jar and conf/server.xml.");
                }
                return path;
            }
            return Tomcat.home(ide).orElseThrow(() -> new IllegalStateException(
                    "No Tomcat found. Set one in Settings > Java > Tomcat home, in this"
                            + " configuration, or in the CATALINA_HOME environment variable."));
        }

        private Path artifact(Path module) {
            String configured = get("artifact", "");
            if (!configured.isBlank()) {
                Path path = Path.of(configured);
                Path resolved = path.isAbsolute() ? path : module.resolve(path);
                if (!Files.exists(resolved)) {
                    throw new IllegalStateException("No web application at " + resolved);
                }
                return resolved;
            }
            return Tomcat.artifact(module).orElseThrow(() -> new IllegalStateException(
                    "No .war under " + module.resolve("target") + ". Is this module packaged as a war?"
                            + " Build it once, or name the artifact in the configuration."));
        }

        /**
         * This configuration's own server directory, outside the project so that a
         * server's logs and work files never land in the repository.
         */
        private Path baseDir(Ide ide) {
            String safe = (workspace.name() + "-" + name()).replaceAll("[^A-Za-z0-9._-]", "-");
            return ide.downloads().toolsDir().getParent().resolve("tomcat").resolve(safe);
        }

        private int number(String text, int fallback) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
