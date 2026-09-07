package com.smide.plugins.assistant;

import com.mdviewer.ai.ProjectIndex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The file being reviewed, and the parts of the project it is connected to.
 *
 * <p>A file on its own does not say much about whether it is safe: the query that
 * concatenates a parameter is a smell in isolation and a vulnerability once you can see
 * that a controller hands it a request parameter. So the file goes to the model with the
 * files it calls and the files that call it, chosen by name and ranked, inside a character
 * budget that comes from the model's context window rather than from optimism.
 *
 * <p>Two directions, both cheap and both approximate. Downward: a file whose declared type
 * shares a name with something this file mentions. Upward: a file that mentions something
 * this one declares. That finds the collaborators of ordinary code without a compiler, an
 * index or a language server, and says plainly in the prompt that it is a selection rather
 * than the whole project.
 */
final class CodeContext {

    /**
     * One file that went into the prompt.
     *
     * <p>The path is kept, not just the text of the line: the panel lists these and the
     * reader will want to open one, and a listing you cannot get from to the file is a
     * listing you have to retype into Go to File.
     *
     * @param path     where the file is
     * @param relative how it is written in the listing, from the project root
     * @param why      what put it in - reviewed, called by, calls into
     */
    record Source(Path path, String relative, String why) {

        @Override
        public String toString() {
            return why.isBlank() ? relative : relative + "  - " + why;
        }
    }

    /**
     * What was assembled.
     *
     * @param prompt   the text to send
     * @param included one entry per file that went in, for the panel to list
     * @param skipped  what did not fit, so silence is never mistaken for absence
     */
    record Result(String prompt, List<Source> included, List<String> skipped) {
    }

    /** Directories whose contents are output, dependencies or history, never source. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".hg", ".svn", ".idea", ".vscode", ".smide", ".gradle", ".mvn",
            "target", "build", "out", "bin", "obj", "dist", "node_modules", "vendor",
            "venv", ".venv", "__pycache__", ".next", ".nuxt", "coverage", ".terraform");

    /** Extensions worth reading as code. Anything else is data, media or noise. */
    private static final Set<String> CODE = Set.of(
            "java", "kt", "kts", "scala", "groovy", "go", "rs", "c", "h", "cpp", "cc", "hpp",
            "cs", "py", "rb", "php", "ts", "tsx", "js", "jsx", "vue", "svelte", "swift", "m",
            "sql", "sh", "bash", "ps1", "html", "css", "scss", "less", "xml", "yaml", "yml",
            "json", "properties", "toml", "gradle", "tf", "proto", "graphql");

    /** An identifier long enough to mean something. Two-letter names match everything. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");

    /** What a file declares, by the shapes most languages share. */
    private static final Pattern DECLARED = Pattern.compile(
            "(?m)^\\s*(?:@\\w+\\s+)*(?:export\\s+|public\\s+|private\\s+|protected\\s+|internal\\s+"
            + "|static\\s+|final\\s+|abstract\\s+|sealed\\s+|open\\s+|data\\s+|default\\s+)*"
            + "(?:class|interface|enum|record|struct|trait|object|type|def|func|fn)\\s+([A-Za-z_]\\w*)");

    /** How many files may be read while looking for neighbours. */
    private static final int MAX_SCANNED = 4000;

    private CodeContext() {
    }

    /**
     * Builds the prompt for one review.
     *
     * @param root       the project, or null to review the file alone
     * @param file       the file under review
     * @param text       its current text, which may differ from what is on disk
     * @param budget     characters of source the whole prompt may carry
     * @param maxFiles   how many neighbours at most
     * @param perFile    characters of any one neighbour
     */
    static Result of(Path root, Path file, String text, int budget, int maxFiles, int perFile) {
        StringBuilder prompt = new StringBuilder();
        List<Source> included = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        String name = file.getFileName().toString();
        String head = truncate(text, Math.max(perFile, 8000));
        if (head.length() < text.length()) {
            skipped.add(name + " was truncated to " + head.length() + " of " + text.length()
                    + " characters");
        }

        if (root == null) {
            prompt.append("The file under review is `").append(name).append("`.")
                    .append(" There is no project open, so nothing around it could be read.\n\n")
                    .append(fence(file, head)).append('\n');
            included.add(new Source(file, name, "the file under review"));
            return new Result(prompt.toString(), included, skipped);
        }

        List<Path> files = scan(root);
        String relative = relative(root, file);
        prompt.append("PROJECT: ").append(root.getFileName()).append("  (")
                .append(files.size()).append(" source files)\n");
        prompt.append("FILE UNDER REVIEW: ").append(relative).append("\n\n");

        // The map: every file, and what it declares, on a line each. It is what lets the
        // model say where something lives that it was not handed the text of.
        int mapBudget = Math.max(2000, budget / 6);
        String map = ProjectIndex.of(root, files, mapBudget);
        if (!map.isBlank()) {
            prompt.append("## Project map (names only - evidence that something is declared,\n")
                    .append("## never evidence of what it does)\n\n```\n")
                    .append(map).append("\n```\n\n");
        }

        prompt.append("## The file under review\n\n").append(fence(file, head)).append('\n');
        included.add(new Source(file, relative, "the file under review"));
        int spent = prompt.length();

        List<Neighbour> neighbours = neighbours(root, file, head, files);
        if (!neighbours.isEmpty()) {
            prompt.append("## Related files, for context\n\n")
                    .append("These are here so that a finding in the file under review can be judged")
                    .append(" against how it is used. Report findings in the file under review;")
                    .append(" mention a related file only where it is part of the same problem.\n\n");
        }
        int taken = 0;
        for (Neighbour neighbour : neighbours) {
            if (taken >= maxFiles) {
                skipped.add((neighbours.size() - taken) + " further related files (file limit)");
                break;
            }
            String body = truncate(neighbour.text, perFile);
            String block = "### " + neighbour.relative + "  - " + neighbour.why + "\n\n"
                    + fence(neighbour.path, body) + "\n";
            if (spent + block.length() > budget) {
                skipped.add((neighbours.size() - taken) + " further related files (budget)");
                break;
            }
            prompt.append(block);
            spent += block.length();
            included.add(new Source(neighbour.path, neighbour.relative, neighbour.why));
            taken++;
        }
        return new Result(prompt.toString(), included, skipped);
    }

    // ------------------------------------------------------------- neighbours

    private record Neighbour(Path path, String relative, String text, String why, double score) {
    }

    private static List<Neighbour> neighbours(Path root, Path file, String text, List<Path> files) {
        Set<String> mentioned = words(text);
        Set<String> declares = declarations(text);
        String stem = stem(file);
        Path folder = file.getParent();

        List<Neighbour> found = new ArrayList<>();
        /* Callers are only found by reading, so every file is read once. Bounded, because
           "every file" is a promise about this project and not about the next one: past
           the budget the callers of this file are simply not all known, which is a worse
           answer than the whole truth and a much better one than a frozen window. */
        long readBudget = 24_000_000L;
        for (Path candidate : files) {
            if (candidate.equals(file) || readBudget <= 0) {
                continue;
            }
            String candidateStem = stem(candidate);
            boolean nameMentioned = mentioned.contains(candidateStem);
            boolean sibling = folder != null && folder.equals(candidate.getParent());
            String body = read(candidate);
            if (body == null) {
                continue;
            }
            readBudget -= body.length();
            double score = 0;
            List<String> why = new ArrayList<>();
            if (nameMentioned) {
                score += 3;
                why.add("used by the file under review");
            }
            boolean callsUs = false;
            for (String declared : declares) {
                if (containsWord(body, declared)) {
                    callsUs = true;
                    break;
                }
            }
            if (!callsUs && !stem.isEmpty() && containsWord(body, stem)) {
                callsUs = true;
            }
            if (callsUs) {
                score += 3;
                why.add("calls into the file under review");
            }
            if (sibling && score > 0) {
                score += 1;
            } else if (sibling && score == 0) {
                score += 0.5;
                why.add("in the same package");
            }
            if (score <= 0) {
                continue;
            }
            found.add(new Neighbour(candidate, relative(root, candidate), body,
                    String.join(", ", why), score));
        }
        found.sort(Comparator.comparingDouble(Neighbour::score).reversed()
                .thenComparing(Neighbour::relative));
        return found;
    }

    /** True if {@code needle} appears in {@code text} as a whole word. */
    private static boolean containsWord(String text, String needle) {
        int at = text.indexOf(needle);
        while (at >= 0) {
            boolean beforeOk = at == 0 || !isWordChar(text.charAt(at - 1));
            int end = at + needle.length();
            boolean afterOk = end >= text.length() || !isWordChar(text.charAt(end));
            if (beforeOk && afterOk) {
                return true;
            }
            at = text.indexOf(needle, at + 1);
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static Set<String> words(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            names.add(m.group());
        }
        return names;
    }

    static Set<String> declarations(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = DECLARED.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (name.length() > 2) {
                names.add(name);
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ files

    /** Every source file under {@code root}, output and dependency folders left out. */
    static List<Path> scan(Path root) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(CodeContext::isCode)
                    .filter(p -> !inSkippedFolder(root, p))
                    .limit(MAX_SCANNED)
                    .forEach(files::add);
        } catch (IOException | RuntimeException e) {
            // A project that cannot be walked still reviews the open file.
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static boolean inSkippedFolder(Path root, Path file) {
        Path relative = root.relativize(file);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (SKIP_DIRS.contains(relative.getName(i).toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCode(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        try {
            // A generated bundle is source by extension and noise by nature.
            return CODE.contains(name.substring(dot + 1)) && Files.size(file) <= 400_000;
        } catch (IOException e) {
            return false;
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null; // Binary, unreadable, or not UTF-8; it is not context we need.
        }
    }

    // ----------------------------------------------------------------- text

    static String relative(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.getFileName().toString();
        }
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        int cut = text.lastIndexOf('\n', limit);
        return text.substring(0, cut > limit / 2 ? cut : limit)
                + "\n// ... truncated by smIDE; the rest of this file was not sent ...\n";
    }

    /** A fenced block tagged with the language, so the model reads it as code. */
    private static String fence(Path file, String body) {
        return "```" + fenceLanguage(file) + "\n" + body
                + (body.endsWith("\n") ? "" : "\n") + "```\n";
    }

    /** Fence tags, where the extension is not the name the fence wants. */
    private static final Map<String, String> FENCE = new TreeMap<>(Map.ofEntries(
            Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"), Map.entry("py", "python"),
            Map.entry("rs", "rust"), Map.entry("rb", "ruby"), Map.entry("cs", "csharp"),
            Map.entry("sh", "bash"), Map.entry("ps1", "powershell"), Map.entry("yml", "yaml"),
            Map.entry("tf", "hcl"), Map.entry("h", "c"), Map.entry("hpp", "cpp"),
            Map.entry("cc", "cpp"), Map.entry("m", "objectivec"), Map.entry("gradle", "groovy")));

    static String fenceLanguage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String extension = name.substring(dot + 1);
        return FENCE.getOrDefault(extension, extension);
    }
}
