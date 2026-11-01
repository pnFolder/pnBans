package ru.privatenull.pnbans.listener;

import ru.privatenull.pnbans.integration.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import ru.privatenull.pnbans.PnBansPlugin;
import ru.privatenull.pnbans.effect.PunishmentEffects;
import ru.privatenull.pnbans.model.Punishment;
import ru.privatenull.pnbans.model.PunishmentType;
import ru.privatenull.pnbans.storage.PunishmentService;
import ru.privatenull.pnbans.util.MessageUtil;
import ru.privatenull.pnbans.util.TimeFormat;

import java.util.List;
import java.util.Map;

public final class PunishmentListener implements Listener {

    private final PnBansPlugin plugin;
    private final PunishmentService service;
    private final PunishmentEffects effects;
    private volatile String bypassPermission;
    private volatile String mutedMessage;

    public PunishmentListener(PnBansPlugin plugin, PunishmentService service, PunishmentEffects effects) {
        this.plugin = plugin;
        this.service = service;
        this.effects = effects;
        reload();
    }

    public void reload() {
        bypassPermission = plugin.getConfig().getString("mute-bypass-permission", "pnbans.mute.bypass");
        mutedMessage = MessageUtil.lines(plugin, "muted-chat-message", List.of("&cВы не можете писать в чат."), Map.of());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();
        Punishment punishment = service.getPlayerPunishment(event.getUniqueId(), event.getName(), PunishmentType.BAN);
        if (punishment == null) punishment = service.getIpPunishment(ip);
        if (punishment == null) return;

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banMessage(punishment));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(bypassPermission)) return;
        Punishment mute = service.getPlayerPunishment(player.getUniqueId(), player.getName(), PunishmentType.MUTE);
        if (mute == null) return;

        event.setCancelled(true);
        String message = mutedMessage.replace("{remaining}", TimeFormat.remaining(mute.expiresAt(), System.currentTimeMillis()))
                .replace("{reason}", mute.reason());
        Tasks.sync(() -> { if (player.isOnline()) { player.sendMessage(MessageUtil.color(message)); effects.muteAttempt(player); } });
    }

    private String banMessage(Punishment punishment) {
        return MessageUtil.lines(plugin, "ban", List.of(
                "&c&lВЫ БЫЛИ ЗАБЛОКИРОВАНЫ",
                "",
                "&7Причина: &f{reason}",
                "&7Срок: &f{duration}",
                "&7Выдал: &f{actor}"), Map.of(
                "reason", punishment.reason(),
                "duration", TimeFormat.remaining(punishment.expiresAt(), System.currentTimeMillis()),
                "actor", punishment.actor()));
    }
}
