package com.smide.plugins.assistant;

import com.mdviewer.ai.AiConfig;
import com.smide.api.Ide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where the assistant may talk to, and as whom.
 *
 * <p>The same file shape as MDViewer's assistant, and the same class behind it: an
 * OpenAI-compatible endpoint per provider, keys taken from the environment unless someone
 * deliberately writes one down, and an {@code allowedHosts} list that a request is refused
 * against before it is built. The file is {@code ~/.smide/ai.properties}, so the IDE and
 * the viewer can be pointed at different models, and it is seeded from
 * {@code ~/.mdviewer/ai.properties} when that exists - having configured a provider once
 * should be enough.
 *
 * <p>Which provider and model to use is an IDE setting rather than a line in that file:
 * it is the one part someone changes often, and Settings is where they will look.
 */
public final class AssistantConfig {

    static final String PROVIDER_KEY = "assistant.provider";
    static final String MODEL_KEY = "assistant.model";
    static final String SCOPE_KEY = "assistant.reviewScope";

    /**
     * Written when there is nothing to seed from.
     *
     * <p>Only the lines this app has an opinion about. Everything else - the other seven
     * providers, the context budgets - comes from {@link AiConfig}'s own defaults, which
     * are layered underneath whatever this file says, so a provider added to the library
     * later still appears here without rewriting anyone's file.
     */
    private static final String SEED = """
            # smIDE assistant configuration.
            #
            # An OpenAI-compatible endpoint per provider. API keys: put one here, or leave
            # the ${env:NAME} form and set that environment variable instead. This file is
            # only ever edited a line at a time by the app, so anything you write in it -
            # including these comments - stays put.
            #
            # allowedHosts is the one that matters. The assistant sends your source code to
            # whichever endpoint is selected, and a host that is not on this list is refused
            # before the request is built. Being able to pick a provider is not permission
            # to send this codebase to it.

            provider.default    = litellm

            litellm.baseUrl     = https://litellm.mainul35.dev/v1
            litellm.model       = qwen3-coder:30b
            litellm.apiKey      = ${env:LITELLM_API_KEY}

            openwebui.baseUrl   = https://ai.mainul35.dev/api
            openwebui.model     = qwen3-coder:30b
            openwebui.apiKey    = ${env:OPENWEBUI_API_KEY}

            # Ollama, on this machine. No key, and nothing leaves the machine.
            ollama.baseUrl      = http://localhost:11434/v1
            ollama.model        = qwen3-coder:30b
            ollama.apiKey       =

            allowedHosts        = localhost, 127.0.0.1, litellm.mainul35.dev, ai.mainul35.dev

            # How much of the project one review may carry. The ceiling is the model's
            # context window, not this file: at roughly four characters per token, 90000
            # characters is some 22000 tokens of source before the reply is written.
            context.totalChars   = 90000
            context.perFileChars = 40000
            context.maxFiles     = 80
            """;

    private final Ide ide;
    private final AiConfig config;

    public AssistantConfig(Ide ide) {
        this.ide = ide;
        Path file = ide.homeDir().resolve("ai.properties");
        seed(file);
        this.config = new AiConfig(file);
    }

    /** The library's config object, which the chat client and the host check both need. */
    public AiConfig ai() {
        return config;
    }

    public Path file() {
        return config.getFile();
    }

    /** Providers worth offering: the ones whose host is already allowed, or all of them. */
    public List<String> providers() {
        return config.enabledProviderNames();
    }

    public String provider() {
        String chosen = ide.settings().get(PROVIDER_KEY, "");
        return chosen.isBlank() || config.endpoint(chosen).baseUrl().isBlank()
                ? config.defaultProvider() : chosen;
    }

    public void setProvider(String name) {
        ide.settings().set(PROVIDER_KEY, name == null ? "" : name);
    }

    /** The model to send: whatever was chosen in Settings, else the provider's own. */
    public String model() {
        String chosen = ide.settings().get(MODEL_KEY, "");
        return chosen.isBlank() ? config.endpoint(provider()).model() : chosen;
    }

    public void setModel(String model) {
        ide.settings().set(MODEL_KEY, model == null ? "" : model);
    }

    /** The endpoint a request goes to, with the chosen model substituted in. */
    public AiConfig.Endpoint endpoint() {
        AiConfig.Endpoint base = config.endpoint(provider());
        return new AiConfig.Endpoint(base.name(), base.baseUrl(), model(), base.apiKey());
    }

    /** True when there is somewhere to send a request that we are permitted to send to. */
    public boolean ready() {
        AiConfig.Endpoint endpoint = endpoint();
        return !endpoint.baseUrl().isBlank() && config.isAllowed(endpoint.baseUrl());
    }

    /** Why {@link #ready()} is false, in a sentence someone can act on. */
    public String whyNotReady() {
        AiConfig.Endpoint endpoint = endpoint();
        if (endpoint.baseUrl().isBlank()) {
            return "No model endpoint is configured. Press Configure, or Settings >"
                    + " Tools > Assistant.";
        }
        if (!config.isAllowed(endpoint.baseUrl())) {
            return "Refusing to send code to " + endpoint.host()
                    + ": it is not an allowed host. Press Configure to allow it.";
        }
        return "";
    }

    /**
     * A value out of {@code ai.properties} that {@code AiConfig} has no opinion about - the
     * search engine and its key, which are the IDE's business rather than the model's.
     */
    public String text(String key, String fallback) {
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file())) {
            properties.load(in);
        } catch (java.io.IOException | RuntimeException e) {
            return fallback;
        }
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** Writes one of those values back, leaving the rest of the file as it was. */
    public boolean setText(String key, String value) {
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file())) {
            properties.load(in);
        } catch (java.io.IOException | RuntimeException e) {
            // A file that is not there yet is a file with nothing in it.
        }
        if (value == null || value.isBlank()) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value.strip());
        }
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file())) {
            properties.store(out, "smIDE assistant");
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public int intValue(String key, int fallback) {
        return config.intValue(key, fallback);
    }

    /** How far a review may look: {@code file} or {@code project}. */
    public boolean projectScope() {
        return !"file".equals(ide.settings().get(SCOPE_KEY, "project"));
    }

    public void setProjectScope(boolean project) {
        ide.settings().set(SCOPE_KEY, project ? "project" : "file");
    }

    private void seed(Path file) {
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            /* MDViewer's file first. Someone who has already decided which host may see
               their work, and put a key somewhere, has answered the only two questions
               this file asks; making them answer again in a second app is not a
               safeguard, it is a reason to be careless with both. */
            Path fromViewer = Path.of(System.getProperty("user.home", "."),
                    ".mdviewer", "ai.properties");
            if (Files.isRegularFile(fromViewer)) {
                String text = Files.readString(fromViewer, StandardCharsets.UTF_8);
                Files.writeString(file, "# Seeded from " + fromViewer + " when smIDE first"
                        + " ran its assistant. Edits here do not affect that file.\n"
                        + text, StandardCharsets.UTF_8);
            } else {
                Files.writeString(file, SEED, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            // AiConfig writes its own defaults if this did not happen; nothing is lost
            // except the smIDE wording, and startup is never worth failing over.
            System.err.println("smIDE assistant: could not seed " + file + " - " + e);
        }
    }
}
