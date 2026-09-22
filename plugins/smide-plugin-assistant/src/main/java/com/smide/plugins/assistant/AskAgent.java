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

    /**
     * Enough turns to read a few files, build, and fix something; not enough to run all day.
     *
     * <p>Both of these are settings - {@code assistant.ask.maxSteps} and
     * {@code assistant.ask.windowChars} - because what is enough depends on the model. A local
     * one with a small window needs the conversation kept short; a hosted one with a large window
     * can be let run. The defaults suit the middle.
     */
    private static final int DEFAULT_MAX_STEPS = 60;
    /** And how long one question may run, whatever it is doing. */
    private static final int DEFAULT_MINUTES = 15;
    private static final int DEFAULT_WINDOW_CHARS = 110_000;

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

    /** What the model has been told so far, for the conversation to keep between sessions. */
    List<ChatProvider.Message> history() {
        return List.copyOf(history);
    }

    /**
     * Gives the model back what it knew.
     *
     * <p>Called when a conversation is picked up again - another project brought to the front,
     * or the IDE started afresh - so that "and the other one?" means something. The system prompt
     * is not among these: it is written from the project as it is now, every turn, which is how a
     * conversation from last week asks about today's files.
     */
    void restore(List<ChatProvider.Message> remembered) {
        history.clear();
        if (remembered != null) {
            history.addAll(remembered);
        }
    }

    public void stop() {
        stopped = true;
        Assistant.Turn running = turn;
        if (running != null) {
            running.cancel();
        }
    }

    private int maxSteps() {
        return Math.max(4, Math.min(100,
                assistant.ide().settings().getInt("assistant.ask.maxSteps", DEFAULT_MAX_STEPS)));
    }

    /** How long one question may take before the answer is asked for. */
    private int minutes() {
        return Math.max(1, Math.min(120,
                assistant.ide().settings().getInt("assistant.ask.minutes", DEFAULT_MINUTES)));
    }

    private int windowChars() {
        return Math.max(20_000, Math.min(2_000_000,
                assistant.ide().settings().getInt("assistant.ask.windowChars", DEFAULT_WINDOW_CHARS)));
    }

    /** Asks, and works until there is an answer. Runs on the caller's thread: not the window's. */
    public void ask(String question) {
        stopped = false;
        done.clear();
        changedSomething = false;
        built = false;
        tested = false;
        askedToBuild = false;
        askedToTest = false;
        history.add(new ChatProvider.Message("user", question));
        int steps = maxSteps();
        long until = System.currentTimeMillis() + minutes() * 60_000L;
        try {
            for (int step = 0; !stopped; step++) {
                /* The work is finished when the model answers, not when a counter says so. The
                   limits are here to stop a runaway, and a runaway is a thing that has stopped
                   getting anywhere - so the model is warned as they approach, told outright when
                   they are reached, and asked for its answer rather than left to trail off. The
                   developer should never have to say "carry on" to a thing that was in the middle
                   of doing what they asked. */
                boolean lastChance = step >= steps || System.currentTimeMillis() > until;
                if (step == steps - WARN_BEFORE && !lastChance) {
                    history.add(new ChatProvider.Message("user",
                            "You have " + WARN_BEFORE + " steps left before you must answer."
                                    + " Stop exploring and do the part that matters."));
                }
                String reply = lastChance ? finalAnswer() : speak();
                if (stopped) {
                    return;
                }
                String call = lastChance ? null : callIn(reply);
                if (call == null) {
                    String check = unchecked();
                    if (check != null) {
                        /* It has changed the project and is about to say so without having looked
                           at what it did. Nobody would accept that from a person. */
                        history.add(new ChatProvider.Message("assistant", reply));
                        history.add(new ChatProvider.Message("user", check));
                        continue;
                    }
                    history.add(new ChatProvider.Message("assistant", reply));
                    listener.answered(lastChance ? reply + tookTooLong(steps) : reply);
                    return;
                }
                history.add(new ChatProvider.Message("assistant", reply));
                if (done.getOrDefault(call, 0) >= 2) {
                    /* Twice is a coincidence, three times is a loop. It happens when a tool keeps
                       failing for a reason the model cannot act on, and it spends the remaining
                       steps trying the same thing again. */
                    history.add(new ChatProvider.Message("user", "TOOL RESULT:\nYou have already done"
                            + " exactly this twice and got the same answer. Do something else, or tell"
                            + " the developer what is in the way."));
                    continue;
                }
                done.merge(call, 1, Integer::sum);
                String result = run(call);
                if (stopped) {
                    return;
                }
                history.add(new ChatProvider.Message("user", "TOOL RESULT:\n" + result));
                trim();
            }
        } catch (Throwable e) {
            /* Everything, not just the runtime kind. Whatever went wrong in here, the developer
               is owed a sentence about it: a turn that ends without one looks exactly like a turn
               that stopped for no reason, and leaves the tab waiting on an answer that is never
               coming. */
            if (!stopped) {
                listener.failed(describe(e));
            }
        }
    }

    /** What to put in front of the developer when something threw. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName() + " - nothing was said about why.";
        }
        return message;
    }

    /**
     * The model's next reply, insisted upon.
     *
     * <p>A provider that drops a connection, rate-limits, or returns an empty body used to end the
     * turn on the spot: the steps so far stayed on screen, no answer arrived, and nothing said
     * why. None of those are reasons to abandon work that is half done, so they are simply tried
     * again, and only a provider that will not speak three times running is given up on - out
     * loud.
     */
    private String speak() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < ATTEMPTS && !stopped; attempt++) {
            if (attempt > 0) {
                listener.doing("The model did not answer, trying again");
                sleep(1500L * attempt);
                if (stopped) {
                    break;
                }
            }
            try {
                String reply = round();
                if (reply != null && !reply.isBlank()) {
                    return reply;
                }
            } catch (RuntimeException e) {
                last = e;
            }
        }
        if (stopped) {
            return "";
        }
        throw last != null ? last
                : new IllegalStateException("the model sent nothing back, " + ATTEMPTS + " times over."
                        + " The provider may be rate limiting, or the conversation may be too long"
                        + " for its window - `assistant.ask.windowChars` in settings.json sets how"
                        + " much of it is kept.");
    }

    /** How many times a silent provider is asked again before the developer is told. */
    private static final int ATTEMPTS = 3;

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }

    /** How many steps before the end the model is told to start finishing. */
    private static final int WARN_BEFORE = 5;

    /**
     * The answer, asked for with the tools taken away.
     *
     * <p>What a reader gets when the work has gone on too long: not "I gave up, ask me to carry
     * on", but what was found, what was done, and what is left - which is worth having even when
     * the job is half done, and which the model can only write if it is asked for it.
     */
    private String finalAnswer() {
        listener.doing("Writing up what I found");
        history.add(new ChatProvider.Message("user",
                "Stop here. Do not use another tool - any tool call in this reply will be ignored."
                        + " Answer now with what you have: what you found, what you changed, what is"
                        + " still wrong, and what you would do next."));
        return speak();
    }

    private String tookTooLong(int steps) {
        return "\n\n---\n\n*I stopped here after " + steps + " steps"
                + (lastStep == null ? "" : ", the last being " + lastStep)
                + ". Ask me to go on and I will pick this up; `assistant.ask.maxSteps` and"
                + " `assistant.ask.minutes` in settings.json set how far I get in one go.*";
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
                        listener.streaming(Replies.cleaned(streamed.toString()));
                    }
                },
                whole -> done.complete(Replies.cleaned(whole)),
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
        String text = Replies.cleaned(reply);
        Matcher matcher = CALL.matcher(text);
        String last = null;
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body.contains("\"tool\"")) {
                last = body;
            }
        }
        return last != null ? last : unfenced(text);
    }

    /**
     * A call the model wrote without the fence it was asked for.
     *
     * <p>Models put a tool call where their training told them to: in a fence, on a line of its
     * own, after {@code to=smide}, or in the middle of a sentence explaining what it is about to
     * do. The fence is what the prompt asks for and what the hosted models give, but a local model
     * that writes {@code Let's replace the file. to=smide {"tool": "replace_in_file", ...}} means
     * exactly the same thing - and reading that as an answer ends the turn in the middle of the
     * job, which is what it looked like from the outside: the work stopping half done.
     *
     * <p>Only a complete object naming a tool this IDE has counts, so a model writing about tool
     * calls is still writing rather than calling.
     */
    private static String unfenced(String text) {
        String found = null;
        for (int i = text.indexOf('{'); i >= 0; i = text.indexOf('{', i + 1)) {
            String object = objectAt(text, i);
            if (object == null || !object.contains("\"tool\"")) {
                continue;
            }
            try {
                JsonObject call = JsonParser.parseString(object).getAsJsonObject();
                if (TOOLS.contains(string(call, "tool"))) {
                    found = object;
                    i += object.length() - 1;
                }
            } catch (RuntimeException e) {
                // Not JSON after all; the next brace may be.
            }
        }
        return found;
    }

    /** The whole JSON object starting at this brace, or null if it never finishes. */
    private static String objectAt(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (inString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }

    /** What this IDE can be asked for; anything else in a JSON object is not a call. */
    private static final java.util.Set<String> TOOLS = java.util.Set.of(
            "project_info", "list_files", "tree", "read_file", "find_text", "problems", "build",
            "test", "run", "web_search", "fetch_url", "write_file", "replace_in_file", "delete_file");

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
                List<String> several = strings(call, "paths");
                if (!several.isEmpty()) {
                    return step("Reading " + several.size() + " files", () -> tools.readFiles(several));
                }
                return step("Reading " + tools.shortened(path), () -> tools.readFile(path));
            }
            case "tree" -> {
                int depth = call.has("depth") && call.get("depth").isJsonPrimitive()
                        ? call.get("depth").getAsInt() : 3;
                return step("Looking through " + (path.isBlank() ? "the project" : tools.shortened(path)),
                        () -> tools.tree(path, depth));
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
                built = true;
                return step("Building the project", tools::build);
            }
            case "test" -> {
                tested = true;
                return step("Running the tests", tools::test);
            }
            case "run" -> {
                List<String> command = strings(call, "command");
                if (command.isEmpty()) {
                    return "A run needs a command, as a list of words.";
                }
                if (!allowedToRun(command)) {
                    return "The developer did not agree to run that.";
                }
                // Checking its work by hand counts as checking its work.
                String what = String.join(" ", command).toLowerCase(java.util.Locale.ROOT);
                built |= what.contains("build") || what.contains("compile") || what.contains("test");
                tested |= what.contains("test");
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
    /** What has been done this turn and how often, so a loop is cut short rather than run out. */
    private final java.util.Map<String, Integer> done = new java.util.HashMap<>();
    /** Whether the project has been changed since it was last built and tested, and by how far. */
    private volatile boolean changedSomething;
    private volatile boolean built;
    private volatile boolean tested;
    /** Each reason to send it back is used once: a model that ignores it will ignore it twice. */
    private boolean askedToBuild;
    private boolean askedToTest;

    /**
     * What the agent still owes the developer before it is allowed to say it is finished.
     *
     * <p>A change that has not been built is a guess, and a change that builds but was never run
     * is half an answer: "it compiles" is not what anybody asked. So a turn that changed the
     * project and then went quiet is sent back to look at what it did - once for the build, once
     * for the tests, and no further, because an agent that cannot get a clean build is not helped
     * by being told to try again forever. Null when there is nothing owed.
     */
    private String unchecked() {
        if (!changedSomething) {
            return null;
        }
        if (!built && !askedToBuild) {
            askedToBuild = true;
            return "You changed this project and have not built it since. Build it now, and if it"
                    + " fails, fix what you broke before you answer.";
        }
        if (built && !tested && !askedToTest && !tools.testCommand().isEmpty()) {
            askedToTest = true;
            return "It builds. Now run the tests - " + String.join(" ", tools.testCommand())
                    + " - and tell the developer what passed and what failed. Do not describe a"
                    + " change as working until something other than you has said so.";
        }
        return null;
    }
    /** The last step taken, for the message when the steps run out. */
    private volatile String lastStep;

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
        // Whatever it built or ran before this, it was a different project a moment ago.
        changedSomething = true;
        built = false;
        tested = false;
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
        lastStep = what;
        listener.doing(what);
        String result = work.get();
        listener.did(what, result);
        return result;
    }

    /** Keeps the conversation inside the window by forgetting the oldest tool results first. */
    private void trim() {
        int window = windowChars();
        while (size() > window && history.size() > 4) {
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
