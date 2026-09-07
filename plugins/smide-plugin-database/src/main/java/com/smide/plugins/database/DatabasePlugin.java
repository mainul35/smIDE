package com.smide.plugins.database;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

/**
 * Database connections for smIDE: a tool window that browses a MySQL server and a
 * console for running SQL against it.
 *
 * <p>The driver is bundled, so a connection needs nothing installed. Passwords are asked
 * for and held in memory; only the address and the user name are saved.
 */
public final class DatabasePlugin implements Plugin {

    private Databases databases;

    @Override
    public void start(PluginContext context) {
        Ide ide = context.ide();
        databases = new Databases(ide);
        DatabaseUi ui = new DatabaseUi(ide, databases);
        DatabaseToolWindow toolWindow = new DatabaseToolWindow(ui);
        context.registerToolWindow(toolWindow);

        context.registerAction(Action.of("database.show", "Database").menu("Tools")
                .icon("fth-database").order(50)
                .perform(ctx -> ide.toolWindows().show(DatabaseToolWindow.ID)));
        context.registerAction(Action.of("database.newConnection", "New Database Connection...")
                .menu("Tools").order(51)
                .perform(ctx -> {
                    ide.toolWindows().show(DatabaseToolWindow.ID);
                    new ConnectionDialog(ui).show(null, source -> {
                        databases.add(source);
                        toolWindow.refresh();
                    });
                }));
    }

    @Override
    public void stop() {
        if (databases != null) {
            databases.closeAll();
        }
    }
}
