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

    /** Words that say this question is a job of work rather than a quick look. */
    private static final List<String> HARD = List.of(
            "refactor", "implement", "architect", "design", "debug", "diagnose", "why",
            "migrate", "optimis", "optimiz", "performance", "concurren", "race condition",
            "rewrite", "plan", "review", "trace", "deadlock", "memory leak");

    /** And a length, past which a question is carrying a job inside it whatever it says. */
    private static final int LONG_QUESTION = 400;

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

    /**
     * What to tell somebody whose chosen model cannot look at the picture they attached.
     *
     * <p>They named a model, so that model answers - this is not the place to quietly send
     * their question somewhere else. But a model without eyes does not answer a picture
     * badly, it answers it with nothing, and silence is the one reply that teaches nobody
     * anything. So the turn stops here and says which model, why, and the ways out.
     */
    static String cannotSee(String model) {
        return (model == null || model.isBlank() ? "The model you chose" : model)
                + " does not read pictures, going by its name, so this question was not sent:"
                + " it would have come back empty."
                + "\n\nEither ask without the picture, or choose a model that reads pictures"
                + " under Settings > Tools > Assistant, or set the model there to Auto and"
                + " smIDE will pick one that can whenever a picture is attached.";
    }

    /**
     * Whether a name looks like a model downloaded onto the machine serving it.
     *
     * <p>A gateway puts everything it fronts in one list, and nothing in that list says where
     * any of it runs. The names do, near enough: the downloaded ones are pulled by tag, so
     * they are written {@code name:tag} or {@code hf.co/owner/repo:quant}, while a profile
     * configured by hand to reach a hosted model is written as a plain name. It is a guess,
     * which is why {@link #servedElsewhere} will take a list of names instead when somebody
     * has said which is which.
     */
    static boolean looksDownloaded(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String name = model.toLowerCase(Locale.ROOT);
        return name.startsWith("hf.co/") || name.contains(":");
    }

    /**
     * The ones that are not running on the machine the gateway is on.
     *
     * @param marks what the reader said their far-away models are called, in whole or in part;
     *              empty leaves it to {@link #looksDownloaded}
     */
    static List<String> servedElsewhere(List<String> offered, List<String> marks) {
        List<String> far = new ArrayList<>();
        for (String model : offered == null ? List.<String>of() : offered) {
            if (marks == null || marks.isEmpty() ? !looksDownloaded(model) : matches(model, marks)) {
                far.add(model);
            }
        }
        return far;
    }

    private static boolean matches(String model, List<String> marks) {
        String name = model.toLowerCase(Locale.ROOT);
        for (String mark : marks) {
            if (mark != null && !mark.isBlank() && name.contains(mark.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** A setting written as a list, turned into the marks {@link #servedElsewhere} wants. */
    static List<String> marks(String setting) {
        List<String> marks = new ArrayList<>();
        for (String word : (setting == null ? "" : setting).split("[,;\\s]+")) {
            if (!word.isBlank()) {
                marks.add(word.strip());
            }
        }
        return marks;
    }

    /**
     * Whether this is the sort of job worth the bigger, further-away model.
     *
     * <p>Only ever asked when nobody chose a model, so the cost of being wrong is one answer
     * from a model that was more than the question needed - which is why it leans that way.
     * Reading the words is the only thing available: nothing on an OpenAI-compatible endpoint
     * says how good anything is, and the question is all there is to go on.
     */
    static boolean looksHard(String question) {
        if (question == null) {
            return false;
        }
        if (question.length() >= LONG_QUESTION) {
            // Long enough to be a job rather than a question.
            return true;
        }
        String words = question.toLowerCase(Locale.ROOT);
        for (String mark : HARD) {
            if (words.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The candidates worth offering, given what the job looks like.
     *
     * <p>A preference and not a rule: where a hard job has nowhere far away to go, it goes to
     * whatever is here rather than not going at all.
     */
    static List<String> preferred(List<String> candidates, List<String> marks, boolean hard) {
        if (!hard || candidates == null || candidates.size() < 2) {
            return candidates;
        }
        List<String> far = servedElsewhere(candidates, marks);
        return far.isEmpty() ? candidates : far;
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
