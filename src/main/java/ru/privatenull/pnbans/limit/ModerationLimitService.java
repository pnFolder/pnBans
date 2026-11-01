package ru.privatenull.pnbans.limit;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import ru.privatenull.pnbans.PnBansPlugin;
import ru.privatenull.pnbans.model.PunishmentType;
import ru.privatenull.pnbans.util.DurationParser;
import ru.privatenull.pnbans.util.TimeFormat;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import ru.privatenull.pnbans.integration.Cooldowns;
import java.util.concurrent.ConcurrentHashMap;

/** Configurable cooldowns and maximum durations. Vault is detected by reflection, so it remains optional. */
public final class ModerationLimitService {
    private final PnBansPlugin plugin;
    public ModerationLimitService(PnBansPlugin plugin) { this.plugin = plugin; }
    public Result validate(CommandSender sender, PunishmentType type, DurationParser.DurationValue requested) {
        if (!(sender instanceof Player player)) return Result.pass();
        Rule rule = ruleFor(player, type);
        long requestedMs = requested.duration().toMillis();
        if (rule.maxDurationMs > 0 && requestedMs > rule.maxDurationMs) return Result.tooLong(TimeFormat.duration(new DurationParser.DurationValue(java.time.Duration.ofMillis(rule.maxDurationMs), rule.maxDurationText)));
        long remaining = Cooldowns.remainingMillis(player.getUniqueId(), cooldownAction(type));
        return remaining > 0 ? Result.cooldown(TimeFormat.remaining(System.currentTimeMillis() + remaining, System.currentTimeMillis())) : Result.pass();
    }
    public void record(CommandSender sender, PunishmentType type) {
        if (sender instanceof Player player) Cooldowns.set(player.getUniqueId(), cooldownAction(type), ruleFor(player, type).cooldownMs);
    }
    private static String cooldownAction(PunishmentType type) { return "moderation-" + type.name().toLowerCase(Locale.ROOT); }
    private Rule ruleFor(Player player, PunishmentType type) {
        String group = vaultGroup(player);
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("moderation-limits.vault-groups." + group);
        if (root == null) root = plugin.getConfig().getConfigurationSection("moderation-limits.default");
        if (root == null) return new Rule(0L, 0L, "0s");
        ConfigurationSection section = root.getConfigurationSection(type.name().toLowerCase(Locale.ROOT));
        if (section == null && type == PunishmentType.IP_BAN) section = root.getConfigurationSection("ban");
        if (section == null) return new Rule(0L, 0L, "0s");
        String cooldown = section.getString("cooldown", "0s"); String maximum = section.getString("max-duration", "0s");
        return new Rule(millis(cooldown), millis(maximum), maximum);
    }
    private long millis(String raw) { return DurationParser.parse(raw).map(value -> value.duration().toMillis()).orElse(0L); }
    private String vaultGroup(Player player) {
        try {
            Class<?> permission = Class.forName("net.milkbowl.vault.permission.Permission");
            Object provider = Bukkit.getServicesManager().load(permission);
            if (provider == null) return "default";
            Object group = permission.getMethod("getPrimaryGroup", String.class, OfflinePlayer.class).invoke(provider, null, player);
            return group == null ? "default" : group.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) { return "default"; }
    }
    private record Rule(long cooldownMs, long maxDurationMs, String maxDurationText) { }
    public record Result(String messageKey, String value) {
        static Result pass() { return new Result(null, ""); }
        static Result cooldown(String value) { return new Result("punishment-cooldown", value); }
        static Result tooLong(String value) { return new Result("punishment-max-duration", value); }
        public boolean allowed() { return messageKey == null; }
    }
}
