package com.smide.plugins.java.ui;

import com.smide.api.Ide;
import com.smide.api.execution.ConsoleHandle;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.ui.ToolWindowAnchor;
import com.smide.api.ui.ToolWindowFactory;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.java.JavaProjectInfo;
import com.smide.plugins.java.JavaProjectRegistry;
import com.smide.plugins.java.JavaTools;
import com.smide.plugins.java.deploy.Dockerfiles;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Everything between "it compiles" and "it is running somewhere else": package, run the
 * artifact, Docker image, installer, copy to a server, health check, Spring Lens.
 * Every field is remembered per workspace under {@code deploy.*}.
 */
public final class DeployToolWindow implements ToolWindowFactory {

    public static final String ID = "deploy";

    private final Ide ide;
    private final JavaProjectRegistry registry;
    private final VBox content = new VBox(14);
    private Workspace current;

    public DeployToolWindow(Ide ide, JavaProjectRegistry registry) {
        this.ide = ide;
        this.registry = registry;
        content.setPadding(new Insets(10, 12, 10, 12));
        ide.workspaces().addActiveListener(w -> {
            current = w.orElse(null);
            rebuild();
        });
        ide.events().subscribe(Events.ProjectImported.class, ev -> {
            if (ev.workspace() == current) {
                rebuild();
            }
        });
        current = ide.workspaces().active().orElse(null);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Deploy";
    }

    @Override
    public String iconLiteral() {
        return "fth-upload-cloud";
    }

    @Override
    public ToolWindowAnchor anchor() {
        return ToolWindowAnchor.RIGHT;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Node create(ToolWindowContext context) {
        rebuild();
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        return scroll;
    }

    // ------------------------------------------------------------------ build

    private void rebuild() {
        content.getChildren().clear();
        Optional<JavaProjectInfo> info = registry.get(current);
        if (current == null || info.isEmpty()) {
            Label hint = new Label("Open a Maven or Gradle project to package and deploy it.");
            hint.getStyleClass().add("empty-hint");
            hint.setWrapText(true);
            content.getChildren().add(hint);
            return;
        }
        JavaProjectInfo p = info.get();
        Workspace ws = current;
        content.getChildren().addAll(
                packageSection(ws, p),
                dockerSection(ws, p),
                installerSection(ws, p),
                serverSection(ws, p),
                healthSection(ws, p));
    }

    private static Label heading(String text) {
        Label label = new Label(text.toUpperCase());
        label.getStyleClass().add("settings-section");
        return label;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-note");
        label.setWrapText(true);
        return label;
    }

    private static Button button(String text, String icon, Runnable action) {
        Button b = new Button(text);
        if (icon != null) {
            b.setGraphic(new FontIcon(icon));
        }
        b.setOnAction(e -> action.run());
        return b;
    }

    private TextField field(Workspace ws, String key, String def, String prompt) {
        TextField f = new TextField(ws.settings().get(key, def));
        f.setPromptText(prompt);
        f.textProperty().addListener((o, a, b) -> ws.settings().set(key, b));
        HBox.setHgrow(f, Priority.ALWAYS);
        return f;
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setMinWidth(90);
        javafx.scene.layout.ColumnConstraints c2 = new javafx.scene.layout.ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(c1, c2);
        return g;
    }

    // ------------------------------------------------------------- sections

    private Node packageSection(Workspace ws, JavaProjectInfo p) {
        Button pack = button("Package", "fth-package", () -> runBuild(ws, p, p.isGradle() ? "assemble" : "package", true));
        Button clean = button("Clean & package", "fth-refresh-cw", () -> runBuild(ws, p, p.isGradle() ? "clean assemble" : "clean package", true));
        Button runJar = button("Run packaged app", "fth-play", () -> runArtifact(ws, p));
        Button reveal = button("Show artifacts", "fth-folder", () -> artifactDir(ws, p).ifPresent(d -> ide.window().revealInFileManager(d)));
        HBox row = new HBox(6, pack, clean, runJar, reveal);
        String artifact = artifacts(ws, p).stream().map(a -> a.getFileName().toString()).findFirst().orElse("no artifact built yet");
        return new VBox(6, heading("Package"), row,
                note((p.isGradle() ? "gradle assemble" : "mvn package -DskipTests") + " builds " + p.packaging()
                        + " artifacts under " + (p.isGradle() ? "build/libs" : "target") + ". Latest: " + artifact));
    }

    private Node dockerSection(Workspace ws, JavaProjectInfo p) {
        GridPane g = grid();
        TextField image = field(ws, "deploy.docker.image", p.artifactId().toLowerCase(), "image name");
        TextField tag = field(ws, "deploy.docker.tag", p.version().isBlank() ? "latest" : p.version(), "tag");
        TextField port = field(ws, "deploy.docker.port", "8080", "container port");
        g.add(new Label("Image"), 0, 0);
        g.add(image, 1, 0);
        g.add(new Label("Tag"), 0, 1);
        g.add(tag, 1, 1);
        g.add(new Label("Port"), 0, 2);
        g.add(port, 1, 2);
        boolean hasDockerfile = Files.exists(ws.root().resolve("Dockerfile"));
        Button generate = button(hasDockerfile ? "Open Dockerfile" : "Generate Dockerfile", "fth-file-plus", () -> {
            Path dockerfile = ws.root().resolve("Dockerfile");
            if (!Files.exists(dockerfile)) {
                try {
                    Files.writeString(dockerfile, Dockerfiles.forProject(p));
                    ide.notifications().info("Dockerfile generated", dockerfile.toString());
                } catch (IOException e) {
                    ide.notifications().error("Cannot write Dockerfile", e.getMessage());
                    return;
                }
            }
            ide.editors().open(dockerfile);
            rebuild();
        });
        Button build = button("Build image", "mdi2d-docker", () -> docker(ws, "docker build",
                List.of("docker", "build", "-t", image.getText() + ":" + tag.getText(), ".")));
        Button run = button("Run container", "fth-play", () -> docker(ws, "docker run",
                List.of("docker", "run", "--rm", "-p", port.getText() + ":" + port.getText(),
                        image.getText() + ":" + tag.getText())));
        Button push = button("Push", "fth-upload", () -> docker(ws, "docker push",
                List.of("docker", "push", image.getText() + ":" + tag.getText())));
        Button compose = button("Compose up", "fth-layers", () -> docker(ws, "docker compose up",
                List.of("docker", "compose", "up", "--build")));
        compose.setDisable(!Files.exists(ws.root().resolve("compose.yaml")) && !Files.exists(ws.root().resolve("docker-compose.yml"))
                && !Files.exists(ws.root().resolve("docker-compose.yaml")) && !Files.exists(ws.root().resolve("compose.yml")));
        HBox row = new HBox(6, generate, build, run, push, compose);
        String dockerNote = JavaTools.dockerAvailable() ? "Builds from the workspace root; package first so the jar exists."
                : "docker was not found on PATH.";
        return new VBox(6, heading("Docker"), g, row, note(dockerNote));
    }

    private Node installerSection(Workspace ws, JavaProjectInfo p) {
        GridPane g = grid();
        TextField appName = field(ws, "deploy.installer.name", p.artifactId(), "application name");
        TextField mainClass = field(ws, "deploy.installer.mainClass",
                p.mainClasses().isEmpty() ? "" : p.mainClasses().get(0).fqn(), "main class");
        ComboBox<String> type = new ComboBox<>();
        type.getItems().setAll("app-image", "exe", "msi", "deb", "rpm", "dmg", "pkg");
        type.setValue(ws.settings().get("deploy.installer.type", "app-image"));
        type.valueProperty().addListener((o, a, b) -> ws.settings().set("deploy.installer.type", b));
        g.add(new Label("Name"), 0, 0);
        g.add(appName, 1, 0);
        g.add(new Label("Main class"), 0, 1);
        g.add(mainClass, 1, 1);
        g.add(new Label("Type"), 0, 2);
        g.add(type, 1, 2);
        Button jpackage = button("Build with jpackage", "fth-download", () -> jpackage(ws, p, appName.getText(),
                mainClass.getText(), type.getValue()));
        boolean available = JavaTools.jdkTool(JavaTools.projectJdk(ide, ws, registry).home(), "jpackage").isPresent();
        jpackage.setDisable(!available);
        return new VBox(6, heading("Installer"), g, new HBox(6, jpackage),
                note(available ? "Packages the built jar and its dependencies with a bundled runtime into target/installer. "
                        + "exe/msi need WiX on Windows; app-image needs nothing."
                        : "jpackage was not found in the configured JDK."));
    }

    private Node serverSection(Workspace ws, JavaProjectInfo p) {
        GridPane g = grid();
        TextField host = field(ws, "deploy.ssh.host", "", "user@server");
        TextField dir = field(ws, "deploy.ssh.dir", "/opt/" + p.artifactId(), "remote directory");
        TextField restart = field(ws, "deploy.ssh.restart", "sudo systemctl restart " + p.artifactId(), "command after copy");
        g.add(new Label("Server"), 0, 0);
        g.add(host, 1, 0);
        g.add(new Label("Directory"), 0, 1);
        g.add(dir, 1, 1);
        g.add(new Label("Restart"), 0, 2);
        g.add(restart, 1, 2);
        Button deploy = button("Copy artifact and restart", "fth-server", () -> deployToServer(ws, p, host.getText(),
                dir.getText(), restart.getText()));
        Button shell = button("Open SSH session", "fth-terminal", () -> ide.execution().run(
                new ProcessSpec("ssh " + host.getText(), List.of("ssh", host.getText()), ws.root())));
        return new VBox(6, heading("Server"), g, new HBox(6, deploy, shell),
                note("Uses the system scp and ssh, so your keys and agent apply. The restart command runs on the server after the copy."));
    }

    private Node healthSection(Workspace ws, JavaProjectInfo p) {
        TextField url = field(ws, "deploy.health.url", "http://localhost:8080/actuator/health", "health URL");
        Button check = button("Check", "fth-activity", () -> healthCheck(url.getText()));
        Button open = button("Open in browser", "fth-external-link", () -> ide.window().browse(url.getText()));
        HBox row = new HBox(6, url, check, open);
        VBox box = new VBox(6, heading("Health"), row);
        if (p.springLens()) {
            TextField lens = field(ws, "deploy.lens.url", "http://localhost:8080/spring-lens", "Spring Lens URL");
            Button openLens = button("Open Spring Lens", "fth-eye", () -> ide.window().browse(lens.getText()));
            box.getChildren().addAll(new HBox(6, lens, openLens), note("Spring Lens is on this project's classpath."));
        } else if (p.springBoot()) {
            box.getChildren().add(note("Add spring-boot-starter-actuator for /actuator/health. Spring Lens (the user's observability tool) can be added as a dependency for runtime insight."));
        }
        return box;
    }

    // -------------------------------------------------------------- actions

    private void runBuild(Workspace ws, JavaProjectInfo p, String goals, boolean skipTests) {
        List<String> cmd = p.isGradle() ? JavaTools.gradle(ide, ws.root()) : JavaTools.maven(ide, ws.root());
        if (!p.isGradle()) {
            cmd.add("-B");
        }
        cmd.addAll(List.of(goals.split(" ")));
        if (skipTests) {
            if (p.isGradle()) {
                cmd.add("-x");
                cmd.add("test");
            } else {
                cmd.add("-DskipTests");
            }
        }
        ConsoleHandle h = ide.execution().run(new ProcessSpec(goals, cmd, ws.root(),
                JavaTools.environment(JavaTools.launchJdk(ide, ws, registry).home())));
        if (h != null) {
            h.exitCode().thenAccept(code -> ide.window().runLater(this::rebuild));
        }
    }

    private Optional<Path> artifactDir(Workspace ws, JavaProjectInfo p) {
        Path dir = ws.root().resolve(p.isGradle() ? "build/libs" : "target");
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

    private List<Path> artifacts(Workspace ws, JavaProjectInfo p) {
        List<Path> out = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        artifactDir(ws, p).ifPresent(dirs::add);
        for (var module : p.model().modules()) {
            Path d = module.root().resolve(p.isGradle() ? "build/libs" : "target");
            if (Files.isDirectory(d) && !dirs.contains(d)) {
                dirs.add(d);
            }
        }
        for (Path dir : dirs) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".jar") || f.toString().endsWith(".war"))
                        .filter(f -> !f.getFileName().toString().endsWith("-sources.jar")
                                && !f.getFileName().toString().endsWith("-javadoc.jar")
                                && !f.getFileName().toString().endsWith(".original"))
                        .sorted((a, b) -> Long.compare(size(b), size(a)))
                        .forEach(out::add);
            } catch (IOException ignored) {
                // No artifacts there.
            }
        }
        return out;
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private void runArtifact(Workspace ws, JavaProjectInfo p) {
        List<Path> jars = artifacts(ws, p);
        if (jars.isEmpty()) {
            ide.notifications().warn("Nothing to run", "Package the project first.");
            return;
        }
        Path jar = jars.get(0);
        ide.execution().run(new ProcessSpec("java -jar " + jar.getFileName(),
                List.of(JavaTools.javaExecutable(JavaTools.launchJdk(ide, ws, registry).home()), "-jar", jar.toString()),
                jar.getParent()));
    }

    private void docker(Workspace ws, String title, List<String> cmd) {
        if (!JavaTools.dockerAvailable()) {
            ide.notifications().error("Docker not found", "Install Docker Desktop or add docker to PATH.");
            return;
        }
        ide.execution().run(new ProcessSpec(title, cmd, ws.root()));
    }

    private void jpackage(Workspace ws, JavaProjectInfo p, String name, String mainClass, String type) {
        List<Path> jars = artifacts(ws, p);
        if (jars.isEmpty()) {
            ide.notifications().warn("Nothing to package", "Package the project first.");
            return;
        }
        if (mainClass == null || mainClass.isBlank()) {
            ide.notifications().warn("Main class required", "jpackage needs the main class to launch.");
            return;
        }
        Path jar = jars.get(0);
        Path input = jar.getParent();
        Path dest = ws.root().resolve(p.isGradle() ? "build/installer" : "target/installer");
        List<String> cmd = new ArrayList<>(List.of(
                JavaTools.jdkTool(JavaTools.launchJdk(ide, ws, registry).home(), "jpackage").orElse("jpackage"),
                "--input", input.toString(), "--main-jar", jar.getFileName().toString(), "--main-class", mainClass,
                "--name", name.isBlank() ? p.artifactId() : name, "--type", type, "--dest", dest.toString()));
        if (!p.version().isBlank() && p.version().matches("\\d+(\\.\\d+){0,2}")) {
            cmd.add("--app-version");
            cmd.add(p.version());
        }
        ide.execution().run(new ProcessSpec("jpackage " + type, cmd, ws.root()));
    }

    private void deployToServer(Workspace ws, JavaProjectInfo p, String host, String dir, String restart) {
        if (host == null || host.isBlank()) {
            ide.notifications().warn("Server required", "Enter user@host first.");
            return;
        }
        List<Path> jars = artifacts(ws, p);
        if (jars.isEmpty()) {
            ide.notifications().warn("Nothing to deploy", "Package the project first.");
            return;
        }
        Path jar = jars.get(0);
        String target = host + ":" + (dir.endsWith("/") ? dir : dir + "/");
        ConsoleHandle copy = ide.execution().run(new ProcessSpec("scp " + jar.getFileName(),
                List.of("scp", jar.toString(), target), ws.root()));
        if (copy == null) {
            return;
        }
        copy.exitCode().thenAccept(code -> ide.window().runLater(() -> {
            if (code != 0) {
                ide.notifications().error("Copy failed", "scp exited with " + code);
                return;
            }
            if (restart != null && !restart.isBlank()) {
                ide.execution().run(new ProcessSpec("ssh " + host + " restart", List.of("ssh", host, restart), ws.root()));
            } else {
                ide.notifications().info("Deployed", jar.getFileName() + " copied to " + target);
            }
        }));
    }

    private void healthCheck(String url) {
        ide.window().runInBackground(() -> {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                if (body.length() > 300) {
                    body = body.substring(0, 300) + "…";
                }
                if (response.statusCode() / 100 == 2) {
                    ide.notifications().info("Healthy (" + response.statusCode() + ")", body);
                } else {
                    ide.notifications().warn("HTTP " + response.statusCode(), body);
                }
            } catch (Exception e) {
                ide.notifications().error("Health check failed", e.getMessage());
            }
        });
    }
}
