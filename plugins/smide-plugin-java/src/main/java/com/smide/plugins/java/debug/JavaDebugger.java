package com.smide.plugins.java.debug;

import com.smide.api.Ide;
import com.smide.api.debug.DebugSession;
import com.smide.api.debug.Debugger;
import com.smide.api.execution.RunConfiguration;
import com.smide.plugins.java.run.ApplicationRunType;
import com.smide.plugins.java.run.SpringBootRunType;

/**
 * Debugs anything the Java plugin can run: the process is started with a JDWP agent, and
 * this attaches to it with JDI.
 */
public final class JavaDebugger implements Debugger {

    @Override
    public String id() {
        return "java.jdi";
    }

    @Override
    public boolean supports(RunConfiguration configuration) {
        String type = configuration.type().id();
        return ApplicationRunType.ID.equals(type) || SpringBootRunType.ID.equals(type);
    }

    @Override
    public DebugSession attach(Ide ide, RunConfiguration configuration, int port) throws Exception {
        // The agent is started with suspend=y, so the program is waiting: there is time to
        // install every breakpoint before a line of it runs.
        JdiSession session = JdiSession.attach(ide, configuration.name(), port, 60);
        session.start(ide.breakpoints().all());
        ide.breakpoints().addListener(file -> ide.breakpoints().inFile(file)
                .forEach(session::addBreakpoint));
        return session;
    }
}
