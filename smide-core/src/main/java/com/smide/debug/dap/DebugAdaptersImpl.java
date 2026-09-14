package com.smide.debug.dap;

import com.smide.api.Ide;
import com.smide.api.debug.DebugAdapters;
import com.smide.api.debug.DebugSession;
import com.smide.api.execution.ConsoleHandle;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** The core's Debug Adapter Protocol client, as plugins see it. */
public final class DebugAdaptersImpl implements DebugAdapters {

    private final Ide ide;

    public DebugAdaptersImpl(Ide ide) {
        this.ide = ide;
    }

    @Override
    public DebugSession attach(String name, int port, Map<String, Object> attachArguments, Duration timeout,
                               ConsoleHandle console) throws IOException {
        return DapSession.connect(ide, name, port, attachArguments, timeout, console);
    }
}
