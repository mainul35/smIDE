package com.smide.plugins.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Connects JediTerm to a pty4j process: the emulator reads decoded characters from the
 * pty's output and writes raw bytes to its input.
 *
 * <p>JediTerm 3.47 does not ship a pty4j connector of its own, so this is the bridge.
 * {@link #read} is called from JediTerm's emulator thread and blocks; {@link #write} from
 * the Swing thread on key presses. Both are safe because they touch different streams.
 */
final class PtyTtyConnector implements TtyConnector {

    private final PtyProcess process;
    private final Reader reader;
    private final OutputStream output;
    private final String name;

    PtyTtyConnector(PtyProcess process, String name) {
        this.process = process;
        this.name = name;
        this.reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        this.output = process.getOutputStream();
    }

    PtyProcess process() {
        return process;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        return reader.read(buffer, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        output.write(bytes);
        output.flush();
    }

    @Override
    public void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return process.isAlive();
    }

    @Override
    public void resize(TermSize size) {
        if (process.isAlive() && size != null) {
            process.setWinSize(winSize(size.getColumns(), size.getRows()));
        }
    }

    /**
     * The size to tell the pty about, which is never nothing.
     *
     * <p>A terminal whose panel has been laid out with no room - a tool window collapsed to
     * the divider, a tab that is not the selected one - reports zero columns, and a pty told
     * it has zero columns is one the shell cannot write a prompt on. Linux takes the ioctl
     * and leaves the terminal looking alive and answering nothing. One column and one row
     * are not useful either, but they are a size, and the next real layout replaces them.
     */
    static WinSize winSize(int columns, int rows) {
        return new WinSize(Math.max(1, columns), Math.max(1, rows));
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return name;
    }

    /** Ends the shell. The blocked reader wakes up as the pty closes, and the emulator thread exits. */
    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
        }
        try {
            output.close();
        } catch (IOException ignored) {
            // Already gone with the process.
        }
    }
}
