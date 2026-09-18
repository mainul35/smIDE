package com.smide.crash;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One unexpected failure, written down in a form that can be read, kept and sent.
 *
 * <p>The same shape travels three ways: into a file under {@code ~/.smide/logs/crashes},
 * onto the screen in the crash dialog, and - only when somebody presses Send - to a report
 * server. What the dialog shows under Details is {@link #text()}, and what is sent is the
 * same fields as JSON, so nothing leaves the machine that the reader has not been shown.
 *
 * <p>Two things are done to the text before any of that. The home directory is written as
 * {@code ~}, because a stack trace or an error message quotes paths and a path carries a
 * user name. And a password written into a URL - a JDBC URL in an exception message is
 * common - is replaced. The rest is the failure as it happened.
 *
 * @param id        unique to this occurrence
 * @param kind      {@code exception}, {@code startup} or {@code previous-session}
 * @param time      when it happened, in UTC
 * @param version   smIDE's version
 * @param os        operating system, version and architecture
 * @param java      the Java runtime and who built it
 * @param thread    the thread it happened on
 * @param exception the class of the innermost cause
 * @param message   that cause's message, or empty
 * @param stack     the whole stack trace, causes included
 * @param signature the same for every occurrence of the same failure; see {@link #signatureOf}
 * @param where     the part of smIDE it came from, in words
 * @param note      what the reader says they were doing; empty until they say
 * @param extra     anything else worth having - the JVM's own crash log, when there is one
 */
public record CrashReport(String id, String kind, String time, String version, String os, String java,
                          String thread, String exception, String message, String stack, String signature,
                          String where, String note, String extra) {

    public static final String EXCEPTION = "exception";
    public static final String STARTUP = "startup";
    public static final String PREVIOUS_SESSION = "previous-session";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A password between the scheme and the host: postgresql://user:secret@db. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("://[^/\\s:@]+:[^/\\s@]+@");

    /** Frames that go into the signature: enough to tell failures apart, few enough to survive a refactor. */
    private static final int SIGNATURE_FRAMES = 5;

    /** A report of a throwable caught on {@code thread}. */
    public static CrashReport of(String kind, Throwable failure, String thread, String version) {
        Throwable root = innermost(failure);
        return new CrashReport(
                UUID.randomUUID().toString(),
                kind,
                Instant.now().toString(),
                version,
                System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " (" + System.getProperty("os.arch") + ")",
                System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                thread,
                root.getClass().getName(),
                clean(String.valueOf(root.getMessage() == null ? "" : root.getMessage())),
                clean(traceOf(failure)),
                signatureOf(failure),
                whereOf(failure),
                "",
                "");
    }

    /** A report with nothing thrown to describe it: a session that ended without saying why. */
    public static CrashReport withoutThrowable(String kind, String summary, String version, String extra) {
        return new CrashReport(
                UUID.randomUUID().toString(),
                kind,
                Instant.now().toString(),
                version,
                System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " (" + System.getProperty("os.arch") + ")",
                System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                "",
                "",
                clean(summary),
                "",
                hash(kind + "|" + firstLine(extra)),
                "the Java runtime",
                "",
                clean(extra));
    }

    /** The same report with what the reader said they were doing. */
    public CrashReport withNote(String note) {
        return new CrashReport(id, kind, time, version, os, java, thread, exception, message, stack,
                signature, where, clean(note == null ? "" : note.strip()), extra);
    }

    // ------------------------------------------------------------------ output

    /** As a person reads it, and as the dialog shows it under Details. */
    public String text() {
        StringBuilder out = new StringBuilder();
        out.append("smIDE ").append(version).append(" - ").append(headline()).append('\n');
        out.append("When:      ").append(time).append('\n');
        out.append("Where:     ").append(where).append('\n');
        if (!thread.isEmpty()) {
            out.append("Thread:    ").append(thread).append('\n');
        }
        out.append("System:    ").append(os).append('\n');
        out.append("Java:      ").append(java).append('\n');
        out.append("Signature: ").append(signature).append('\n');
        if (!note.isEmpty()) {
            out.append("\nWhat they were doing:\n").append(note).append('\n');
        }
        if (!stack.isEmpty()) {
            out.append('\n').append(stack.stripTrailing()).append('\n');
        }
        if (!extra.isEmpty()) {
            out.append("\n--- from the Java runtime's crash log ---\n").append(extra.stripTrailing()).append('\n');
        }
        return out.toString();
    }

    /** One line: what went wrong, in the words a title bar has room for. */
    public String headline() {
        if (exception.isEmpty()) {
            return message.isEmpty() ? "stopped unexpectedly" : message;
        }
        String simple = exception.substring(exception.lastIndexOf('.') + 1);
        return message.isEmpty() ? simple : simple + ": " + firstLine(message);
    }

    public String json() {
        return GSON.toJson(this);
    }

    public static CrashReport fromJson(String json) {
        return GSON.fromJson(json, CrashReport.class);
    }

    // ------------------------------------------------------------------ parts

    /**
     * What makes two occurrences the same failure.
     *
     * <p>The class of the innermost cause and the first few frames it was thrown through -
     * class and method, not line number, so that a report from before an unrelated edit
     * to the same file still groups with one from after it. A dashboard of a thousand
     * reports is only readable if the thousand collapse into the dozen things that are
     * actually wrong.
     */
    static String signatureOf(Throwable failure) {
        Throwable root = innermost(failure);
        StringBuilder key = new StringBuilder(root.getClass().getName());
        StackTraceElement[] frames = root.getStackTrace();
        for (int i = 0; i < Math.min(SIGNATURE_FRAMES, frames.length); i++) {
            key.append('|').append(frameKey(frames[i]));
        }
        return hash(key.toString());
    }

    /**
     * A frame as it is named in a signature: without the numbers the compiler makes up.
     *
     * <p>A lambda is compiled to a method called {@code lambda$start$3}, and the 3 is its
     * place among the lambdas in that class - so adding an unrelated lambda above it renames
     * it, and the same failure after that edit would be counted as a new one. The class a
     * lambda is spun into at run time carries an address, {@code Foo$$Lambda/0x0000...},
     * which is different in every process. Both are dropped; what is left is the method the
     * code was written in.
     */
    private static String frameKey(StackTraceElement frame) {
        String type = frame.getClassName();
        int spun = type.indexOf("$$Lambda");
        if (spun >= 0) {
            type = type.substring(0, spun + "$$Lambda".length());
        }
        String method = frame.getMethodName();
        if (method.startsWith("lambda$")) {
            int number = method.lastIndexOf('$');
            if (number > "lambda$".length() && method.substring(number + 1).chars().allMatch(Character::isDigit)) {
                method = method.substring(0, number);
            }
        }
        return type + "." + method;
    }

    /**
     * The part of smIDE a failure came from, named the way its menus name it.
     *
     * <p>Taken from the first of smIDE's own frames rather than the top of the stack, which
     * is usually a library: an exception thrown inside JavaFX's layout code while laying
     * out the Markdown editor is the Markdown plugin's to answer for.
     */
    static String whereOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            for (StackTraceElement frame : t.getStackTrace()) {
                String name = frame.getClassName();
                if (name.startsWith("com.smide.plugins.")) {
                    String plugin = name.substring("com.smide.plugins.".length());
                    plugin = plugin.contains(".") ? plugin.substring(0, plugin.indexOf('.')) : plugin;
                    return "the " + plugin + " plugin (" + simple(name) + ")";
                }
                if (name.startsWith("com.smide.")) {
                    return "smIDE (" + simple(name) + ")";
                }
            }
        }
        return "a library, with none of smIDE's code on the stack";
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static Throwable innermost(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static String traceOf(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /** The home directory as ~, and no password left in a URL. */
    static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String home = System.getProperty("user.home");
        String out = text;
        if (home != null && home.length() > 3) {
            out = out.replace(home, "~");
            // Written the other way round too, as Windows paths are in half of what they appear in.
            out = out.replace(home.replace('\\', '/'), "~");
        }
        return URL_CREDENTIALS.matcher(out).replaceAll("://***@");
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).strip();
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
