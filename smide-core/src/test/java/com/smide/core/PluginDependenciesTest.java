package com.smide.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugins in folders of their own, one built on another - as Spring Boot's is on Java's: the one
 * that depends can use the other's classes, starts after it, and does not start without it.
 */
class PluginDependenciesTest {

    @TempDir
    Path temp;

    private PluginManager manager;

    @AfterEach
    void clear() throws IOException {
        System.clearProperty("smide.test.b");
        // Windows keeps a jar locked while a loader has it open.
        for (PluginManager.LoadedPlugin p : manager.loaded()) {
            if (p.loader() instanceof java.net.URLClassLoader loader) {
                loader.close();
            }
        }
    }

    /** Compiles sources into a jar in {@code plugins/<name>/}, with a descriptor. */
    private void plugin(Path plugins, String name, Map<String, String> sources, String descriptor, Path... classpath)
            throws IOException {
        Path src = Files.createDirectories(temp.resolve("src-" + name));
        Path classes = Files.createDirectories(temp.resolve("classes-" + name));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-cp",
                System.getProperty("java.class.path") + java.io.File.pathSeparator
                        + String.join(java.io.File.pathSeparator, Stream.of(classpath).map(Path::toString).toList())));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "compiles");
        Path dir = Files.createDirectories(plugins.resolve(name));
        try (OutputStream out = Files.newOutputStream(dir.resolve(name + ".jar"));
             JarOutputStream jar = new JarOutputStream(out)) {
            try (Stream<Path> walk = Files.walk(classes)) {
                for (Path f : walk.filter(Files::isRegularFile).toList()) {
                    jar.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                    jar.write(Files.readAllBytes(f));
                    jar.closeEntry();
                }
            }
            jar.putNextEntry(new JarEntry("META-INF/smide-plugin.properties"));
            jar.write(descriptor.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        // Where the next plugin compiles against this one.
        Files.createDirectories(temp.resolve("cp"));
        Files.copy(dir.resolve(name + ".jar"), temp.resolve("cp").resolve(name + ".jar"));
    }

    private Path twoPlugins() throws IOException {
        Path plugins = Files.createDirectories(temp.resolve("plugins"));
        plugin(plugins, "a", Map.of(
                "a/Base.java", "package a; public class Base { public static String hello() { return \"from a\"; } }",
                "a/APlugin.java", "package a; public class APlugin implements com.smide.api.plugin.Plugin {"
                        + " public void start(com.smide.api.plugin.PluginContext c) { } }"),
                "id=test.a\nname=A\nmainClass=a.APlugin\ndepends=\n");
        // Compiled against a's jar and naming it in depends, as Spring Boot's plugin does Java's.
        plugin(plugins, "b", Map.of(
                "b/BPlugin.java", "package b; public class BPlugin implements com.smide.api.plugin.Plugin {"
                        + " public void start(com.smide.api.plugin.PluginContext c) {"
                        + " System.setProperty(\"smide.test.b\", a.Base.hello()); } }"),
                "id=test.b\nname=B\nmainClass=b.BPlugin\ndepends=test.a\n", temp.resolve("cp").resolve("a.jar"));
        return plugins;
    }

    @Test
    void aPluginUsesTheClassesOfThePluginItDependsOn() throws IOException {
        manager = new PluginManager(null, new ExtensionRegistry());
        manager.startAll(twoPlugins(), Set.of());
        for (PluginManager.LoadedPlugin p : manager.loaded()) {
            assertNull(p.error(), p.descriptor().id() + " started");
        }
        assertEquals("from a", System.getProperty("smide.test.b"));
    }

    @Test
    void notWhenThatPluginIsDisabled() throws IOException {
        manager = new PluginManager(null, new ExtensionRegistry());
        manager.startAll(twoPlugins(), Set.of("test.a"));
        PluginManager.LoadedPlugin b = manager.loaded().stream()
                .filter(p -> p.descriptor().id().equals("test.b")).findFirst().orElseThrow();
        assertTrue(b.error().contains("disabled"), b.error());
        assertNull(System.getProperty("smide.test.b"));
    }
}
