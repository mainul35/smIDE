package com.smide.plugins.terminal;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;

import java.awt.Color;

/**
 * {@link JediTermWidget} with two things the stock widget keeps to itself: the style
 * state, so a theme change can recolour text that is already on screen, and the panel's
 * font re-initialisation, so a font size change applies without a restart.
 *
 * <p>Swing component: every method runs on the Swing event dispatch thread.
 */
final class SmideTerminalWidget extends JediTermWidget {

    /** Captured from {@link #createDefaultStyle()}, which the super constructor calls. */
    private StyleState styleState;

    SmideTerminalWidget(int columns, int rows, ThemedSettingsProvider settings) {
        super(columns, rows, settings);
        applyChrome();
    }

    @Override
    protected StyleState createDefaultStyle() {
        StyleState state = super.createDefaultStyle();
        styleState = state;
        return state;
    }

    @Override
    protected TerminalPanel createTerminalPanel(SettingsProvider settings, StyleState style, TerminalTextBuffer buffer) {
        return new Panel(settings, buffer, style);
    }

    /** Pushes the provider's current colours into the running terminal and repaints. */
    void restyle() {
        TextStyle style = ((ThemedSettingsProvider) mySettingsProvider).currentDefaultStyle();
        if (styleState != null) {
            styleState.setDefaultStyle(style);
        }
        applyChrome();
        ((Panel) getTerminalPanel()).refreshFont();
        getTerminalPanel().repaint();
    }

    /** Colours the widget's own surface so that gaps around the panel match the terminal. */
    private void applyChrome() {
        com.jediterm.core.Color bg = mySettingsProvider.getTerminalColorPalette()
                .getBackground(mySettingsProvider.getDefaultBackground());
        Color awt = new Color(bg.getRed(), bg.getGreen(), bg.getBlue());
        setBackground(awt);
        setOpaque(true);
        getTerminalPanel().setBackground(awt);
    }

    /** Exposes the protected font re-initialisation. */
    private static final class Panel extends TerminalPanel {
        Panel(SettingsProvider settings, TerminalTextBuffer buffer, StyleState style) {
            super(settings, buffer, style);
        }

        void refreshFont() {
            reinitFontAndResize();
        }
    }
}
