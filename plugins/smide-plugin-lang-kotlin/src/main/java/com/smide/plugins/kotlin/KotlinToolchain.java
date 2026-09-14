package com.smide.plugins.kotlin;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Kotlin command-line compiler, for Kotlin outside a build tool. A Gradle or Maven project
 * compiles Kotlin through its build and needs no kotlinc, so it is not asked for there.
 */
public final class KotlinToolchain implements Toolchain {

    public static final String HOME_SETTING = "kotlin.home";
    public static final String DOWNLOAD = "https://kotlinlang.org/docs/command-line.html";
    static final List<String> BUILD_FILES =
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pom.xml");

    @Override
    public String id() {
        return "kotlin";
    }

    @Override
    public String displayName() {
        return "Kotlin compiler";
    }

    @Override
    public String purpose() {
        return "Running Kotlin scripts and files outside Gradle or Maven needs kotlinc, and a JDK for it to run on."
                + " Gradle and Maven projects compile Kotlin themselves.";
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
        return !builtByTool(root) && ProjectFiles.any(root, 3, KotlinToolchain::isLooseKotlin);
    }

    @Override
    public Optional<Path> locate(Ide ide) {
        List<Path> usual = new ArrayList<>();
        Path kotlinHome = Executables.env("KOTLIN_HOME");
        if (kotlinHome != null) {
            usual.add(kotlinHome);
        }
        usual.add(Executables.home().resolve(".sdkman").resolve("candidates").resolve("kotlin").resolve("current"));
        usual.addAll(Executables.programFiles("kotlinc"));
        usual.add(Path.of("C:\\kotlinc"));
        usual.add(Path.of("/usr/local"));
        usual.add(Path.of("/opt/homebrew"));
        usual.add(Path.of("/usr"));
        return Executables.find(ide, HOME_SETTING, "kotlinc", usual, p -> true);
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, "kotlinc").isPresent();
    }

    /** The kotlin runner beside kotlinc, which runs the jars kotlinc makes. */
    public static Optional<Path> runner(Path kotlinc) {
        return Executables.in(kotlinc.getParent(), "kotlin");
    }

    static boolean builtByTool(Path root) {
        return BUILD_FILES.stream().anyMatch(name -> Files.isRegularFile(root.resolve(name)));
    }

    /** A Kotlin source or script that is not a Gradle build script. */
    static boolean isLooseKotlin(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".kt") || (name.endsWith(".kts") && !name.endsWith(".gradle.kts"));
    }
}
