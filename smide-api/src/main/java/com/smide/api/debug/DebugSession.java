package com.smide.api.debug;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * A running debug session, as the Debug tool window sees it.
 *
 * <p>The core knows nothing about JDWP, DAP or any particular runtime: a plugin starts a
 * session and publishes state through this interface, and the window draws it. Every
 * method may be called from the JavaFX thread and must not block.
 */
public interface DebugSession {

    /** What the Debug window puts on its tab. */
    String name();

    boolean isRunning();

    /** True while the program is stopped at a breakpoint or step. */
    boolean isSuspended();

    /** Frames of the thread that is stopped, innermost first; empty while running. */
    List<StackFrameInfo> frames();

    /** Variables visible in a frame, fetched on demand because each one costs a round trip. */
    List<VariableInfo> variables(StackFrameInfo frame);

    /** Children of a variable that has them: fields of an object, elements of an array. */
    List<VariableInfo> children(VariableInfo variable);

    /**
     * Works out what an expression means where the program is stopped.
     *
     * <p>Optional, because not every runtime can: one that cannot says so and the window
     * shows the reason rather than an empty box. What a debugger is asked at a breakpoint
     * is nearly always a name, a field of one, an element of one or a getter, so a session
     * that answers only those is worth far more than one that answers nothing.
     */
    default Evaluation evaluate(StackFrameInfo frame, String expression) {
        return Evaluation.failed("This debugger cannot evaluate expressions.");
    }

    void resume();

    void stepOver();

    void stepInto();

    void stepOut();

    /** Ends the session; the program it was debugging is left running or killed as the plugin decides. */
    void stop();

    /** Called whenever the session suspends, resumes or ends, on the JavaFX thread. */
    void addListener(Consumer<DebugSession> listener);

    /** What an expression came to, or why it came to nothing. */
    record Evaluation(VariableInfo value, String error) {

        public static Evaluation of(VariableInfo value) {
            return new Evaluation(value, null);
        }

        public static Evaluation failed(String why) {
            return new Evaluation(null, why);
        }

        public boolean ok() {
            return value != null;
        }
    }

    /** One frame of the call stack. */
    record StackFrameInfo(String description, Path file, int line, int index) {
    }

    /**
     * A variable, its value rendered as text, and whether it can be opened further.
     *
     * @param handle what the plugin needs to fetch children; opaque to the window
     */
    record VariableInfo(String name, String type, String value, boolean expandable, Object handle) {
    }
}
