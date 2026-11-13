package com.smide.plugins.notebook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Optional;

/**
 * One line the kernel helper wrote on its standard output.
 *
 * <p>Parsing is kept here, away from the process and away from the screen, because it is
 * the part that can be wrong in interesting ways and the part worth testing. A line that
 * does not parse is not an error: the helper writes everything else it has to say to
 * standard error, but a package the project imports is perfectly capable of printing to
 * standard output on import, and that would land here. Such a line is reported as
 * {@link #isUnderstood() not understood} and the caller logs it and carries on, which is
 * better than taking the kernel down over somebody else's banner.
 */
public record KernelEvent(String type, String id, String msgType, JsonObject content,
                          String message, String module, String status, Integer executionCount) {

    /** The helper could not import what it needs; {@link #module()} says what. */
    public static final String MISSING = "missing";
    /** The kernel is up and has answered. */
    public static final String READY = "ready";
    /** Something the kernel published: output, a status change, an execution count. */
    public static final String MESSAGE = "message";
    /** One execution finished; {@link #status()} is {@code ok}, {@code error} or {@code abort}. */
    public static final String REPLY = "reply";
    /** The kernel was restarted and is up again. */
    public static final String RESTARTED = "restarted";
    /** The helper could not do what was asked, or could not start a kernel at all. */
    public static final String FAILED = "failed";
    /** A line the helper did not write; {@link #message()} is the line. */
    public static final String UNKNOWN = "unknown";

    public boolean isUnderstood() {
        return !UNKNOWN.equals(type);
    }

    public boolean is(String type) {
        return type.equals(this.type);
    }

    public Optional<Integer> count() {
        return Optional.ofNullable(executionCount);
    }

    /** Reads one line; never throws, and never returns null. */
    public static KernelEvent parse(String line) {
        if (line == null || line.isBlank()) {
            return unknown(line);
        }
        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(line);
            if (parsed == null || !parsed.isJsonObject()) {
                return unknown(line);
            }
            object = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return unknown(line);
        }
        String type = string(object, "type");
        if (type == null) {
            return unknown(line);
        }
        JsonElement content = object.get("content");
        JsonElement count = object.get("execution_count");
        Integer executionCount = null;
        if (count != null && count.isJsonPrimitive()) {
            try {
                executionCount = count.getAsInt();
            } catch (RuntimeException ignored) {
                // A count that is not a number is no count; the cell keeps the one it had.
            }
        }
        return new KernelEvent(type, string(object, "id"), string(object, "msg_type"),
                content != null && content.isJsonObject() ? content.getAsJsonObject() : new JsonObject(),
                string(object, "message"), string(object, "module"), string(object, "status"),
                executionCount);
    }

    private static KernelEvent unknown(String line) {
        return new KernelEvent(UNKNOWN, null, null, new JsonObject(), line, null, null, null);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
