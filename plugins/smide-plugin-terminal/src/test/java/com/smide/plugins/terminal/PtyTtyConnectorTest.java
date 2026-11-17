package com.smide.plugins.terminal;

import com.pty4j.WinSize;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The size handed to the pty.
 *
 * <p>No process and no emulator: what can be wrong here is the arithmetic, and starting a
 * shell to find that out would make a unit test into a machine-dependent one.
 */
class PtyTtyConnectorTest {

    @Test
    void anOrdinarySizeIsPassedStraightThrough() {
        WinSize size = PtyTtyConnector.winSize(120, 30);
        assertEquals(120, size.getColumns());
        assertEquals(30, size.getRows());
    }

    @Test
    void aPanelWithNoRoomIsStillASize() {
        // A collapsed tool window and an unselected tab both lay out at nothing, and a pty
        // told it has no columns is one the shell cannot write a prompt on.
        WinSize none = PtyTtyConnector.winSize(0, 0);
        assertEquals(1, none.getColumns());
        assertEquals(1, none.getRows());
    }

    @Test
    void andNeitherDimensionIsClampedByTheOther() {
        assertEquals(80, PtyTtyConnector.winSize(80, 0).getColumns());
        assertEquals(1, PtyTtyConnector.winSize(80, 0).getRows());
        assertEquals(1, PtyTtyConnector.winSize(0, 24).getColumns());
        assertEquals(24, PtyTtyConnector.winSize(0, 24).getRows());
    }

    @Test
    void aNegativeSizeIsNotPassedOnEither() {
        // JediTerm computes columns from a width and a character width; a width it has not
        // measured yet has come back negative.
        assertEquals(1, PtyTtyConnector.winSize(-5, -5).getColumns());
        assertEquals(1, PtyTtyConnector.winSize(-5, -5).getRows());
    }
}
