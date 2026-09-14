package com.smide.plugins.docker;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Docker: the docker command, which needs a running engine to do anything. */
public final class DockerToolchain implements Toolchain {

    public static final String HOME_SETTING = "docker.home";
    public static final String DOWNLOAD = "https://docs.docker.com/get-docker/";
    static final List<String> COMPOSE_FILES = List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");

    @Override
    public String id() {
        return "docker";
    }

    @Override
    public String displayName() {
        return "Docker";
    }

    @Override
    public String purpose() {
        return "Building images and running containers needs the docker command and a running Docker engine.";
    }

    @Override
    public String downloadUrl() {
        return DOWNLOAD;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    @Override
    public boolean isNeededBy(Path root) {
        if (Files.isRegularFile(root.resolve("Dockerfile")) || Files.isRegularFile(root.resolve("Containerfile"))) {
            return true;
        }
        return COMPOSE_FILES.stream().anyMatch(name -> Files.isRegularFile(root.resolve(name)));
    }

    @Override
    public Optional<Path> locate(Ide ide) {
        List<Path> usual = new ArrayList<>(Executables.programFiles("Docker\\Docker\\resources"));
        usual.add(Path.of("/usr/local"));
        usual.add(Path.of("/opt/homebrew"));
        usual.add(Path.of("/usr"));
        usual.add(Path.of("/Applications/Docker.app/Contents/Resources"));
        return Executables.find(ide, HOME_SETTING, "docker", usual, p -> true);
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, "docker").isPresent();
    }
}
