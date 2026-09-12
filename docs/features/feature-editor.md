# Code Editor Feature

## Overview

The code editor provides the core editing experience with syntax highlighting, line numbers, bracket matching, auto-indent, comment toggling, find and replace, go to line, and undo history.

## Component Structure

```
smide-core/src/main/java/com/smide/editor/
├── EditorService.java              # Manages open files and editor instances
├── CodeEditor.java                 # Main editor component (JavaFX Node)
├── EditorDocument.java             # Represents an open file
├── EditorInput.java                # File input stream management
├── SyntaxHighlighter.java          # Syntax highlighting engine
├── LineNumberGutter.java           # Line number display
├── BracketMatcher.java             # Bracket/brace matching
├── AutoIndent.java                  # Auto-indentation logic
├── CommentToggle.java               # Comment/uncomment lines
├── MoveLine.java                    # Move lines up/down
├── DuplicateLine.java               # Duplicate current line
├── FindInFile.java                  # Find and replace in current file
├── GoToLine.java                    # Jump to specific line
└── UndoManager.java                 # Per-document undo history
```

## Key Classes

### EditorService
- Manages the lifecycle of all open editors
- Coordinates between editor instances and the workspace
- Handles file open/save operations

### CodeEditor
- Main JavaFX component for code editing
- Composes: syntax highlighter, line numbers, gutter, text area
- Handles keyboard shortcuts and mouse events
- Provides API for plugins to extend editor behavior

### EditorDocument
- Represents an open file in the editor
- Manages document content and metadata (file path, modification state)
- Provides undo/redo functionality
- Notifies listeners of content changes

## Data Flow

```
User types in editor
    ↓
CodeEditor captures input
    ↓
EditorDocument updates content
    ↓
SyntaxHighlighter re-highlights changed region
    ↓
LineNumberGutter updates if line count changed
    ↓
UndoManager records change for undo
    ↓
LSP Client (if language server available) sends didChange notification
```

## Related Features

### Language Intelligence (LSP)
The editor is the primary consumer of LSP features. When the user types, the editor:
1. Captures the input
2. Sends it to the LSP Client via `textDocument/didChange`
3. Receives diagnostics and updates underlines
4. Provides completion items when user requests completion

**Key Integration Point**: `EditorService` → `LspManager`

### Plugin System
The editor exposes extension points for plugins:
- **Syntax Highlighting**: Implement `SyntaxHighlighter` interface
- **Editor Actions**: Register actions via `EditorAction` extension point
- **Gutter Widgets**: Add custom widgets to the line number gutter
- **Completion Providers**: Implement `CompletionProvider` for custom completions

**Key Integration Point**: `CodeEditor` → Plugin extensions

### Git Integration
The editor integrates with Git for:
- Displaying line status (modified, added, deleted) in the gutter
- Showing inline diffs for modified lines
- Providing "stage hunk" functionality

**Key Integration Point**: `LineNumberGutter` → `GitService`

### Search & Navigation
The editor provides:
- **Find in File**: Opens FindInFile dialog for current file
- **Go to Line**: Jumps to specific line number
- **Selection**: Provides selected text for search operations

**Key Integration Point**: `CodeEditor` → `SearchService`

### Markdown Support
Markdown files use a specialized editor (`MarkdownEditor`) that extends the base editor with:
- Markdown syntax highlighting
- Live preview mode
- PlantUML/Mermaid rendering

**Key Integration Point**: `EditorService` → `MarkdownPlugin`

## Extension Points

### For Plugin Developers

1. **Syntax Highlighting**: Implement `SyntaxHighlighter` interface to add language support
2. **Editor Actions**: Register actions via `EditorAction` extension point
3. **Gutter Widgets**: Add custom widgets to the line number gutter
4. **Completion Providers**: Implement `CompletionProvider` for custom completions

## How to Modify

### To add a new editor feature
1. Create a new class in `smide.editor` package
2. Implement the relevant interface (e.g., `EditorExtension`)
3. Register the extension in your plugin's `plugin.json` or via ServiceLoader
4. Test by opening a file and verifying the feature works

### To modify existing behavior
1. Locate the relevant class (e.g., `AutoIndent.java` for indentation)
2. Make changes and run tests
3. Ensure backward compatibility with existing plugins

## Known Limitations

- RichTextFX has limitations with very large files (>100k lines)
- Syntax highlighting is line-by-line (no multi-line parsing)
- Undo history is per-document (not cross-document)

## See Also

- [Language Intelligence](feature-lsp.md) - How LSP integrates with the editor
- [Plugin System](feature-plugins.md) - How to extend the editor
- [Git Integration](feature-git.md) - Git gutter integration
- [Search & Navigation](feature-search.md) - Search in file integration
