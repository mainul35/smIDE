package com.smide.plugins.java.templates;

import com.smide.api.Ide;
import com.smide.api.project.NewProjectTemplate;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** A Maven project with one main class, one JUnit 5 test and a Java 21 compiler setting. */
public final class MavenQuickstartTemplate implements NewProjectTemplate {

    @Override
    public String id() {
        return "java.maven";
    }

    @Override
    public String displayName() {
        return "Java (Maven)";
    }

    @Override
    public String description() {
        return "A plain Maven project: pom.xml, a main class and a JUnit 5 test, ready to run.";
    }

    @Override
    public String iconLiteral() {
        return "mdi2l-language-java";
    }

    @Override
    public Node form(Map<String, String> values) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        TextField group = new TextField("com.example");
        TextField pkg = new TextField("com.example.app");
        TextField java = new TextField("21");
        CheckBox tests = new CheckBox("JUnit 5 test");
        tests.setSelected(true);
        values.put("groupId", group.getText());
        values.put("package", pkg.getText());
        values.put("java", java.getText());
        values.put("tests", "true");
        group.textProperty().addListener((o, a, b) -> values.put("groupId", b));
        pkg.textProperty().addListener((o, a, b) -> values.put("package", b));
        java.textProperty().addListener((o, a, b) -> values.put("java", b));
        tests.selectedProperty().addListener((o, a, b) -> values.put("tests", String.valueOf(b)));
        grid.addRow(0, new Label("Group id"), group);
        grid.addRow(1, new Label("Package"), pkg);
        grid.addRow(2, new Label("Java version"), java);
        grid.add(tests, 1, 3);
        return grid;
    }

    @Override
    public void generate(Ide ide, Path location, String name, Map<String, String> values) throws Exception {
        String groupId = values.getOrDefault("groupId", "com.example");
        String pkg = values.getOrDefault("package", groupId + "." + name.replaceAll("[^A-Za-z0-9]", "").toLowerCase());
        String java = values.getOrDefault("java", "21");
        boolean tests = Boolean.parseBoolean(values.getOrDefault("tests", "true"));
        Path pkgDir = location.resolve("src/main/java").resolve(pkg.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Files.createDirectories(location.resolve("src/main/resources"));
        Files.writeString(location.resolve("pom.xml"), pom(groupId, name, java, tests));
        Files.writeString(pkgDir.resolve("App.java"), """
                package %s;

                public class App {

                    public static void main(String[] args) {
                        System.out.println("Hello from %s");
                    }
                }
                """.formatted(pkg, name));
        if (tests) {
            Path testDir = location.resolve("src/test/java").resolve(pkg.replace('.', '/'));
            Files.createDirectories(testDir);
            Files.writeString(testDir.resolve("AppTest.java"), """
                    package %s;

                    import org.junit.jupiter.api.Test;

                    import static org.junit.jupiter.api.Assertions.assertTrue;

                    class AppTest {

                        @Test
                        void runs() {
                            assertTrue(true);
                        }
                    }
                    """.formatted(pkg));
        }
        Files.writeString(location.resolve(".gitignore"), "target/\n.idea/\n*.iml\n.smide/\n");
        Files.writeString(location.resolve("README.md"), "# " + name + "\n\n```bash\nmvn package\njava -cp target/classes " + pkg + ".App\n```\n");
    }

    private static String pom(String groupId, String artifactId, String java, boolean tests) {
        String testDep = tests ? """
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.4</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                """ : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.1.0-SNAPSHOT</version>
                    <packaging>jar</packaging>

                    <properties>
                        <maven.compiler.release>%s</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                %s
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(groupId, artifactId, java, testDep);
    }
}
