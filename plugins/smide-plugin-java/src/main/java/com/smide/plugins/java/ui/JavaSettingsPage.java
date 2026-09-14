package com.smide.plugins.java.ui;

import com.smide.api.Ide;
import com.smide.api.settings.SettingsPage;
import com.smide.api.workspace.Workspace;
import com.smide.plugins.java.JavaProjectRegistry;
import com.smide.plugins.java.JavaTools;
import com.smide.plugins.java.JdtLauncher;
import com.smide.plugins.java.run.Tomcat;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Settings → Languages → Java: the JDKs, Maven, and the language server. */
public final class JavaSettingsPage implements SettingsPage {

    private final Ide ide;
    private final JdtLauncher jdt;
    private final JavaProjectRegistry registry;
    private final Runnable installJdt;

    public JavaSettingsPage(Ide ide, JdtLauncher jdt, JavaProjectRegistry registry, Runnable installJdt) {
        this.ide = ide;
        this.jdt = jdt;
        this.registry = registry;
        this.installJdt = installJdt;
    }

    @Override
    public String path() {
        return "Languages/Java";
    }

    @Override
    public String keywords() {
        return "java jdk sdk version project maven gradle jdt language server";
    }

    @Override
    public Node create(SettingsEditor editor) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(120);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2);

        TextField jdk = new TextField(editor.staged().get(JavaTools.JDK_HOME, ""));
        jdk.setPromptText(JavaTools.jdkHome(ide).toString() + "  (detected)");
        jdk.textProperty().addListener((o, a, b) -> editor.staged().set(JavaTools.JDK_HOME, b));
        Button browseJdk = new Button("...");
        browseJdk.setOnAction(e -> ide.window().chooseDirectory("JDK home", Path.of(System.getProperty("java.home")))
                .ifPresent(p -> jdk.setText(p.toString())));
        HBox jdkRow = new HBox(4, jdk, browseJdk);
        HBox.setHgrow(jdk, Priority.ALWAYS);

        TextField maven = new TextField(editor.staged().get(JavaTools.MAVEN_HOME, ""));
        maven.setPromptText("mvn on PATH" + (JavaTools.onPath("mvn") ? "  (found)" : "  (not found)"));
        maven.textProperty().addListener((o, a, b) -> editor.staged().set(JavaTools.MAVEN_HOME, b));
        CheckBox wrapper = new CheckBox("Prefer the project's mvnw / gradlew wrapper when present");
        wrapper.setSelected(editor.staged().getBoolean(JavaTools.PREFER_WRAPPER, true));
        wrapper.selectedProperty().addListener((o, a, b) -> editor.staged().setBoolean(JavaTools.PREFER_WRAPPER, b));

        TextField tomcat = new TextField(editor.staged().get(Tomcat.HOME_SETTING, ""));
        tomcat.setPromptText(Tomcat.home(ide)
                .map(h -> h + "  (detected)")
                .orElse("no Tomcat found - needed only for war projects"));
        tomcat.textProperty().addListener((o, a, b) -> editor.staged().set(Tomcat.HOME_SETTING, b));
        Button browseTomcat = new Button("...");
        browseTomcat.setOnAction(e -> ide.window()
                .chooseDirectory("Tomcat home", Tomcat.home(ide).orElse(Path.of(System.getProperty("user.home"))))
                .ifPresent(p -> tomcat.setText(p.toString())));
        HBox tomcatRow = new HBox(4, tomcat, browseTomcat);
        HBox.setHgrow(tomcat, Priority.ALWAYS);

        TextField jvmArgs = new TextField(editor.staged().get(JdtLauncher.JVM_ARGS_KEY, "-Xmx1G"));
        jvmArgs.textProperty().addListener((o, a, b) -> editor.staged().set(JdtLauncher.JVM_ARGS_KEY, b));
        Label status = new Label("JDT Language Server: " + jdt.installedVersion());
        Button install = new Button(jdt.isInstalled(ide) ? "Update to latest" : "Install");
        install.setOnAction(e -> installJdt.run());

        grid.addRow(0, new Label("Default JDK"), jdkRow);
        grid.addRow(1, new Label("Maven home"), maven);
        grid.add(wrapper, 1, 2);
        grid.addRow(3, new Label("Tomcat home"), tomcatRow);
        grid.addRow(4, new Label("JDT LS JVM args"), jvmArgs);
        grid.add(new HBox(8, status, install), 1, 5);

        Label note = note("The default JDK builds and runs every project it is new enough for; a project can have"
                + " its own below. The language server runs on the newest JDK 21 or later found, whatever the"
                + " projects use, and takes about 50 MB under " + ide.downloads().toolsDir() + ".");
        return new VBox(10, grid, note, projectJdks(editor));
    }

    /** One entry in a project's JDK list: a JDK, or null for Automatic. */
    private record Choice(String label, Path home) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One row per open project: the JDK it builds and runs with, and the release it asks for.
     *
     * <p>Written to the project's own settings on OK, beside its run configurations, because
     * the JDK belongs to the project - one machine can build a Java 17 service and a Java 25
     * one - and Automatic, which writes nothing, is what most projects want. A choice that
     * cannot build the project says so under it before anything is saved.
     */
    private Node projectJdks(SettingsEditor editor) {
        Label heading = new Label("Project JDKs");
        heading.setStyle("-fx-font-weight: bold;");
        List<Workspace> open = ide.workspaces().all();
        if (open.isEmpty()) {
            return new VBox(6, heading, note("Open a project to choose the JDK it builds and runs with."));
        }
        List<JavaTools.Jdk> installed = JavaTools.jdks(ide);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(4);
        ColumnConstraints name = new ColumnConstraints();
        name.setMinWidth(120);
        ColumnConstraints choice = new ColumnConstraints();
        choice.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(name, choice, new ColumnConstraints());

        Map<Workspace, ChoiceBox<Choice>> boxes = new LinkedHashMap<>();
        int row = 0;
        for (Workspace workspace : open) {
            int requested = registry.requestedRelease(workspace);
            JavaTools.ProjectJdk automatic = JavaTools.jdkFor(ide, null, requested);
            ChoiceBox<Choice> box = new ChoiceBox<>();
            box.setMaxWidth(Double.MAX_VALUE);
            Choice auto = new Choice("Automatic - " + describe(automatic), null);
            box.getItems().add(auto);
            for (JavaTools.Jdk jdk : installed) {
                box.getItems().add(new Choice(jdk.label(), jdk.home()));
            }
            box.setValue(current(workspace, box, auto));

            Label asks = new Label(requested > 0 ? "builds for Java " + requested : "release not declared");
            asks.getStyleClass().add("settings-note");
            Label problem = new Label();
            problem.getStyleClass().add("error-text");
            problem.setWrapText(true);
            Runnable judge = () -> {
                Choice picked = box.getValue();
                JavaTools.ProjectJdk chosen = picked == null || picked.home() == null
                        ? automatic : JavaTools.jdkFor(ide, picked.home(), requested);
                problem.setText(chosen.problem() == null ? "" : chosen.problem());
                problem.setVisible(chosen.problem() != null);
                problem.setManaged(chosen.problem() != null);
            };
            box.valueProperty().addListener((o, was, now) -> judge.run());
            judge.run();

            grid.add(new Label(workspace.name()), 0, row);
            grid.add(box, 1, row);
            grid.add(asks, 2, row);
            grid.add(problem, 1, row + 1, 2, 1);
            row += 2;
            boxes.put(workspace, box);
        }
        editor.onApply(() -> boxes.forEach((workspace, box) -> {
            Choice picked = box.getValue();
            if (picked == null || picked.home() == null) {
                workspace.settings().remove(JavaTools.JDK_HOME);
            } else {
                workspace.settings().set(JavaTools.JDK_HOME, picked.home().toString());
            }
        }));
        return new VBox(6, heading, grid, note("Automatic uses the default JDK when it is new enough for the"
                + " release the build asks for, otherwise the oldest JDK found that is. Runs, tests, Maven and"
                + " Gradle use a change straight away; the language server the next time it starts for that project."));
    }

    /** The entry matching what the project has set, added to the list when it is not one of the JDKs found. */
    private static Choice current(Workspace workspace, ChoiceBox<Choice> box, Choice auto) {
        String setting = workspace.settings().get(JavaTools.JDK_HOME, "");
        if (setting.isBlank()) {
            return auto;
        }
        Path path = Path.of(setting);
        for (Choice c : box.getItems()) {
            if (c.home() != null && same(c.home(), path)) {
                return c;
            }
        }
        Choice other = new Choice("Set for this project   " + setting, path);
        box.getItems().add(other);
        return other;
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (java.io.IOException | RuntimeException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static String describe(JavaTools.ProjectJdk jdk) {
        return (jdk.version() > 0 ? "JDK " + jdk.version() : "JDK") + "   " + jdk.home();
    }

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("settings-note");
        note.setWrapText(true);
        return note;
    }
}
