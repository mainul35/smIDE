package com.smide.plugins.groovy;

import com.smide.api.lang.CompletionSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The words a Gradle build script is written in, offered while typing.
 *
 * <p>Gradle's build language is a fixed vocabulary - {@code plugins}, {@code dependencies},
 * {@code implementation}, {@code toolchain} - and the hard part of writing a build file is
 * remembering which word to use and what it means. That is what this offers, each with a line
 * saying what it does; a settings file gets its own short list, since the words there are
 * different ones.
 */
public final class GradleCompletion implements CompletionSource {

    /** Blocks and methods of a build script, with what each is for. */
    private static final Map<String, String> BUILD = new java.util.LinkedHashMap<>();
    /** The few that belong in settings.gradle. */
    private static final Map<String, String> SETTINGS = new java.util.LinkedHashMap<>();

    static {
        BUILD.put("plugins", "Block: the plugins this build applies, as id 'java' or id 'org.springframework.boot' version '3.3.4'.");
        BUILD.put("id", "Applies a plugin by its id, inside the plugins block.");
        BUILD.put("apply", "Applies a plugin the older way: apply plugin: 'java'.");
        BUILD.put("group", "The group of what this project publishes, as a Maven groupId.");
        BUILD.put("version", "The version of what this project publishes.");
        BUILD.put("description", "What this project is, in a sentence.");
        BUILD.put("repositories", "Block: where dependencies are fetched from - mavenCentral(), mavenLocal(), maven { url ... }.");
        BUILD.put("mavenCentral", "Fetches dependencies from Maven Central.");
        BUILD.put("mavenLocal", "Fetches dependencies from the local ~/.m2 repository.");
        BUILD.put("dependencies", "Block: what this project builds and runs against.");
        BUILD.put("implementation", "A dependency this project uses, kept off the compile classpath of whatever depends on it.");
        BUILD.put("api", "A dependency this project uses and exposes, on the compile classpath of whatever depends on it.");
        BUILD.put("compileOnly", "A dependency needed to compile and not at runtime - Lombok, an annotation library.");
        BUILD.put("runtimeOnly", "A dependency needed at runtime and not to compile - a JDBC driver.");
        BUILD.put("annotationProcessor", "An annotation processor run while compiling - Lombok, a mapper generator.");
        BUILD.put("testImplementation", "A dependency the tests use.");
        BUILD.put("testCompileOnly", "A dependency the tests compile against and do not run with.");
        BUILD.put("testRuntimeOnly", "A dependency the tests run with and do not compile against.");
        BUILD.put("testAnnotationProcessor", "An annotation processor run while compiling the tests.");
        BUILD.put("developmentOnly", "Spring Boot: a dependency for running locally, left out of the built archive.");
        BUILD.put("platform", "Takes the versions of a BOM: implementation platform('group:artifact:version').");
        BUILD.put("exclude", "Leaves a transitive dependency out: exclude group: '...', module: '...'.");
        BUILD.put("java", "Block: the Java of this build - its toolchain, source sets, sources and javadoc jars.");
        BUILD.put("toolchain", "Block: the JDK this project is built with, whatever the IDE runs on.");
        BUILD.put("languageVersion", "The Java release of the toolchain: JavaLanguageVersion.of(21).");
        BUILD.put("JavaLanguageVersion", "A Java release, for a toolchain: JavaLanguageVersion.of(21).");
        BUILD.put("sourceCompatibility", "The Java release the sources are written for, the older way.");
        BUILD.put("targetCompatibility", "The Java release the classes are built for, the older way.");
        BUILD.put("sourceSets", "Block: where the sources and resources of this project are.");
        BUILD.put("configurations", "Block: the dependency configurations of this project, to add to or change.");
        BUILD.put("tasks", "Block: the tasks of this build, to configure or to add to.");
        BUILD.put("test", "The test task: useJUnitPlatform(), test arguments, what to include.");
        BUILD.put("useJUnitPlatform", "Runs the tests with JUnit 5.");
        BUILD.put("jar", "The jar task: its name, its manifest, what goes in it.");
        BUILD.put("war", "The war task, for a project that builds a web archive.");
        BUILD.put("bootRun", "Spring Boot: the task that runs the application.");
        BUILD.put("bootJar", "Spring Boot: the task that builds the executable jar.");
        BUILD.put("bootWar", "Spring Boot: the task that builds the executable war.");
        BUILD.put("manifest", "Block: the entries of the jar's manifest, such as Main-Class.");
        BUILD.put("mainClass", "The class to run: mainClass = 'com.example.Application'.");
        BUILD.put("subprojects", "Block: applied to every module of this build.");
        BUILD.put("allprojects", "Block: applied to this project and every module of it.");
        BUILD.put("buildscript", "Block: what the build script itself needs to run - its own dependencies.");
        BUILD.put("ext", "Block: values of your own, read elsewhere in the build as project.ext or by name.");
        BUILD.put("wrapper", "The wrapper task: gradle wrapper writes gradlew and its properties.");
        BUILD.put("project", "This project: its name, its version, its properties.");
        BUILD.put("rootProject", "The project at the top of the build.");
        BUILD.put("file", "A file of this project, by a path relative to it.");
        BUILD.put("providers", "Gradle's providers: environment variables, system properties, files, read lazily.");
        BUILD.put("layout", "The build's folders: layout.buildDirectory, layout.projectDirectory.");

        SETTINGS.put("rootProject", "The project at the top: rootProject.name = 'my-build'.");
        SETTINGS.put("include", "Adds a module to the build: include 'app', 'library'.");
        SETTINGS.put("includeBuild", "Adds another build to this one, as a composite build.");
        SETTINGS.put("pluginManagement", "Block: where plugins are resolved from.");
        SETTINGS.put("dependencyResolutionManagement", "Block: repositories and version catalogues for every module.");
        SETTINGS.put("versionCatalogs", "Block: the version catalogue, which names the dependencies of this build.");
    }

    @Override
    public boolean handles(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gradle") || name.endsWith(".groovy") || name.equals("jenkinsfile");
    }

    @Override
    public List<Suggestion> suggest(Path file, String text, int offset) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, String> words = name.startsWith("settings.gradle") ? SETTINGS : BUILD;
        List<Suggestion> out = new ArrayList<>(words.size());
        words.forEach((word, what) -> out.add(new Suggestion(word, what, "keyword")));
        return out;
    }
}
