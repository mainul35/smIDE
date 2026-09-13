package com.smide.plugins.java.debug;

import com.smide.api.Ide;
import com.smide.api.debug.Breakpoint;
import com.smide.api.debug.DebugSession;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A debug session over JDWP, using JDI - the debugger interface that ships with the JDK.
 *
 * <p>The run configuration starts the program with an agent listening on a port; this
 * attaches to it, asks the VM to notify it when a class is prepared, and installs a
 * breakpoint request for every line the user marked in that class. When one is hit the
 * VM suspends, this reads the frames and tells the window.
 *
 * <p>Everything JDI is done on one thread - the event pump - because a VirtualMachine is
 * not safe to use from several. Reads that the UI asks for (frames, variables) happen on
 * the caller's thread while the VM is suspended, which JDI does allow.
 */
public final class JdiSession implements DebugSession {

    private final Ide ide;
    private final String name;
    private final VirtualMachine vm;
    private final List<Consumer<DebugSession>> listeners = new CopyOnWriteArrayList<>();
    /** The enabled breakpoints wanted in each class, by class name. */
    private final Map<String, List<Breakpoint>> wantedByClass = new HashMap<>();
    /** The requests installed in each class, so a change can take exactly those back. */
    private final Map<String, List<BreakpointRequest>> requestsByClass = new HashMap<>();
    /** Classes the VM has been asked to announce when they load, each asked for once. */
    private final Set<String> announced = new HashSet<>();
    /** Warnings already given, so re-installing a file's breakpoints does not repeat them. */
    private final Set<String> warned = new HashSet<>();
    private volatile ThreadReference suspendedThread;
    private volatile boolean running = true;
    private Thread pump;

    private JdiSession(Ide ide, String name, VirtualMachine vm) {
        this.ide = ide;
        this.name = name;
        this.vm = vm;
    }

    /**
     * Attaches to a JVM that is listening for a debugger.
     *
     * @param port the address the {@code -agentlib:jdwp} option was given
     */
    public static JdiSession attach(Ide ide, String name, int port, int timeoutSeconds) throws IOException {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> "com.sun.jdi.SocketAttach".equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new IOException("This JDK has no socket attaching connector."));
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("localhost");
        args.get("port").setValue(String.valueOf(port));
        args.get("timeout").setValue("2000");

        // The program needs a moment to open the port; retry rather than fail on the race.
        IOException last = null;
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                VirtualMachine vm = connector.attach(args);
                return new JdiSession(ide, name, vm);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while attaching", interrupted);
                }
            } catch (com.sun.jdi.connect.IllegalConnectorArgumentsException e) {
                throw new IOException("Bad connector arguments: " + e.getMessage(), e);
            }
        }
        throw new IOException("Could not attach to the debuggee on port " + port
                + (last == null ? "" : ": " + last.getMessage()));
    }

    /** Installs the current breakpoints and starts pumping events. */
    public void start(List<Breakpoint> breakpoints) {
        Map<Path, List<Breakpoint>> byFile = new LinkedHashMap<>();
        for (Breakpoint b : breakpoints) {
            byFile.computeIfAbsent(b.file(), f -> new ArrayList<>()).add(b);
        }
        byFile.forEach(this::sync);
        int wanted;
        int bound;
        synchronized (this) {
            wanted = wantedByClass.values().stream().mapToInt(List::size).sum();
            bound = requestsByClass.values().stream().mapToInt(List::size).sum();
        }
        if (wanted == 0) {
            ide.statusBar().message("Debugging " + name + " with no breakpoints set.");
        } else {
            ide.statusBar().message("Debugging " + name + ": " + bound + " of " + wanted
                    + " breakpoints bound, the rest when their classes load.");
        }
        pump = new Thread(this::pumpEvents, "smide-jdi-" + name);
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Makes the session match one file's breakpoints as they are now.
     *
     * <p>Given the file's whole list after any change - added, removed, disabled,
     * enabled - and answered the same way every time: take back every request the file's
     * class has, then install the enabled ones again. Only adding used to reach a running
     * session, and nothing was ever taken back, so disabling a breakpoint while stopped on
     * it hollowed the dot and changed nothing else: the program stopped there on the next
     * pass exactly as before.
     *
     * <p>A class that has not loaded yet is asked to be announced, once, so a breakpoint
     * added mid-session in code that has not run yet still binds when it does.
     */
    public synchronized void sync(Path file, List<Breakpoint> current) {
        if (!running) {
            return;
        }
        String className = classNameOf(file);
        if (className == null) {
            return;
        }
        try {
            for (BreakpointRequest request : requestsByClass.getOrDefault(className, List.of())) {
                vm.eventRequestManager().deleteEventRequest(request);
            }
            requestsByClass.remove(className);
            List<Breakpoint> enabled = current.stream().filter(Breakpoint::enabled).toList();
            if (enabled.isEmpty()) {
                wantedByClass.remove(className);
                return;
            }
            wantedByClass.put(className, enabled);
            /* Asked for before looking, not after: a class that finished loading between
               the look and the ask would be announced to nobody and never get its
               breakpoints. */
            if (announced.add(className)) {
                ClassPrepareRequest request = vm.eventRequestManager().createClassPrepareRequest();
                request.addClassFilter(className);
                request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                request.enable();
            }
            for (ReferenceType type : vm.classesByName(className)) {
                install(type);
            }
        } catch (com.sun.jdi.VMDisconnectedException e) {
            // The program ended between the check and the call; there is nothing left to change.
        }
    }

    /**
     * The class a source file declares, worked out from its path.
     *
     * <p>{@code .../src/main/java/com/example/Main.java} is {@code com.example.Main}. The
     * package declaration would be more reliable, but the file is on disk and this needs
     * no parse; a file outside a source root simply gets no breakpoints, which is the
     * same outcome as guessing wrong.
     */
    static String classNameOf(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!name.endsWith(".java")) {
            return null;
        }
        String simple = name.substring(0, name.length() - ".java".length());
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                    return trimmed.substring("package ".length(), trimmed.length() - 1).strip() + "." + simple;
                }
                if (trimmed.startsWith("import ") || trimmed.startsWith("public ") || trimmed.startsWith("class ")) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            return simple;
        }
        return simple;
    }

    /** Installs the wanted breakpoints into a loaded class: from {@link #sync}, or when it loads. */
    private synchronized void install(ReferenceType type) {
        for (Breakpoint b : wantedByClass.getOrDefault(type.name(), List.of())) {
            try {
                // JDI lines are one-based; the API's are zero-based.
                List<Location> locations = type.locationsOfLine(b.line() + 1);
                if (locations.isEmpty()) {
                    /* No code was compiled for that line - a blank line, a comment, or a
                       class file older than the source. Silence here looked exactly like
                       a debugger that ignores breakpoints. Said once: every change to the
                       file installs its breakpoints again. */
                    if (warned.add(type.name() + ":" + b.line())) {
                        ide.notifications().warn("Breakpoint not set",
                                b.label() + " has no executable code in " + type.name()
                                        + ". Rebuild the project if the class file is out of date.");
                    }
                    continue;
                }
                BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(locations.get(0));
                request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                if (b.condition() != null && warned.add("condition:" + type.name() + ":" + b.line())) {
                    // A real conditional breakpoint needs expression evaluation in the
                    // debuggee; until that exists the condition is shown but not applied.
                    ide.statusBar().message("Conditional breakpoints are not evaluated yet: " + b.label());
                }
                request.enable();
                requestsByClass.computeIfAbsent(type.name(), c -> new ArrayList<>()).add(request);
            } catch (AbsentInformationException e) {
                ide.statusBar().message("No line numbers in " + type.name() + "; compile with debug information.");
            } catch (RuntimeException e) {
                // A class that cannot take this breakpoint is not worth stopping the session for.
            }
        }
    }

    private void pumpEvents() {
        try {
            while (running) {
                EventSet events = vm.eventQueue().remove();
                boolean stay = false;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent prepared) {
                        install(prepared.referenceType());
                    } else if (event instanceof BreakpointEvent hit) {
                        suspendedThread = hit.thread();
                        stay = true;
                    } else if (event instanceof StepEvent step) {
                        // One step request at a time, or every line would fire for ever.
                        vm.eventRequestManager().deleteEventRequest(step.request());
                        suspendedThread = step.thread();
                        stay = true;
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        running = false;
                        suspendedThread = null;
                        notifyListeners();
                        return;
                    }
                }
                if (stay) {
                    notifyListeners();
                } else {
                    events.resume();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (com.sun.jdi.VMDisconnectedException e) {
            running = false;
            suspendedThread = null;
            notifyListeners();
        } catch (RuntimeException e) {
            System.err.println("smIDE debug: " + e);
        }
    }

    private void notifyListeners() {
        ide.window().runLater(() -> listeners.forEach(l -> l.accept(this)));
    }

    // ------------------------------------------------------------ DebugSession

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isSuspended() {
        return suspendedThread != null;
    }

    /** What a source path resolved to last time, so stepping does not look again. */
    private final java.util.Map<String, java.util.Optional<Path>> sources =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public List<StackFrameInfo> frames() {
        ThreadReference thread = suspendedThread;
        if (thread == null) {
            return List.of();
        }
        List<StackFrameInfo> out = new ArrayList<>();
        try {
            List<StackFrame> frames = thread.frames();
            for (int i = 0; i < frames.size(); i++) {
                Location location = frames.get(i).location();
                String description = location.declaringType().name() + "." + location.method().name() + "()";
                Path file = sourceOf(location);
                out.add(new StackFrameInfo(description, file, Math.max(0, location.lineNumber() - 1), i));
            }
        } catch (IncompatibleThreadStateException | com.sun.jdi.VMDisconnectedException e) {
            return List.of();
        }
        return out;
    }

    /**
     * The file a location came from, looked for in the open workspaces.
     *
     * <p>Cached, misses included: the frame list is rebuilt on every step, and a frame
     * from a library will not be found however many times it is looked for.
     */
    private Path sourceOf(Location location) {
        String path;
        try {
            path = location.sourcePath();
        } catch (AbsentInformationException | RuntimeException e) {
            return null;
        }
        return sources.computeIfAbsent(path,
                p -> SourceLookup.find(ide.workspaces().all(), p)).orElse(null);
    }

    @Override
    public List<VariableInfo> variables(StackFrameInfo frame) {
        ThreadReference thread = suspendedThread;
        if (thread == null) {
            return List.of();
        }
        List<VariableInfo> out = new ArrayList<>();
        try {
            StackFrame stackFrame = thread.frame(frame.index());
            ObjectReference self = stackFrame.thisObject();
            if (self != null) {
                out.add(new VariableInfo("this", self.referenceType().name(), render(self), true, self));
            }
            for (LocalVariable local : stackFrame.visibleVariables()) {
                Value value = stackFrame.getValue(local);
                out.add(new VariableInfo(local.name(), local.typeName(), render(value), expandable(value), value));
            }
        } catch (AbsentInformationException e) {
            out.add(new VariableInfo("(no variable names)", "",
                    "compile with -g to see local variables", false, null));
        } catch (IncompatibleThreadStateException | RuntimeException e) {
            return out;
        }
        return out;
    }

    @Override
    public List<VariableInfo> children(VariableInfo variable) {
        Object handle = variable.handle();
        List<VariableInfo> out = new ArrayList<>();
        try {
            if (handle instanceof ArrayReference array) {
                int shown = Math.min(array.length(), 200);
                for (int i = 0; i < shown; i++) {
                    Value value = array.getValue(i);
                    out.add(new VariableInfo("[" + i + "]", typeOf(value), render(value), expandable(value), value));
                }
                if (array.length() > shown) {
                    out.add(new VariableInfo("...", "", (array.length() - shown) + " more", false, null));
                }
            } else if (handle instanceof ObjectReference object) {
                for (Field field : object.referenceType().allFields()) {
                    if (field.isStatic()) {
                        continue;
                    }
                    Value value = object.getValue(field);
                    out.add(new VariableInfo(field.name(), field.typeName(), render(value), expandable(value), value));
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    /**
     * From the method's own line table.
     *
     * <p>Its first entry is the first statement, not the signature, so the line before it
     * counts too: that is where the parameters are declared, and they are as much the
     * method's variables as anything in its body.
     */
    @Override
    public boolean frameContains(StackFrameInfo frame, int line) {
        ThreadReference thread = suspendedThread;
        if (thread == null || frame == null) {
            return false;
        }
        try {
            int first = Integer.MAX_VALUE;
            int last = -1;
            for (Location location : thread.frame(frame.index()).location().method().allLineLocations()) {
                first = Math.min(first, location.lineNumber());
                last = Math.max(last, location.lineNumber());
            }
            int oneBased = line + 1;
            return last >= 0 && oneBased >= first - 1 && oneBased <= last;
        } catch (AbsentInformationException | IncompatibleThreadStateException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public Evaluation evaluate(StackFrameInfo frame, String expression) {
        ThreadReference thread = suspendedThread;
        if (thread == null) {
            return Evaluation.failed("The program is running; stop it at a breakpoint first.");
        }
        try {
            Value value = JdiEvaluator.evaluate(thread, frame == null ? 0 : frame.index(), expression);
            /* The handle is the JDI value itself, so the result expands in the variables
               tree exactly like a local does - the point of evaluating an object is
               usually to look inside it. */
            return Evaluation.of(new VariableInfo(expression.strip(), typeOf(value), render(value),
                    expandable(value), value));
        } catch (JdiEvaluator.EvalException e) {
            return Evaluation.failed(e.getMessage());
        } catch (com.sun.jdi.VMDisconnectedException e) {
            running = false;
            notifyListeners();
            return Evaluation.failed("The program has exited.");
        } catch (RuntimeException e) {
            return Evaluation.failed("Could not evaluate: " + e);
        }
    }

    private static boolean expandable(Value value) {
        return value instanceof ArrayReference
                || (value instanceof ObjectReference && !(value instanceof StringReference));
    }

    private static String typeOf(Value value) {
        return value == null ? "null" : value.type().name();
    }

    /** A value as one line of text, without calling toString() in the debuggee. */
    private static String render(Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof StringReference s) {
            String text = s.value();
            return '"' + (text.length() > 200 ? text.substring(0, 200) + "…" : text) + '"';
        }
        if (value instanceof ArrayReference array) {
            return array.type().name() + "[" + array.length() + "]";
        }
        if (value instanceof ObjectReference object) {
            return object.referenceType().name() + "@" + object.uniqueID();
        }
        return value.toString();
    }

    @Override
    public void resume() {
        if (suspendedThread == null) {
            return;
        }
        suspendedThread = null;
        try {
            vm.resume();
        } catch (com.sun.jdi.VMDisconnectedException e) {
            running = false;
        }
        notifyListeners();
    }

    @Override
    public void stepOver() {
        step(StepRequest.STEP_OVER);
    }

    @Override
    public void stepInto() {
        step(StepRequest.STEP_INTO);
    }

    @Override
    public void stepOut() {
        step(StepRequest.STEP_OUT);
    }

    private void step(int depth) {
        ThreadReference thread = suspendedThread;
        if (thread == null) {
            return;
        }
        try {
            // Any step request left from before would fire again on the next line.
            vm.eventRequestManager().stepRequests().forEach(vm.eventRequestManager()::deleteEventRequest);
            StepRequest request = vm.eventRequestManager()
                    .createStepRequest(thread, StepRequest.STEP_LINE, depth);
            request.addCountFilter(1);
            request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            request.enable();
            suspendedThread = null;
            vm.resume();
            notifyListeners();
        } catch (com.sun.jdi.VMDisconnectedException e) {
            running = false;
            notifyListeners();
        } catch (RuntimeException e) {
            ide.statusBar().message("Cannot step: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;
        suspendedThread = null;
        try {
            vm.dispose();
        } catch (RuntimeException e) {
            // Already gone.
        }
        if (pump != null) {
            pump.interrupt();
        }
        notifyListeners();
    }

    @Override
    public void addListener(Consumer<DebugSession> listener) {
        listeners.add(listener);
    }
}
