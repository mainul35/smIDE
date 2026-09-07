package com.smide.plugins.git;

import com.smide.api.ui.Theme;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;

import java.util.List;

/**
 * A unified diff, read-only, with added and removed lines coloured.
 *
 * <p>The colours are style classes rather than inline styles so they follow the theme;
 * the rules live in the plugin's own small stylesheet, which is put on this node's
 * subtree along with the application's.
 */
public final class DiffView extends VirtualizedScrollPane<StyleClassedTextArea> {

    private final StyleClassedTextArea area;

    public DiffView(Theme theme) {
        this(new StyleClassedTextArea(), theme);
    }

    private DiffView(StyleClassedTextArea area, Theme theme) {
        super(area);
        this.area = area;
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("diff-area");
        getStylesheets().add(theme.stylesheet());
        String own = DiffView.class.getResource("/css/git.css").toExternalForm();
        getStylesheets().add(own);
        theme.style(this);
    }

    /** Replaces the content; {@code null} or blank shows a placeholder line. */
    public void setDiff(String unified) {
        area.clear();
        if (unified == null || unified.isBlank()) {
            append("No differences.\n", "diff-meta");
            return;
        }
        for (String line : unified.split("\n", -1)) {
            String style;
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ")
                    || line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file")) {
                style = "diff-meta";
            } else if (line.startsWith("@@")) {
                style = "diff-hunk";
            } else if (line.startsWith("+")) {
                style = "diff-add";
            } else if (line.startsWith("-")) {
                style = "diff-remove";
            } else {
                style = "diff-context";
            }
            append(line + "\n", style);
        }
        area.moveTo(0);
        area.requestFollowCaret();
    }

    private void append(String text, String styleClass) {
        int start = area.getLength();
        area.appendText(text);
        area.setStyle(start, area.getLength(), List.of(styleClass));
    }
}
