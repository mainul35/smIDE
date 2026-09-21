package com.smide.plugins.assistant;

import com.mdviewer.ai.ChatProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Ask tab's agent: a question, then as many looks at the project as it takes to answer it.
 *
 * <p>The model is given tools and a way to call them, and the loop does the rest: it reads what
 * the model asked for, does it, hands back what happened, and goes round again until the model
 * answers in prose instead of asking for something. The model cannot call anything by itself -
 * every tool here is a method in this IDE, run by this loop, and the two that change the project
 * stop and ask the developer first.
 *
 * <p>The provider this talks to has no idea of tools - it is a chat endpoint, and nothing more -
 * so a call is a fenced block of JSON in the reply, and the result of one is the next thing the
 * developer's side of the conversation says. It is the oldest trick in the book and it has one
 * great virtue: it works with every model the IDE can be pointed at, including the local ones.
 */
public final class AskAgent {

    /** Enough turns to read a few files, build, and fix something; not enough to run all day. */
    private static final int MAX_STEPS = 16;
    /** What the conversation may grow to before the oldest tool results are dropped. */
    private static final int WINDOW_CHARS = 110_000;

    /** A fenced block asking for a tool: ```smide { "tool": ... } ``` */
    private static final Pattern CALL = Pattern.compile(
            "```(?:smide|json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    /** What the panel is told as the agent works. */
    public interface Listener {

        /** The model's own words, as they arrive and then whole. */
        void streaming(String soFar);

        /** One step of work, in the present tense: "Reading build.gradle". */
        void doing(String what);

        /** The same step, done, with what came back for the transcript. */
        void did(String what, String result);

        /** A change the developer must accept; the agent waits on the answer. */
        CompletableFuture<Boolean> approve(AskTools.Change change);

        /** The turn ended with this as the answer. */
        void answered(String markdown);

        void failed(String message);
    }

    private final Assistant assistant;
    private final AskTools tools;
    private final AskWeb web;
    private final Listener listener;
    private final List<ChatProvider.Message> history = new ArrayList<>();
    private volatile Assistant.Turn turn;
    private volatile boolean stopped;
    private volatile boolean autonomous;

    public AskAgent(Assistant assistant, AskTools tools, AskWeb web, Listener listener) {
        this.assistant = assistant;
        this.tools = tools;
        this.web = web;
        this.listener = listener;
    }

    /**
     * Whether the developer has said to get on with it.
     *
     * <p>Off for every question. It is turned on when they ask for something to be fixed rather
     * than explained, and only for that one task: an agent that keeps the freedom it was given
     * once is an agent nobody can hand a question to safely.
     */
    public void setAutonomous(boolean autonomous) {
        this.autonomous = autonomous;
    }

    public boolean isAutonomous() {
        return autonomous;
    }

    public void forget() {
        history.clear();
        refused.clear();
    }

    public void stop() {
        stopped = true;
        Assistant.Turn running = turn;
        if (running != null) {
            running.cancel();
        }
    }

    /** Asks, and works until there is an answer. Runs on the caller's thread: not the window's. */
    public void ask(String question) {
        stopped = false;
        history.add(new ChatProvider.Message("user", question));
        try {
            for (int step = 0; step < MAX_STEPS && !stopped; step++) {
                String reply = round();
                if (stopped) {
                    return;
                }
                String call = callIn(reply);
                if (call == null) {
                    history.add(new ChatProvider.Message("assistant", reply));
                    listener.answered(reply);
                    return;
                }
                history.add(new ChatProvider.Message("assistant", reply));
                String result = run(call);
                if (stopped) {
                    return;
                }
                history.add(new ChatProvider.Message("user", "TOOL RESULT:\n" + result));
                trim();
            }
            if (!stopped) {
                listener.answered("I stopped after " + MAX_STEPS + " steps without finishing."
                        + " Ask me to carry on if that is what you want.");
            }
        } catch (RuntimeException e) {
            if (!stopped) {
                listener.failed(String.valueOf(e.getMessage() == null ? e : e.getMessage()));
            }
        }
    }

    /** One exchange with the model, blocking until it has finished speaking. */
    private String round() {
        List<ChatProvider.Message> messages = new ArrayList<>();
        messages.add(new ChatProvider.Message("system", AskPrompts.system(tools, web, autonomous)));
        messages.addAll(history);
        CompletableFuture<String> done = new CompletableFuture<>();
        StringBuilder streamed = new StringBuilder();
        turn = assistant.ask(messages,
                token -> {
                    streamed.append(token);
                    // A tool call is not for reading: it is shown as the step it turns into.
                    if (callIn(streamed.toString()) == null) {
                        listener.streaming(streamed.toString());
                    }
                },
                whole -> done.complete(whole),
                error -> done.completeExceptionally(new IllegalStateException(error)));
        try {
            return done.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    /** The JSON of the tool call in a reply, or null when the model is answering rather than asking. */
    static String callIn(String reply) {
        if (reply == null) {
            return null;
        }
        Matcher matcher = CALL.matcher(reply);
        String last = null;
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body.contains("\"tool\"")) {
                last = body;
            }
        }
        return last;
    }

    /** Does what the call asks for, and says what happened. */
    private String run(String json) {
        JsonObject call;
        try {
            call = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return "That was not a tool call I could read. Send one fenced block of JSON with a \"tool\" in it.";
        }
        String tool = string(call, "tool");
        String path = string(call, "path");
        switch (tool) {
            case "read_file" -> {
                return step("Reading " + tools.shortened(path), () -> tools.readFile(path));
            }
            case "list_files" -> {
                return step("Listing " + (path.isBlank() ? "the project" : tools.shortened(path)),
                        () -> tools.listFiles(path));
            }
            case "find_text" -> {
                String text = string(call, "text");
                return step("Looking for \"" + text + "\"", () -> tools.findText(text));
            }
            case "project_info" -> {
                return step("Looking at the project", tools::projectInfo);
            }
            case "problems" -> {
                return step("Reading the problems", tools::problems);
            }
            case "build" -> {
                return step("Building the project", tools::build);
            }
            case "run" -> {
                List<String> command = strings(call, "command");
                if (command.isEmpty()) {
                    return "A run needs a command, as a list of words.";
                }
                if (!allowedToRun(command)) {
                    return "The developer did not agree to run that.";
                }
                return step("Running " + String.join(" ", command),
                        () -> tools.run(command, path.isBlank() ? null : tools.root().resolve(path),
                                "The assistant: " + String.join(" ", command)));
            }
            case "web_search" -> {
                String query = string(call, "query");
                return step("Searching the web for \"" + query + "\"", () -> web.search(query));
            }
            case "fetch_url" -> {
                String url = string(call, "url");
                return step("Reading " + url, () -> web.fetch(url));
            }
            case "write_file" -> {
                return write(tools.proposeWrite(path, string(call, "content")));
            }
            case "delete_file" -> {
                try {
                    return write(tools.proposeDelete(path));
                } catch (IllegalArgumentException e) {
                    return e.getMessage();
                }
            }
            case "replace_in_file" -> {
                try {
                    return write(tools.proposeReplace(path, string(call, "find"), string(call, "replace")));
                } catch (IllegalArgumentException e) {
                    return e.getMessage();
                }
            }
            default -> {
                return "There is no tool called " + tool + ".";
            }
        }
    }

    /**
     * Files the developer has already said no to.
     *
     * <p>Kept, because a model that is told "no, and do not try that again" will try it again:
     * in the session this was built from, a file was refused, two folders were listed, and the
     * same file was proposed a second time - which is the IDE asking the same question twice and
     * hoping for a different answer. A refusal is about the file, not about the wording.
     */
    private final java.util.Set<String> refused = new java.util.HashSet<>();

    /** A change the developer says yes or no to, unless they have already said to get on with it. */
    private String write(AskTools.Change change) {
        String where = change.relativeTo(tools.root());
        String what = change.verb() + " " + where;
        if (refused.contains(where)) {
            listener.did(what, "already refused");
            return "The developer has already said no to " + where + " in this task."
                    + " Leave it alone and tell them what you would do instead.";
        }
        listener.doing(what);
        /* Asked about, unless the developer has said to get on with it - and even then the change
           goes through the editor, so it is one Ctrl+Z away and the file is theirs again. */
        boolean allowed;
        try {
            allowed = autonomous || listener.approve(change).join();
        } catch (RuntimeException e) {
            allowed = false;
        }
        if (!allowed) {
            refused.add(where);
            listener.did(what, "the developer said no");
            return "The developer did not accept that change, and it will not be offered again."
                    + " Ask them what they would prefer instead.";
        }
        String outcome = tools.apply(change);
        listener.did(what, outcome);
        return outcome;
    }

    /**
     * Whether a command may run.
     *
     * <p>Reading the project is one thing; starting a process is another, and the developer is
     * asked unless they have already said to get on with it. A build is not covered by this - it
     * is the project's own build, and it is what "why will this not compile" means.
     */
    private boolean allowedToRun(List<String> command) {
        if (autonomous) {
            return true;
        }
        AskTools.Change asking = new AskTools.Change(AskTools.Change.Kind.COMMAND,
                tools.root(), null, String.join(" ", command));
        try {
            return listener.approve(asking).join();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String step(String what, java.util.function.Supplier<String> work) {
        listener.doing(what);
        String result = work.get();
        listener.did(what, result);
        return result;
    }

    /** Keeps the conversation inside the window by forgetting the oldest tool results first. */
    private void trim() {
        while (size() > WINDOW_CHARS && history.size() > 4) {
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).content().startsWith("TOOL RESULT:")) {
                    history.remove(i);
                    if (i < history.size() && "assistant".equals(history.get(i).role())) {
                        history.remove(i);
                    }
                    break;
                }
            }
            if (history.stream().noneMatch(m -> m.content().startsWith("TOOL RESULT:"))) {
                history.remove(0);
            }
        }
    }

    private int size() {
        int total = 0;
        for (ChatProvider.Message message : history) {
            total += message.content().length();
        }
        return total;
    }

    private static String string(JsonObject call, String name) {
        return call.has(name) && call.get(name).isJsonPrimitive() ? call.get(name).getAsString() : "";
    }

    private static List<String> strings(JsonObject call, String name) {
        List<String> out = new ArrayList<>();
        if (call.has(name) && call.get(name).isJsonArray()) {
            call.get(name).getAsJsonArray().forEach(element -> out.add(element.getAsString()));
        } else if (call.has(name) && call.get(name).isJsonPrimitive()) {
            out.addAll(List.of(call.get(name).getAsString().split("\\s+")));
        }
        return out;
    }
}
