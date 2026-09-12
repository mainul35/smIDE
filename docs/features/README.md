# smIDE Feature Architecture Documentation

This directory contains detailed architecture documentation for each major feature in smIDE. Each feature document explains:

- **Component Structure**: Directory layout and key classes
- **Data Flow**: How the feature works step-by-step
- **Related Features**: How this feature integrates with others
- **Extension Points**: How to extend the feature
- **How to Modify**: Guide for making changes
- **Known Limitations**: Current limitations and technical debt

## Feature Documents

| Feature | Description | Key Components |
|---------|-------------|----------------|
| [Code Editor](feature-editor.md) | Syntax highlighting, editing, line numbers | `smide.editor` package |
| [Language Intelligence](feature-lsp.md) | LSP client for language features | `smide.lsp` package |
| [Java Support](feature-java.md) | Maven/Gradle, JDT LS, run configurations | `smide-plugin-java` |
| [Debugging](feature-debugger.md) | Breakpoints, call stack, variables | `smide.debug` package |
| [Git Integration](feature-git.md) | Changes, log, diffs, branches | `smide.git` package |
| [Terminal](feature-terminal.md) | Shell integration | `smide.terminal` package |
| [Plugin System](feature-plugins.md) | Extensibility architecture | `smide.api` package |
| [Search & Navigation](feature-search.md) | Search Everywhere, Go to File, etc. | `smide.search` package |
| [Markdown Support](feature-markdown.md) | Markdown editing and preview | `smide-plugin-markdown` |
| [Deployment](feature-deploy.md) | Docker, SCP, Spring Boot health | `smide-plugin-java/deploy` |

## Navigation Guide

### For New Developers
1. Start with **[Plugin System](feature-plugins.md)** to understand extensibility
2. Read **[Code Editor](feature-editor.md)** to understand the core UI
3. Read **[Language Intelligence](feature-lsp.md)** to understand language support
4. Read feature-specific sections for areas you're working on

### For Experienced Developers
1. Jump to the feature you're working on
2. Read the "How to Modify" section for your change
3. Refer to "Related Features" for integration points

### For Architects
1. Read all sections to understand the full system
2. Focus on "Data Flow" sections for integration points
3. Review "Related Features" for cross-cutting concerns

## Feature Relationships

```
┌─────────────────────────────────────────────────────────────────┐
│                        Plugin System                            │
│  (Extensibility architecture - all features are plugins)       │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                     Code Editor                                 │
│  (Core UI - consumer of all other features)                   │
└─────────────────────────────────────────────────────────────────┘
         ↓              ↓              ↓              ↓
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ Language     │ │ Git          │ │ Terminal     │ │ Search &     │
│ Intelligence │ │ Integration  │ │              │ │ Navigation   │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
         ↓              ↓              ↓              ↓
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ Java         │ │ Debugging    │ │ Markdown     │ │ Deployment   │
│ Support      │ │              │ │ Support      │ │              │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
```

## Key Integration Points

### Editor ↔ All Features
The Code Editor is the primary consumer of all other features:
- Receives diagnostics from Language Intelligence
- Displays Git status in gutter
- Provides selection for Search
- Integrates with Debugging for breakpoints

### Plugin System ↔ All Features
All features are implemented as plugins:
- Each feature implements extension points
- Plugins are loaded via ServiceLoader
- Plugins can extend other plugins

### Language Intelligence ↔ Core Features
Language Intelligence provides data to:
- Code Editor (diagnostics, completion)
- Search & Navigation (symbols, usages)
- Debugging (source lookup)

## Change Impact Analysis

Before making a change, consider:

1. **Which feature is affected?** - Identify the relevant document
2. **Which components are involved?** - List the classes/files
3. **What are the related features?** - Check "Related Features" section
4. **What are the extension points?** - Can the change be made via plugins?
5. **What are the known limitations?** - Will the change hit any limits?

## See Also

- [arc42.md](../arc42.md) - High-level architecture overview
- [INSTALL.md](../INSTALL.md) - Installation guide
- [PLUGIN-GUIDE.md](../PLUGIN-GUIDE.md) - Plugin development guide

---

**Document Version**: 1.0  
**Last Updated**: 2026-09-13
