package com.smide.plugins.docker;

import com.smide.api.Ide;
import com.smide.api.execution.CommandRunType;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.Forms;
import com.smide.api.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * docker compose up, docker build and docker run. A project with a compose file is offered
 * compose up, and one with a Dockerfile a build - no configuration written.
 */
public final class DockerRunType extends CommandRunType {

    private final Function<Ide, Optional<Path>> docker;

    public DockerRunType(Ide ide, Function<Ide, Optional<Path>> docker) {
        super(ide, "docker.run", "Docker", "mdi2d-docker");
        this.docker = docker;
    }

    @Override
    protected List<Field> fields() {
        return List.of(
                Field.choice("kind", "Kind", List.of("compose up", "build image", "run image")),
                Field.text("file", "Compose file or Dockerfile", "compose.yaml or Dockerfile, relative; empty for the default"),
                Field.text("image", "Image", "name:tag"),
                Field.text("ports", "Ports", "8080:8080 9090:9090"),
                Field.text("flags", "Extra flags", "--no-cache"));
    }

    @Override
    protected Map<String, String> defaults() {
        return Map.of("kind", "compose up");
    }

    @Override
    protected Command command(Config c, ExecutionMode mode) {
        Path binary = docker.apply(ide).orElseThrow(() -> new IllegalStateException(
                "docker was not found. Install Docker from " + DockerToolchain.DOWNLOAD
                        + ", or set its folder in Settings > Tools > Docker."));
        Path root = c.workspace().root();
        String file = c.get("file", "");
        List<String> flags = Forms.splitArgs(c.get("flags", ""));
        List<String> cmd = new ArrayList<>(List.of(binary.toString()));
        switch (c.get("kind", "compose up")) {
            case "build image" -> {
                cmd.addAll(List.of("build", "-t", image(c)));
                if (!file.isBlank()) {
                    cmd.addAll(List.of("-f", file));
                }
                cmd.addAll(flags);
                cmd.add(".");
            }
            case "run image" -> {
                cmd.addAll(List.of("run", "--rm"));
                for (String port : Forms.splitArgs(c.get("ports", ""))) {
                    cmd.addAll(List.of("-p", port));
                }
                cmd.addAll(flags);
                cmd.add(image(c));
            }
            default -> {
                cmd.add("compose");
                if (!file.isBlank()) {
                    cmd.addAll(List.of("-f", file));
                }
                cmd.addAll(List.of("up", "--build"));
                cmd.addAll(flags);
            }
        }
        return new Command(cmd, root);
    }

    /** The image named in the configuration, or one named after the project folder. */
    private static String image(Config c) {
        String image = c.get("image", "");
        if (!image.isBlank()) {
            return image;
        }
        String folder = c.workspace().root().getFileName().toString().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-._]+", "");
        return (folder.isEmpty() ? "app" : folder) + ":latest";
    }

    @Override
    protected List<Detected> find(Workspace workspace) {
        Path root = workspace.root();
        List<Detected> out = new ArrayList<>();
        for (String compose : DockerToolchain.COMPOSE_FILES) {
            if (Files.isRegularFile(root.resolve(compose))) {
                out.add(new Detected("docker compose up", Map.of("kind", "compose up", "file", compose)));
                break;
            }
        }
        if (Files.isRegularFile(root.resolve("Dockerfile"))) {
            out.add(new Detected("docker build", Map.of("kind", "build image")));
        }
        return out;
    }
}
