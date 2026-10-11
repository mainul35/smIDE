package com.smide.plugins.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which of an endpoint's models should answer this one.
 *
 * <p>A gateway offers a dozen: one that writes code, one that reads pictures, one that is small
 * and quick, one that is enormous and slow. The setting names a single one of them, and that one
 * answered everything - so a screenshot pasted into a question went to a model that has never
 * seen a pixel, which came back with nothing at all and looked like a broken connection.
 *
 * <p>The deciding is here and the talking is not, so what it decides can be tested without a
 * gateway. Two steps: the hard requirements first - a question with a picture in it needs a model
 * that reads pictures, and nothing else will do - and then a preference among whatever is left,
 * which is where a small model is asked what it thinks.
 */
final class ModelChoice {

    /**
     * What a model's name says about whether it can see.
     *
     * <p>Names are all there is. No OpenAI-compatible endpoint says what its models can do, and
     * the ones a gateway fronts are named by whoever published them - so this is a list of the
     * ways people write "this one has eyes", and it will want adding to. Being wrong in one
     * direction costs a turn; being wrong in the other costs the feature, so it is deliberately
     * generous and the failure it causes is reported rather than silent.
     */
    private static final List<String> SEEING = List.of(
            "vision", "-vl", "vl-", "vl:", "llava", "bakllava", "moondream", "pixtral",
            "minicpm-v", "cogvlm", "internvl", "janus", "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4",
            "claude", "gemini", "gemma3", "gemma4", "qwen2-vl", "qwen2.5-vl", "qwen3-vl",
            "phi-3.5-vision", "phi-4-multimodal", "granite-vision", "aya-vision", "mistral-small3",
            "llama3.2-vision", "llama-3.2-vision", "llama4", "step-1v", "glm-4v", "yi-vision");

    /** Words that say "small and quick", for the model that does the choosing. */
    private static final List<String> SMALL = List.of(
            "mini", "small", "tiny", "nano", "micro", "lite", "flash", "haiku");

    /**
     * And a size, read as a number rather than matched as text.
     *
     * <p>As text they lie: "14b" ends in "4b", and a list of the sizes people use has to guess
     * at "1.7b" and "0.6b" and whatever is published next week. The number is right there in the
     * name; reading it and comparing it is both simpler and correct.
     */
    private static final java.util.regex.Pattern SIZE = java.util.regex.Pattern.compile(
            "(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s?b(?![\\d])");

    /** Billions of parameters, at or under which a model is quick enough for one question. */
    private static final double SMALL_BILLIONS = 9;

    private ModelChoice() {
    }

    /** Whether a model with this name is likely to read pictures. */
    static boolean canSee(String model) {
        if (model == null) {
            return false;
        }
        String name = model.toLowerCase(Locale.ROOT);
        for (String mark : SEEING) {
            if (name.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    /** The ones that could take a picture, in the order the endpoint gave them. */
    static List<String> thatCanSee(List<String> offered) {
        List<String> seeing = new ArrayList<>();
        for (String model : offered == null ? List.<String>of() : offered) {
            if (canSee(model)) {
                seeing.add(model);
            }
        }
        return seeing;
    }

    /**
     * The cheapest model to ask a one-line question of.
     *
     * <p>Used for the choosing itself, which is one short question and wants the quickest thing
     * on the gateway rather than the best. Falls back to whatever is set, because a routing step
     * that is slower than the work it routes is not worth having.
     */
    static String smallest(List<String> offered, String fallback) {
        for (String model : offered == null ? List.<String>of() : offered) {
            String name = model.toLowerCase(Locale.ROOT);
            if (isSmall(name)) {
                return model;
            }
            for (String mark : SMALL) {
                if (name.contains(mark)) {
                    return model;
                }
            }
        }
        return fallback;
    }

    /** Whether the size in this name, if there is one, is a small one. */
    private static boolean isSmall(String name) {
        java.util.regex.Matcher size = SIZE.matcher(name);
        while (size.find()) {
            try {
                if (Double.parseDouble(size.group(1)) <= SMALL_BILLIONS) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Not a size after all; the next one in the name might be.
            }
        }
        return false;
    }

    /**
     * What a model said when it was asked to choose, turned back into one of the names.
     *
     * <p>Models answer this sort of question with a sentence, a bullet, a name in backticks, or
     * the name and then why. Any of those is fine as long as exactly one of the offered names is
     * in it; anything else is no answer, and the caller keeps what it had.
     */
    static String named(String reply, List<String> offered) {
        if (reply == null || offered == null) {
            return null;
        }
        String said = reply.toLowerCase(Locale.ROOT);
        String found = null;
        for (String model : offered) {
            if (said.contains(model.toLowerCase(Locale.ROOT))) {
                // The longest match wins: "qwen3:8b" is in "qwen3:8b-instruct" and is not it.
                if (found == null || model.length() > found.length()) {
                    found = model;
                }
            }
        }
        return found;
    }

    /**
     * Which model answers this turn, when nobody is asked.
     *
     * <p>The chosen one if it will do; otherwise the first that will. "Will do" is only ever
     * about what the turn needs - a picture - because everything else is a preference, and a
     * preference belongs to the developer who set the model, not to this.
     */
    static String fallback(List<String> offered, String chosen, boolean needsEyes) {
        if (!needsEyes) {
            return chosen;
        }
        if (canSee(chosen)) {
            return chosen;
        }
        List<String> seeing = thatCanSee(offered);
        return seeing.isEmpty() ? null : seeing.get(0);
    }

    /** The question the small model is asked. Short on purpose: it is paying for every word. */
    static String question(List<String> candidates, String task, boolean hasPicture) {
        return "Pick the model best suited to this job. Answer with one name from the list and"
                + " nothing else.\n\nModels:\n- " + String.join("\n- ", candidates)
                + "\n\nThe job" + (hasPicture ? " includes a picture to look at" : "") + ":\n"
                + (task.length() > 600 ? task.substring(0, 600) : task);
    }
}
