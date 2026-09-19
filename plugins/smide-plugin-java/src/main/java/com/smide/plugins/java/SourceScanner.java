package com.smide.plugins.java;

import com.smide.api.project.ProjectModel;
import com.smide.plugins.java.JavaProjectInfo.RunnableClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds main classes and test classes by reading sources, without a compiler: the
 * class name comes from the file, the package from its declaration.
 */
final class SourceScanner {

    private static final Pattern MAIN = Pattern.compile(
            "public\\s+static\\s+void\\s+main\\s*\\(|static\\s+public\\s+void\\s+main\\s*\\(|(?m)^\\s*void\\s+main\\s*\\(\\s*\\)");
    private static final Pattern TEST = Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final int MAX_FILES = 20_000;

    private SourceScanner() {
    }

    record Result(List<RunnableClass> mains, List<RunnableClass> tests) {
    }

    static Result scan(ProjectModel model) {
        List<RunnableClass> mains = new ArrayList<>();
        List<RunnableClass> tests = new ArrayList<>();
        int[] budget = {MAX_FILES};
        for (ProjectModel.ProjectModule module : model.modules()) {
            for (Path root : module.sourceRoots()) {
                scanRoot(root, module.root(), mains, null, budget);
            }
            for (Path root : module.testRoots()) {
                scanRoot(root, module.root(), mains, tests, budget);
            }
        }
        return new Result(mains, tests);
    }

    private static void scanRoot(Path root, Path moduleRoot, List<RunnableClass> mains, List<RunnableClass> tests,
                                 int[] budget) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (budget[0]-- <= 0) {
                    return;
                }
                classify(file, moduleRoot, mains, tests);
            }
        } catch (IOException | RuntimeException ignored) {
            // A root that cannot be read has nothing to run.
        }
    }

    private static void classify(Path file, Path moduleRoot, List<RunnableClass> mains, List<RunnableClass> tests) {
        String text;
        try {
            if (Files.size(file) > 1_000_000) {
                return;
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return;
        }
        boolean isMain = MAIN.matcher(text).find();
        boolean isTest = tests != null && TEST.matcher(text).find();
        if (!isMain && !isTest) {
            return;
        }
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ".java".length());
        Matcher pkg = PACKAGE.matcher(text);
        String fqn = pkg.find() ? pkg.group(1) + "." + name : name;
        RunnableClass rc = new RunnableClass(fqn, moduleRoot, file);
        if (isMain) {
            mains.add(rc);
        }
        if (isTest) {
            tests.add(rc);
        }
    }
}
