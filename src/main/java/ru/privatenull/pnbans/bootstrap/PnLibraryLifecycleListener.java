package ru.privatenull.pnbans.bootstrap;

import ru.privatenull.pnbans.integration.Log;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import ru.privatenull.pnbans.PnBansPlugin;

/** Keeps pnCases from outliving the shared runtime during shutdown or manual unload. */
public final class PnLibraryLifecycleListener implements Listener {

    private final PnBansPlugin plugin;

    public PnLibraryLifecycleListener(PnBansPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!event.getPlugin().getName().equalsIgnoreCase("pnLibrary") || !plugin.isEnabled()) return;
        Log.warning("pnLibrary отключается; pnCases будет безопасно остановлен первым.");
        plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
}
