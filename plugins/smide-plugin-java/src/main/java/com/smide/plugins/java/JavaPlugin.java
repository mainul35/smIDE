package com.smide.plugins.java;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.execution.ProcessSpec;
import com.smide.api.lang.FileType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.ui.StatusBar;
import com.smide.api.util.Events;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.java.run.ApplicationRunType;
import com.smide.plugins.java.run.BuildToolRunType;
import com.smide.plugins.java.run.JUnitRunType;
import com.smide.plugins.java.run.SpringBootRunType;
import com.smide.plugins.java.templates.MavenQuickstartTemplate;
import com.smide.plugins.java.templates.SpringBootTemplate;
import com.smide.plugins.java.ui.DeployToolWindow;
import com.smide.plugins.java.ui.JavaSettingsPage;
import com.smide.plugins.java.ui.MavenToolWindow;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Java as an smIDE plugin: language and JDT LS, Maven/Gradle import, run configurations,
 * the Maven and Deploy tool windows, project templates and the settings page.
 */
public final class JavaPlugin implements Plugin {

    private Ide ide;
    private final JavaProjectRegistry registry = new JavaProjectRegistry();
    private JdtLauncher jdt;
    private MavenToolWindow mavenWindow;

    @Override
    public void start(PluginContext context) {
        ide = context.ide();
        jdt = new JdtLauncher(ide);

        context.registerFileType(FileType.text("java", "Java source", "mdi2l-language-java", "java", "jav"));
        context.registerFileType(new FileType("class", "Java class file", Set.of("class"), Set.of(), "fth-cpu", true));
        context.registerFileType(new FileType("jar", "Java archive", Set.of("jar", "war", "ear"), Set.of(), "fth-archive", true));
        context.registerLanguage(new JavaLanguage(jdt));

        context.registerProjectImporter(new MavenImporter(registry));
        context.registerProjectImporter(new GradleImporter(registry));

        context.registerRunConfigurationType(new ApplicationRunType(ide, registry));
        context.registerRunConfigurationType(new SpringBootRunType(ide, registry));
        context.registerRunConfigurationType(new JUnitRunType(ide, registry));
        context.registerRunConfigurationType(new BuildToolRunType(ide, false));
        context.registerRunConfigurationType(new BuildToolRunType(ide, true));

        context.registerDebugger(new com.smide.plugins.java.debug.JavaDebugger());

        mavenWindow = new MavenToolWindow(ide, registry);
        context.registerToolWindow(mavenWindow);
        context.registerToolWindow(new DeployToolWindow(ide, registry));

        context.registerNewProjectTemplate(new MavenQuickstartTemplate());
        context.registerNewProjectTemplate(new SpringBootTemplate());
        context.registerSettingsPage(new JavaSettingsPage(ide, jdt, this::installJdt));

        registerActions(context);

        ide.workspaces().addClosedListener(registry::remove);
        // A saved build file means the model is stale.
        ide.events().subscribe(Events.FileSaved.class, ev -> {
            String name = ev.path().getFileName() == null ? "" : ev.path().getFileName().toString();
            if (name.equals("pom.xml") || name.startsWith("build.gradle") || name.startsWith("settings.gradle")) {
                ide.workspaces().containing(ev.path()).ifPresent(w -> ide.projects().reimport(w));
            }
        });
    }

    private void registerActions(PluginContext context) {
        context.registerAction(Action.of("java.build", "Build Project").menu("Build").shortcut("shortcut+F9")
                .icon("fth-tool").toolbar("build").order(10)
                .enabledWhen(ctx -> javaWorkspace(ctx.workspace()).isPresent())
                .perform(ctx -> ctx.workspace().ifPresent(w -> build(w, "compile", "classes", false))));
        context.registerAction(Action.of("java.rebuild", "Rebuild Project").menu("Build").order(11)
                .enabledWhen(ctx -> javaWorkspace(ctx.workspace()).isPresent())
                .perform(ctx -> ctx.workspace().ifPresent(w -> build(w, "clean compile", "clean classes", false))));
        context.registerAction(Action.of("java.package", "Package").menu("Build").order(12)
                .enabledWhen(ctx -> javaWorkspace(ctx.workspace()).isPresent())
                .perform(ctx -> ctx.workspace().ifPresent(w -> build(w, "package", "assemble", true))));
        context.registerAction(Action.of("java.test", "Run All Tests").menu("Build").order(13)
                .enabledWhen(ctx -> javaWorkspace(ctx.workspace()).isPresent())
                .perform(ctx -> ctx.workspace().ifPresent(w -> build(w, "test", "test", false))));
        context.registerAction(Action.of("java.clean", "Clean").menu("Build").order(14)
                .enabledWhen(ctx -> javaWorkspace(ctx.workspace()).isPresent())
                .perform(ctx -> ctx.workspace().ifPresent(w -> build(w, "clean", "clean", false))));
        context.registerAction(Action.of("java.reload", "Reload Project Model").menu("Build").icon("fth-refresh-cw")
                .order(100).enabledWhen(ctx -> ctx.workspace().isPresent())
                .perform(ctx -> ctx.workspace().ifPresent(w -> ide.projects().reimport(w))));
        context.registerAction(Action.of("java.goal", "Execute Maven Goal...").menu("Build").order(101)
                .enabledWhen(ctx -> javaWorkspace(ctx.workspace()).isPresent())
                .perform(ctx -> ide.window().prompt("Execute Maven Goal", "Command line", "clean install -DskipTests")
                        .ifPresent(mavenWindow::runGoal)));
        context.registerAction(Action.of("java.installJdt", "Install JDT Language Server").menu("Tools").order(200)
                .perform(ctx -> installJdt()));
        context.registerAction(Action.of("java.jdtLog", "Show JDT Language Server Log").menu("Tools").order(201)
                .perform(ctx -> {
                    Path log = ide.homeDir().resolve("logs").resolve("jdtls.log");
                    if (java.nio.file.Files.exists(log)) {
                        ide.editors().open(log);
                    } else {
                        ide.statusBar().message("No JDT LS log yet.");
                    }
                }));
    }

    private Optional<JavaProjectInfo> javaWorkspace(Optional<Workspace> workspace) {
        return workspace.flatMap(registry::get);
    }

    private void build(Workspace w, String mavenGoals, String gradleTasks, boolean skipTests) {
        JavaProjectInfo info = registry.get(w).orElse(null);
        boolean gradle = info != null && info.isGradle();
        List<String> cmd = gradle ? JavaTools.gradle(ide, w.root()) : JavaTools.maven(ide, w.root());
        if (!gradle) {
            cmd.add("-B");
        }
        cmd.addAll(List.of((gradle ? gradleTasks : mavenGoals).split(" ")));
        if (skipTests) {
            if (gradle) {
                cmd.add("-x");
                cmd.add("test");
            } else {
                cmd.add("-DskipTests");
            }
        }
        ide.execution().run(new ProcessSpec((gradle ? gradleTasks : mavenGoals) + " [" + w.name() + "]", cmd, w.root()));
    }

    private void installJdt() {
        StatusBar.Progress progress = ide.statusBar().progress("Installing JDT Language Server", false);
        ide.window().runInBackground(() -> {
            try {
                jdt.install(ide, progress::update);
                ide.notifications().info("JDT Language Server installed",
                        "Reopen a Java file to start it. Version: " + jdt.installedVersion());
            } catch (Exception e) {
                ide.notifications().error("JDT LS install failed", e.getMessage());
            } finally {
                progress.done();
            }
        });
    }

    @Override
    public void stop() {
        // Language server processes are owned by the core and stopped with it.
    }
}
