package com.smide.plugins.java.debug;

import com.smide.api.Ide;
import com.smide.api.debug.DebugSession;
import com.smide.api.debug.Debugger;
import com.smide.api.execution.RunConfiguration;
import com.smide.plugins.java.run.ApplicationRunType;

/**
 * Debugs anything the Java plugin can run: the process is started with a JDWP agent, and
 * this attaches to it with JDI.
 */
public final class JavaDebugger implements Debugger {

    @Override
    public String id() {
        return "java.jdi";
    }

    /** Run configuration kinds of other plugins that start a JVM waiting for this debugger - Spring Boot's. */
    private static final java.util.Set<String> ALSO = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Debugs configurations of this kind too: they start the JVM with the JDWP agent, suspended, as Application does. */
    public static void alsoDebug(String runTypeId) {
        ALSO.add(runTypeId);
    }

    @Override
    public boolean supports(RunConfiguration configuration) {
        String type = configuration.type().id();
        return ApplicationRunType.ID.equals(type) || ALSO.contains(type);
    }

    @Override
    public DebugSession attach(Ide ide, RunConfiguration configuration, int port) throws Exception {
        // The agent is started with suspend=y, so the program is waiting: there is time to
        // install every breakpoint before a line of it runs.
        JdiSession session = JdiSession.attach(ide, configuration.name(), port, 60);
        session.start(ide.breakpoints().all());
        // The file's whole list every time, so a disabled or removed breakpoint is taken back.
        ide.breakpoints().addListener(file -> session.sync(file, ide.breakpoints().inFile(file)));
        return session;
    }
}
