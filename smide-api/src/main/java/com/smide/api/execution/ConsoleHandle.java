package com.smide.api.execution;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A running or finished process and the console it wrote to. */
public interface ConsoleHandle {

    String title();

    boolean isRunning();

    /** Asks the process to stop; forcibly after a grace period. */
    void stop();

    CompletableFuture<Integer> exitCode();

    /** Sends text to the process's standard input. */
    void writeInput(String text);

    /** Appends a line to the console as the IDE speaking, not the process. */
    void println(String line);

    /** Every chunk of output as it arrives, on a background thread. */
    void addOutputListener(Consumer<String> listener);

    /** All output so far. */
    String output();
}
