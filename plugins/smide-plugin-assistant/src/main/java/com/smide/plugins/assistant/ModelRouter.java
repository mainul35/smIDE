package com.smide.plugins.assistant;

import com.mdviewer.ai.AiConfig;

import java.util.List;
import java.util.Locale;

/**
 * Sends each turn to the model that can do it.
 *
 * <p>An endpoint that fronts a dozen models is one setting in this IDE, and that setting answered
 * everything - so a screenshot pasted into a question went to a model that reads only text, and
 * came back with nothing at all. From the outside that is indistinguishable from a broken
 * connection, which is what it was reported as.
 *
 * <p>So before a turn starts, the endpoint is asked what it has, and a small model on the same
 * endpoint is asked which of them suits the job. Only the ones that could do it at all are
 * offered to it: a question with a picture in it is shown models that read pictures and nothing
 * else, because no amount of good judgement makes a text model see.
 *
 * <p>Off by default for nobody - {@code assistant.router} turns it off - but a model the
 * developer named explicitly is still used whenever it can do the job, because somebody who
 * chose a model meant it.
 */
final class ModelRouter {

    /** Settings: whether to route at all, and which model is asked to do the choosing. */
    static final String ROUTING_KEY = "assistant.router";
    static final String CHOOSER_KEY = "assistant.router.model";

    /** What the choosing may cost before it is not worth having. */
    private static final int CHOOSING_SECONDS = 20;

    private final Assistant assistant;
    /** The last answer for a given job, so a conversation does not ask again every turn. */
    private final java.util.Map<String, String> decided = new java.util.HashMap<>();

    ModelRouter(Assistant assistant) {
        this.assistant = assistant;
    }

    /** What the panel says while this is happening, and what it should say afterwards. */
    record Routed(String model, String why) {

        boolean changed() {
            return why != null;
        }
    }

    /**
     * The model for this turn. Blocks: callers are off the window's thread.
     *
     * @param question what was asked, for the small model to judge by
     * @param hasPicture whether this turn carries an image, which rules most models out
     */
    Routed routeFor(String question, boolean hasPicture) {
        String chosen = assistant.config().model();
        if (!assistant.ide().settings().getBoolean(ROUTING_KEY, true)) {
            return new Routed(chosen, null);
        }
        AiConfig.Endpoint endpoint = assistant.config().endpoint();
        List<String> offered = assistant.models(endpoint);
        if (offered.isEmpty()) {
            // Nothing to choose between, or an endpoint that will not say: leave it alone.
            return new Routed(chosen, null);
        }
        if (hasPicture && !ModelChoice.canSee(chosen)) {
            List<String> seeing = ModelChoice.thatCanSee(offered);
            if (seeing.isEmpty()) {
                return new Routed(chosen, "none of the models here look like they read pictures,"
                        + " so this went to " + chosen + " as it is");
            }
        } else if (hasPicture) {
            // What is set already has eyes; nothing to decide.
            return new Routed(chosen, null);
        }
        List<String> candidates = hasPicture ? ModelChoice.thatCanSee(offered) : offered;
        if (candidates.size() == 1) {
            return answer(chosen, candidates.get(0), hasPicture, "it is the only one that can");
        }
        String remembered = decided.get(key(question, hasPicture));
        if (remembered != null && candidates.contains(remembered)) {
            return answer(chosen, remembered, hasPicture, "chosen for this kind of question");
        }
        String picked = askSmallModel(endpoint, candidates, question, hasPicture);
        if (picked == null) {
            picked = ModelChoice.fallback(offered, chosen, hasPicture);
            if (picked == null || picked.equals(chosen)) {
                return new Routed(chosen, null);
            }
            return answer(chosen, picked, hasPicture, "it reads pictures and " + chosen + " does not");
        }
        decided.put(key(question, hasPicture), picked);
        return answer(chosen, picked, hasPicture,
                hasPicture ? "it reads pictures" : "picked for this job");
    }

    private static Routed answer(String chosen, String picked, boolean hasPicture, String why) {
        if (picked.equals(chosen)) {
            return new Routed(chosen, null);
        }
        return new Routed(picked, why);
    }

    /** One question, to the quickest thing on the endpoint. */
    private String askSmallModel(AiConfig.Endpoint endpoint, List<String> candidates,
                                 String question, boolean hasPicture) {
        String chooser = assistant.ide().settings().get(CHOOSER_KEY, "");
        if (chooser.isBlank()) {
            chooser = ModelChoice.smallest(assistant.models(endpoint), null);
        }
        if (chooser == null || chooser.isBlank()) {
            return null;
        }
        String reply = assistant.askOnce(endpoint, chooser,
                ModelChoice.question(candidates, question, hasPicture), CHOOSING_SECONDS);
        return ModelChoice.named(reply, candidates);
    }

    /** One remembered decision per kind of job, which is as fine a grain as this is worth. */
    private static String key(String question, boolean hasPicture) {
        String words = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return (hasPicture ? "picture:" : "text:") + (words.length() > 40
                ? words.substring(0, 40) : words);
    }
}
