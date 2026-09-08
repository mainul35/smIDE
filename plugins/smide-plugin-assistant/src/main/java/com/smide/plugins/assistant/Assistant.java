package com.smide.plugins.assistant;

import com.mdviewer.ai.AiConfig;
import com.mdviewer.ai.ChatProvider;
import com.smide.api.Ide;

import java.util.List;
import java.util.function.Consumer;

/**
 * The one place the assistant talks to a model.
 *
 * <p>Every request goes out on a thread of its own and comes back on the JavaFX thread,
 * because a reply takes as long as the model takes and the window has to stay alive
 * meanwhile. The host allowlist is enforced inside {@link ChatProvider}, before a request
 * is built, so nothing here can route around it.
 *
 * <p>This assistant does not write code. That is a property of the prompts in
 * {@link Prompts}, not of this class, which would send anything it was given.
 */
public final class Assistant {

    /** A request in flight, so a panel can stop waiting for one it no longer wants. */
    public static final class Turn {

        private volatile boolean cancelled;
        private volatile Thread worker;

        public void cancel() {
            cancelled = true;
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Raised inside the stream to unwind a turn the panel has given up on. */
    private static final class Abandoned extends RuntimeException {
        private Abandoned() {
            super(null, null, false, false);
        }
    }

    private final Ide ide;
    private final AssistantConfig config;
    private final ChatProvider provider;

    public Assistant(Ide ide, AssistantConfig config) {
        this.ide = ide;
        this.config = config;
        this.provider = new ChatProvider(config.ai());
    }

    public Ide ide() {
        return ide;
    }

    public AssistantConfig config() {
        return config;
    }

    /**
     * Sends a conversation and streams the reply.
     *
     * <p>{@code onToken} arrives many times on the JavaFX thread as fragments come in;
     * exactly one of {@code onDone} or {@code onError} follows, unless the turn was
     * cancelled, in which case neither does.
     */
    public Turn ask(List<ChatProvider.Message> messages, Consumer<String> onToken,
                    Consumer<String> onDone, Consumer<String> onError) {
        Turn turn = new Turn();
        if (!config.ready()) {
            ide.window().runLater(() -> onError.accept(config.whyNotReady()));
            return turn;
        }
        AiConfig.Endpoint endpoint = config.endpoint();
        ide.window().runInBackground(() -> {
            turn.worker = Thread.currentThread();
            try {
                String whole = provider.stream(endpoint, messages, fragment -> {
                    if (turn.cancelled) {
                        throw new Abandoned();
                    }
                    ide.window().runLater(() -> {
                        if (!turn.cancelled) {
                            onToken.accept(fragment);
                        }
                    });
                });
                if (!turn.cancelled) {
                    ide.window().runLater(() -> onDone.accept(whole));
                }
            } catch (Abandoned e) {
                // Asked for by the panel; there is nobody left to tell.
            } catch (Exception e) {
                if (!turn.cancelled) {
                    String message = describe(e);
                    ide.window().runLater(() -> onError.accept(message));
                }
            } finally {
                turn.worker = null;
                // interrupt() may have arrived after the request finished.
                Thread.interrupted();
            }
        });
        return turn;
    }

    /** The model names the endpoint offers. Blocks; callers are already off the FX thread. */
    public List<String> models(AiConfig.Endpoint endpoint) {
        return provider.listModels(endpoint);
    }

    /** Proves the host and key work, sending no code at all. Blocks. */
    public String test(AiConfig.Endpoint endpoint) {
        return provider.testConnection(endpoint);
    }

    /** The part of a failure worth putting on screen. Never the stack trace. */
    private String describe(Exception e) {
        if (e instanceof ChatProvider.NotAllowedException) {
            return e.getMessage();
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String text = message.length() > 400 ? message.substring(0, 400) + "..." : message;
        /* A 401 is configuration, not a fault, and the endpoint says so in its own words -
           "No api key passed in" - which tells somebody what is wrong and nothing about
           where to put one. The answer is two levels into Settings, so it gets said. */
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("401") || lower.contains("api key") || lower.contains("unauthorized")) {
            text += "\n\nThis provider has no key. Press **Configure** to add one, or set"
                    + " the environment variable named against it in " + config.file() + ".";
        }
        return text;
    }
}
