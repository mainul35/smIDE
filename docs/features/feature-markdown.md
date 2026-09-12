# Markdown Support Feature

## Overview

Markdown support includes raw, split, and preview modes with PlantUML, Mermaid, and chart rendering. Uses MDViewer library.

## Component Structure

```
plugins/smide-plugin-markdown/src/main/java/com/smide/plugin/markdown/
├── MarkdownPlugin.java               # Markdown plugin registration
├── MarkdownLanguageProvider.java     # Registers Markdown language
├── MarkdownEditor.java               # Markdown editor component
├── MarkdownPreviewPanel.java         # Preview panel
├── MarkdownSplitPanel.java           # Split view (edit + preview)
├── PlantUmlRenderer.java             # PlantUML rendering
├── MermaidRenderer.java              # Mermaid rendering
├── ChartRenderer.java                # Chart rendering
└── MarkdownSyntaxHighlighter.java    # Markdown syntax highlighting
```

## Key Classes

### MarkdownPlugin
- Registers Markdown language support
- Provides editor extensions
- Integrates with MDViewer

### MarkdownEditor
- Main Markdown editor component
- Supports raw, split, and preview modes
- Handles PlantUML/Mermaid rendering

### PlantUmlRenderer
- Renders PlantUML diagrams in preview
- Handles diagram code blocks
- Provides interactive diagram viewing

## Data Flow

```
User opens .md file
    ↓
MarkdownLanguageProvider detects Markdown
    ↓
MarkdownEditor is created
    ↓
User switches to preview mode
    ↓
MarkdownEditor renders Markdown to HTML
    ↓
PlantUmlRenderer/MermaidRenderer render diagrams
    ↓
HTML is displayed in preview panel
```

## Related Features

### Code Editor
Markdown support extends the editor:
- **Specialized Editor**: `MarkdownEditor` extends base editor
- **Syntax Highlighting**: Markdown-specific highlighting
- **Preview Mode**: Live preview of Markdown content
- **Diagram Rendering**: PlantUML, Mermaid, charts

**Key Integration Point**: `EditorService` → `MarkdownPlugin`

### Language Intelligence (LSP)
Markdown support includes basic LSP:
- **Markdown LSP**: Provides Markdown checking
- **Basic Completion**: Completion for markdown links
- **Diagnostics**: Markdown syntax errors

**Key Integration Point**: `MarkdownPlugin` → `LspManager`

### Plugin System
Markdown support is a plugin:
- **Language Provider**: Registers Markdown language
- **Editor Extension**: Provides Markdown editor
- **Renderer**: Provides PlantUML/Mermaid rendering

**Key Integration Point**: `MarkdownPlugin` implements multiple extension points

### Search & Navigation
Markdown support integrates with search:
- **Search in Markdown**: Search within Markdown files
- **Go to Link**: Navigate to linked files
- **Symbol Search**: Search Markdown headings

**Key Integration Point**: `MarkdownPlugin` → `SearchService`

### Terminal
Markdown support integrates with terminal:
- **Render Commands**: Run PlantUML/Mermaid rendering commands
- **External Tools**: Use external tools for rendering
- **Output Display**: Display rendered output

**Key Integration Point**: `MarkdownPlugin` → `TerminalService`

## Extension Points

### For Plugin Developers

1. **Renderer**: Add custom diagram renderer
2. **Preview Extension**: Add features to preview mode
3. **Syntax Highlighting**: Customize Markdown highlighting

## How to Modify

### To add a new diagram type
1. Create a renderer class (e.g., `GraphvizRenderer`)
2. Implement rendering logic
3. Register in MarkdownEditor
4. Test by using the diagram type

## Known Limitations

- Depends on MDViewer library
- PlantUML/Mermaid rendering requires internet (first time)
- No live preview in v1 (manual refresh)

## See Also

- [Code Editor](feature-editor.md) - Editor integration
- [Language Intelligence](feature-lsp.md) - Markdown LSP
- [Plugin System](feature-plugins.md) - Markdown plugin structure
- [Search & Navigation](feature-search.md) - Search in Markdown
