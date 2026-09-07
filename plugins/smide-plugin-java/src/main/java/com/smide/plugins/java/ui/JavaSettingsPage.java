package com.smide.plugins.java.ui;

import com.smide.api.Ide;
import com.smide.api.settings.SettingsPage;
import com.smide.plugins.java.JavaTools;
import com.smide.plugins.java.JdtLauncher;
import com.smide.plugins.java.run.Tomcat;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

/** Settings → Languages → Java: JDK, Maven, and the language server. */
public final class JavaSettingsPage implements SettingsPage {

    private final Ide ide;
    private final JdtLauncher jdt;
    private final Runnable installJdt;

    public JavaSettingsPage(Ide ide, JdtLauncher jdt, Runnable installJdt) {
        this.ide = ide;
        this.jdt = jdt;
        this.installJdt = installJdt;
    }

    @Override
    public String path() {
        return "Languages/Java";
    }

    @Override
    public String keywords() {
        return "java jdk maven gradle jdt language server";
    }

    @Override
    public Node create(SettingsEditor editor) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setMinWidth(120);
        javafx.scene.layout.ColumnConstraints c2 = new javafx.scene.layout.ColumnConstraints();
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

        grid.addRow(0, new Label("JDK home"), jdkRow);
        grid.addRow(1, new Label("Maven home"), maven);
        grid.add(wrapper, 1, 2);
        grid.addRow(3, new Label("Tomcat home"), tomcatRow);
        grid.addRow(4, new Label("JDT LS JVM args"), jvmArgs);
        grid.add(new HBox(8, status, install), 1, 5);

        Label note = new Label("The JDK runs applications, tests, Maven and the language server. "
                + "JDT LS needs Java 21 or newer and about 50 MB; it is stored under " + ide.downloads().toolsDir() + ".");
        note.getStyleClass().add("settings-note");
        note.setWrapText(true);
        return new VBox(10, grid, note);
    }
}
