package com.smide.api.debug;

import com.smide.api.execution.ConsoleHandle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

/**
 * Debug sessions over the Debug Adapter Protocol - what Delve, debugpy, CodeLLDB, netcoredbg
 * and js-debug speak.
 *
 * <p>A plugin starts its program under the adapter in a console, so the program's output is
 * where a run's output always is, and then asks for a session here. The core speaks the
 * protocol, hands the adapter the IDE's breakpoints and keeps them in step, and the Debug
 * window draws the session like any other. A new language gets a debugger for the price of
 * a command line.
 */
public interface DebugAdapters {

    /**
     * Connects to an adapter listening on a local port and attaches to the program it runs.
     *
     * <p>Waits for the port to open: an adapter that builds the program first, as
     * {@code dlv debug} does, listens only once the build is done. The wait ends early when
     * the console's process has ended - a build that failed, say. Called on a background
     * thread; throws with a message the user can act on.
     *
     * @param name            what the Debug window calls the session
     * @param attachArguments the adapter's own attach arguments, {@code {"mode": "remote"}} for Delve
     * @param console         the console the adapter runs in, or null
     */
    DebugSession attach(String name, int port, Map<String, Object> attachArguments, Duration timeout,
                        ConsoleHandle console) throws IOException;

    /** A local port nothing listens on at the moment, for an adapter to be told to listen on. */
    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the debugger", e);
        }
    }
}
