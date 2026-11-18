package com.smide.plugins.assistant;

import com.mdviewer.ai.AiConfig;

import java.util.List;
import java.util.Locale;

/**
 * Which model answers this turn.
 *
 * <p>Two cases, and which one it is depends on one thing: whether anybody chose.
 *
 * <p><b>A model named in Settings answers everything it is given.</b> It is not weighed against
 * the rest of the list, not for a hard question and not for a long one. A gateway fronting a
 * hosted model and a shelf of downloaded ones offers them all in one list, and which of them
 * answers is exactly what the reader was deciding when they picked; preferring another for a job
 * theirs could do is this overruling them for a reason they cannot see, and it reads - correctly
 * - as the setting being ignored. The one thing a chosen model is not made to do is a job it
 * cannot do at all: asked to look at a picture with no eyes to look with, it would answer
 * nothing, so the turn stops instead and says so in words. See {@link ModelChoice#cannotSee}.
 *
 * <p><b>Auto - nobody chose - is this doing the choosing.</b> The endpoint is asked what it has;
 * a turn with a picture in it narrows that to the models whose names say they read pictures,
 * because no amount of good judgement makes a text model see; a question that looks like a job
 * of work rather than a quick look narrows it again to the models served from somewhere else,
 * on the grounds that somebody letting the IDE choose wants the hard thing done well more than
 * they want it done on the machine next to them. A small model on the same endpoint picks from
 * what is left.
 *
 * <p>{@code assistant.router.cloud} is how to say which models those far-away ones are, when
 * their names do not give it away. {@code assistant.router} turns the choosing off altogether,
 * leaving Auto with the provider's own model and nothing else.
 */
final class ModelRouter {

    /** Settings: whether to route at all, which model chooses, and which models are far away. */
    static final String ROUTING_KEY = "assistant.router";
    static final String CHOOSER_KEY = "assistant.router.model";
    static final String CLOUD_KEY = "assistant.router.cloud";

    /** What the choosing may cost before it is not worth having. */
    private static final int CHOOSING_SECONDS = 20;

    private final Assistant assistant;
    /** The last answer for a given job, so a conversation does not ask again every turn. */
    private final java.util.Map<String, String> decided = new java.util.HashMap<>();

    ModelRouter(Assistant assistant) {
        this.assistant = assistant;
    }

    /**
     * What the panel says while this is happening, and what it should say afterwards.
     *
     * @param why     why this is not the model that was expected, or null when it is
     * @param refusal why nothing was sent at all, or null when something was
     */
    record Routed(String model, String why, String refusal) {

        Routed(String model, String why) {
            this(model, why, null);
        }

        static Routed refused(String because) {
            return new Routed(null, null, because);
        }

        boolean changed() {
            return why != null;
        }

        boolean refused() {
            return refusal != null;
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
        if (assistant.config().modelChosen()) {
            // Somebody picked this one. It answers, or it says why it cannot.
            return hasPicture && !ModelChoice.canSee(chosen)
                    ? Routed.refused(ModelChoice.cannotSee(chosen))
                    : new Routed(chosen, null);
        }
        if (!assistant.ide().settings().getBoolean(ROUTING_KEY, true)) {
            return new Routed(chosen, null);
        }
        AiConfig.Endpoint endpoint = assistant.config().endpoint();
        List<String> offered = assistant.models(endpoint);
        if (offered.isEmpty()) {
            // An endpoint that will not say what it has: nothing to choose between.
            return hasPicture && !ModelChoice.canSee(chosen)
                    ? Routed.refused(ModelChoice.cannotSee(chosen))
                    : new Routed(chosen, null);
        }
        List<String> able = hasPicture ? ModelChoice.thatCanSee(offered) : offered;
        if (able.isEmpty()) {
            return Routed.refused("There is a picture on this question, and none of the models"
                    + " offered by " + endpoint.host() + " look like they read pictures."
                    + "\n\nAsk without the picture, or add a model that can see.");
        }
        List<String> marks = ModelChoice.marks(assistant.ide().settings().get(CLOUD_KEY, ""));
        List<String> candidates = ModelChoice.preferred(able, marks,
                ModelChoice.looksHard(question));
        if (candidates.size() == 1) {
            return answer(chosen, candidates.get(0), only(hasPicture));
        }
        String remembered = decided.get(key(question, hasPicture));
        if (remembered != null && candidates.contains(remembered)) {
            return answer(chosen, remembered, "chosen for this kind of question");
        }
        String picked = askSmallModel(endpoint, candidates, question, hasPicture);
        if (picked == null) {
            picked = ModelChoice.fallback(offered, chosen, hasPicture);
            if (picked == null || picked.equals(chosen)) {
                return new Routed(chosen, null);
            }
            return answer(chosen, picked, "it reads pictures and " + chosen + " does not");
        }
        decided.put(key(question, hasPicture), picked);
        return answer(chosen, picked, hasPicture ? "it reads pictures" : "picked for this job");
    }

    private static String only(boolean hasPicture) {
        return hasPicture ? "it is the only one here that reads pictures"
                : "it is the one this job wants";
    }

    private static Routed answer(String chosen, String picked, String why) {
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
