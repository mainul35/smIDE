package com.smide.plugins.terminal;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

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
        styleScrollBars(this, awt);
    }

    /**
     * Makes JediTerm's Swing scrollbar look like the JavaFX ones around it.
     *
     * <p>Everything else in the window scrolls with a slim, buttonless bar; the terminal
     * is Swing, so it arrived with the platform's chunky one, complete with arrow buttons
     * and a light track that stood out against a dark terminal. Same colours, same width,
     * no buttons.
     */
    private void styleScrollBars(Container container, Color background) {
        for (Component child : container.getComponents()) {
            if (child instanceof JScrollBar bar) {
                ThemedSettingsProvider provider = (ThemedSettingsProvider) mySettingsProvider;
                Color thumb = provider.awt("scroll-thumb", 0xae, 0xbb, 0xc9);
                Color hover = provider.awt("accent", 0x0b, 0x6e, 0x7f);
                bar.setUI(new SlimScrollBarUI(thumb, hover, background));
                bar.setPreferredSize(new Dimension(SCROLLBAR_WIDTH, bar.getPreferredSize().height));
                bar.setUnitIncrement(16);
                bar.setBorder(null);
                bar.setOpaque(true);
                bar.setBackground(background);
            } else if (child instanceof Container nested) {
                styleScrollBars(nested, background);
            }
        }
    }

    private static final int SCROLLBAR_WIDTH = 12;

    /** A track that is just the background and a rounded thumb, with no arrow buttons. */
    private static final class SlimScrollBarUI extends BasicScrollBarUI {

        private final Color thumb;
        private final Color hover;
        private final Color track;

        SlimScrollBarUI(Color thumb, Color hover, Color track) {
            this.thumb = thumb;
            this.hover = hover;
            this.track = track;
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroSized();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroSized();
        }

        /** BasicScrollBarUI insists on a button; one with no size is how you get none. */
        private static JButton zeroSized() {
            JButton button = new JButton();
            Dimension none = new Dimension(0, 0);
            button.setPreferredSize(none);
            button.setMinimumSize(none);
            button.setMaximumSize(none);
            button.setBorder(null);
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
            g.setColor(track);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() || isDragging ? hover : thumb);
            int inset = 3;
            int width = Math.max(1, bounds.width - inset * 2);
            g2.fillRoundRect(bounds.x + inset, bounds.y + 2, width, Math.max(8, bounds.height - 4), width, width);
            g2.dispose();
        }
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
