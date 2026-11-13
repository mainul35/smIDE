package com.smide.plugins.notebook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns what a kernel published into what a notebook stores.
 *
 * <p>The two are nearly the same thing and deliberately so: nbformat's output types were
 * taken from the messages, which is why an {@code execute_result} message's content is
 * already an {@code execute_result} output but for the {@code output_type} field it does
 * not carry. The differences are the whole of this class, and they are worth writing down
 * because each one is a bug somebody has had:
 *
 * <ul>
 *   <li>a message carries no {@code output_type}; the output needs one;
 *   <li>{@code stream} text arrives in pieces, one message per flush, and a cell that
 *       prints in a loop produces hundreds of them - a notebook that stored each as its
 *       own output would be enormous and would render as hundreds of separate blocks, so
 *       consecutive text on the same stream is appended to the output already there;
 *   <li>{@code execute_input} and {@code status} are not output at all, they are the
 *       kernel saying what it is doing;
 *   <li>{@code clear_output} is an instruction, not a thing to store.
 * </ul>
 *
 * <p>Nothing here touches a kernel or a screen, so all of it is tested.
 */
final class Outputs {

    private Outputs() {
    }

    /** What a kernel message means for a cell's output list. */
    sealed interface Effect {

        /** Add this, or fold it into the last one. */
        record Add(Output output) implements Effect {
        }

        /** Throw away what is there; {@code clear_output} with {@code wait} is still a clear. */
        record Clear() implements Effect {
        }

        /** The cell is now running this execution, and should say so. */
        record Counted(int executionCount) implements Effect {
        }

        /** Nothing to do. */
        record None() implements Effect {
        }
    }

    /**
     * Reads one message.
     *
     * @param msgType the kernel's {@code msg_type}
     * @param content the message's content
     */
    static Effect effectOf(String msgType, JsonObject content) {
        if (msgType == null) {
            return new Effect.None();
        }
        JsonObject body = content == null ? new JsonObject() : content;
        return switch (msgType) {
            case "stream" -> new Effect.Add(Output.stream(
                    string(body, "name", "stdout"), Output.multiline(body.get("text"))));
            case "execute_result" -> new Effect.Add(Output.result(
                    intOf(body, "execution_count", 0), object(body, "data"), object(body, "metadata")));
            case "display_data", "update_display_data" -> new Effect.Add(Output.displayData(
                    object(body, "data"), object(body, "metadata")));
            case "error" -> new Effect.Add(Output.error(
                    string(body, "ename", ""), string(body, "evalue", ""), strings(body, "traceback")));
            case "clear_output" -> new Effect.Clear();
            // The kernel echoing back what it was asked to run, with the number it gave it.
            case "execute_input" -> {
                int count = intOf(body, "execution_count", -1);
                yield count < 0 ? new Effect.None() : new Effect.Counted(count);
            }
            default -> new Effect.None();
        };
    }

    /**
     * Appends an output to a list, folding stream text into the output before it.
     *
     * <p>Only text on the same stream is folded: {@code stdout} after {@code stderr} stays
     * separate, because the two are drawn differently and merging them would colour one as
     * the other. A result or a picture is never folded into anything.
     */
    static List<Output> append(List<Output> existing, Output output) {
        List<Output> out = new ArrayList<>(existing);
        if (!out.isEmpty() && Output.STREAM.equals(output.outputType())) {
            Output last = out.get(out.size() - 1);
            if (Output.STREAM.equals(last.outputType())
                    && last.streamName().equals(output.streamName())) {
                out.set(out.size() - 1, Output.stream(output.streamName(), last.text() + output.text()));
                return List.copyOf(out);
            }
        }
        out.add(output);
        return List.copyOf(out);
    }

    /**
     * Whether the kernel is idle, from a {@code status} message, or empty for anything else.
     *
     * <p>Idle after a run is what says the cell has finished and the spinner can stop. The
     * execute reply says the same thing, but the two race: a kernel can publish idle before
     * the reply reaches the shell channel, and either one arriving is enough.
     */
    static Optional<Boolean> idleFrom(String msgType, JsonObject content) {
        if (!"status".equals(msgType) || content == null) {
            return Optional.empty();
        }
        String state = string(content, "execution_state", "");
        if (state.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("idle".equals(state));
    }

    private static JsonObject object(JsonObject holder, String key) {
        JsonElement value = holder.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject holder, String key, String fallback) {
        JsonElement value = holder.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static int intOf(JsonObject holder, String key, int fallback) {
        JsonElement value = holder.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static List<String> strings(JsonObject holder, String key) {
        JsonElement value = holder.get(key);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            }
        }
        return List.copyOf(out);
    }
}
