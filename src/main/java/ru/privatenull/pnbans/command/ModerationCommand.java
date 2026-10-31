package ru.privatenull.pnbans.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import ru.privatenull.pnbans.PnBansPlugin;
import ru.privatenull.pnbans.dupeip.DupeIpService;
import ru.privatenull.pnbans.effect.PunishmentEffects;
import ru.privatenull.pnbans.gui.DupeIpGui;
import ru.privatenull.pnbans.gui.HistoryGui;
import ru.privatenull.pnbans.gui.StaffGui;
import ru.privatenull.pnbans.limit.ModerationLimitService;
import ru.privatenull.pnbans.model.Punishment;
import ru.privatenull.pnbans.integration.PnLibraryRuntime;
import ru.privatenull.pnbans.model.PunishmentType;
import ru.privatenull.pnbans.storage.PunishmentService;
import ru.privatenull.pnbans.util.DurationParser;
import ru.privatenull.pnbans.util.MessageUtil;
import ru.privatenull.pnbans.util.TimeFormat;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ModerationCommand implements CommandExecutor, TabCompleter {

    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern IP_LITERAL = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+$");
    private static final List<String> DURATION_SUGGESTIONS = List.of("30m", "1h", "6h", "1d", "7d", "30d", "100d");

    private final PnBansPlugin plugin;
    private final PunishmentService service;
    private final HistoryGui historyGui;
    private final StaffGui staffGui;
    private final DupeIpService dupeIpService;
    private final DupeIpGui dupeIpGui;
    private final PunishmentEffects effects;
    private final ModerationLimitService limits;

    public ModerationCommand(PnBansPlugin plugin, PunishmentService service, HistoryGui historyGui, StaffGui staffGui, DupeIpService dupeIpService, DupeIpGui dupeIpGui, PunishmentEffects effects, ModerationLimitService limits) {
        this.plugin = plugin;
        this.service = service;
        this.historyGui = historyGui;
        this.staffGui = staffGui;
        this.dupeIpService = dupeIpService;
        this.dupeIpGui = dupeIpGui;
        this.effects = effects;
        this.limits = limits;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "ban" -> punish(sender, args, PunishmentType.BAN, "pnbans.ban", "/ban <ник> <время> <правило|причина>");
            case "mute" -> punish(sender, args, PunishmentType.MUTE, "pnbans.mute", "/mute <ник> <время> <правило|причина>");
            case "warn" -> punish(sender, args, PunishmentType.WARN, "pnbans.warn", "/warn <ник> <время> <правило|причина>");
            case "ipban" -> ipBan(sender, args);
            case "unban" -> revoke(sender, args, PunishmentType.BAN, "pnbans.unban", "unbanned", "not-banned", "/unban <ник>");
            case "unmute" -> revoke(sender, args, PunishmentType.MUTE, "pnbans.unmute", "unmuted", "not-muted", "/unmute <ник>");
            case "unwarn" -> revoke(sender, args, PunishmentType.WARN, "pnbans.unwarn", "unwarned", "not-warned", "/unwarn <ник>");
            case "unipban" -> unipBan(sender, args);
            case "history" -> history(sender, args);
            case "dupeip" -> dupeIp(sender, args);
            case "pnbans" -> admin(sender, args);
            default -> false;
        };
    }

    private boolean punish(CommandSender sender, String[] args, PunishmentType type, String permission, String usage) {
        if (!require(sender, permission)) return true;
        SilentArguments parsed = extractSilent(args);
        Input input = parseInput(sender, parsed.arguments(), type, usage);
        if (input == null || !canPunish(sender, input.target())) return true;
        ModerationLimitService.Result limit = limits.validate(sender, type, input.duration());
        if (!limit.allowed()) { message(sender, limit.messageKey(), Map.of("value", limit.value())); return true; }
        try {
            if (type != PunishmentType.WARN && service.getPlayerPunishment(input.target().uuid(), input.target().name(), type) != null) {
                service.revokePlayer(input.target().uuid(), input.target().name(), type, sender.getName());
            }
            Punishment punishment = create(input.target(), null, type, input.reason().full(), sender.getName(), input.duration());
            if (type == PunishmentType.BAN) {
                Player target = Bukkit.getPlayerExact(input.target().name());
                if (target != null) effects.ban(target, banMessage(punishment));
            } else {
                if (parsed.silent()) {
                    notifyTarget(input.target().name(), type == PunishmentType.MUTE ? "target-muted" : "target-warned", Map.of("reason", punishment.reason()));
                }
                if (type == PunishmentType.MUTE) {
                    Player target = Bukkit.getPlayerExact(input.target().name());
                    if (target != null) effects.muteIssued(target);
                }
            }
            limits.record(sender, type);
            announce(punishment, parsed.silent(), sender);
            if (type == PunishmentType.WARN) applyEscalation(input.target(), sender.getName());
        } catch (Exception exception) {
            databaseError(sender, exception);
        }
        return true;
    }

    private boolean ipBan(CommandSender sender, String[] args) {
        if (!require(sender, "pnbans.ban.ip")) return true;
        SilentArguments parsed = extractSilent(args);
        args = parsed.arguments();
        if (args.length < 3) return usage(sender, "/ipban <ник|ip> <время> <причина>");
        String ip = resolveIp(args[0]);
        if (ip == null) {
            message(sender, "ip-not-found", Map.of());
            return true;
        }
        Optional<DurationParser.DurationValue> duration = DurationParser.parse(args[1]);
        if (duration.isEmpty()) return invalidDuration(sender);
        ModerationLimitService.Result limit = limits.validate(sender, PunishmentType.IP_BAN, duration.get());
        if (!limit.allowed()) { message(sender, limit.messageKey(), Map.of("value", limit.value())); return true; }
        Reason reason = resolveReason(PunishmentType.IP_BAN, Arrays.copyOfRange(args, 2, args.length));
        try {
            if (service.getIpPunishment(ip) != null) {
                service.revokeIp(ip, sender.getName());
            }
            Punishment punishment = create(new Target(null, "IP-адрес"), ip, PunishmentType.IP_BAN, reason.full(), sender.getName(), duration.get());
            limits.record(sender, PunishmentType.IP_BAN);
            announce(punishment, parsed.silent(), sender);
        } catch (Exception exception) {
            databaseError(sender, exception);
        }
        return true;
    }

    private boolean revoke(CommandSender sender, String[] args, PunishmentType type, String permission, String success, String absent, String usage) {
        if (!require(sender, permission)) return true;
        Target target = target(sender, args, usage);
        if (target == null) return true;
        try {
            if (!service.revokePlayer(target.uuid(), target.name(), type, sender.getName())) {
                message(sender, absent, Map.of("player", target.name()));
                return true;
            }
            notifyTarget(target.name(), "target-punishment-removed", Map.of());
            message(sender, success, Map.of("player", target.name()));
        } catch (Exception exception) {
            databaseError(sender, exception);
        }
        return true;
    }

    private boolean unipBan(CommandSender sender, String[] args) {
        if (!require(sender, "pnbans.unban.ip")) return true;
        if (args.length != 1) return usage(sender, "/unipban <ник|ip>");
        String ip = resolveIp(args[0]);
        if (ip == null) {
            message(sender, "ip-not-found", Map.of());
            return true;
        }
        try {
            if (!service.revokeIp(ip, sender.getName())) {
                message(sender, "not-ip-banned", Map.of("ip", ip));
                return true;
            }
            message(sender, "ip-unbanned", Map.of("ip", ip));
        } catch (Exception exception) {
            databaseError(sender, exception);
        }
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (!require(sender, "pnbans.history")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("staff")) {
            if (!(sender instanceof Player player)) { message(sender, "only-player", Map.of()); return true; }
            staffGui.open(player, 0);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("staff")) {
            if (!(sender instanceof Player player)) {
                message(sender, "only-player", Map.of());
                return true;
            }
            if (!PLAYER_NAME.matcher(args[1]).matches()) {
                message(sender, "invalid-player", Map.of());
                return true;
            }
            historyGui.openForActor(player, args[1], 0);
            return true;
        }
        Target target = target(sender, args, "/history <ник>");
        if (target == null) return true;
        if (!(sender instanceof Player player)) {
            message(sender, "only-player", Map.of());
            return true;
        }
        historyGui.open(player, target.uuid(), target.name(), 0);
        return true;
    }

    private boolean dupeIp(CommandSender sender, String[] args) {
        if (!require(sender, "pnbans.dupeip")) return true;
        if (!plugin.getConfig().getBoolean("dupeip.enabled", true)) {
            message(sender, "dupeip-disabled", Map.of());
            return true;
        }
        Target target = target(sender, args, "/dupeip <ник>");
        if (target == null) return true;
        if (sender instanceof Player player) {
            dupeIpGui.open(player, target.uuid(), target.name());
        } else {
            message(sender, "dupeip-loading", Map.of("player", target.name()));
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    DupeIpService.Analysis analysis = dupeIpService.analyze(target.uuid(), target.name());
                    Bukkit.getScheduler().runTask(plugin, () -> sendDupeIpReport(sender, analysis));
                } catch (Exception exception) {
                    Bukkit.getScheduler().runTask(plugin, () -> databaseError(sender, exception));
                }
            });
        }
        return true;
    }

    private void sendDupeIpReport(CommandSender sender, DupeIpService.Analysis analysis) {
        if (analysis.status() == DupeIpService.Status.NO_PROFILE) {
            message(sender, "dupeip-no-profile", Map.of("player", analysis.name()));
            return;
        }
        if (analysis.status() == DupeIpService.Status.NO_IP) {
            message(sender, "dupeip-no-ip", Map.of("player", analysis.name()));
            return;
        }

        DupeIpService.Match strongest = analysis.matches().stream().findFirst().orElse(null);
        Map<String, String> values = new java.util.HashMap<>();
        values.put("player", analysis.name());
        values.put("ip", analysis.ip());
        values.put("country", analysis.country());
        values.put("known_ips", Integer.toString(analysis.knownIpCount()));
        values.put("links", Integer.toString(analysis.matches().size()));
        values.put("same_ip", Integer.toString(analysis.sameIpCount()));
        values.put("similar", Integer.toString(analysis.similarNameCount()));
        values.put("active_bans", Integer.toString(analysis.activeBanCount()));
        values.put("active_mutes", Integer.toString(analysis.activeMuteCount()));
        values.put("risk", Integer.toString(analysis.highestRiskScore()));
        values.put("risk_level", analysis.highestRiskLevel());
        values.put("related_player", strongest == null ? "Не найден" : strongest.profile().name());
        values.put("shared_ips", strongest == null || strongest.sharedIps().isEmpty()
                ? "нет" : String.join(", ", strongest.sharedIps()));
        values.put("evidence", strongest == null || strongest.signals().isEmpty()
                ? "Совпадений не найдено" : String.join("; ", strongest.signals()));
        message(sender, "dupeip-report", values);
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!require(sender, "pnbans.admin")) return true;
            String current = plugin.getDescription().getVersion();
            MenuUpdateInfo update = menuUpdateInfo(current);
            message(sender, "pnbans-info", Map.of(
                    "version", current,
                    "database", plugin.getConfig().getString("database.type", "SQLITE").toUpperCase(Locale.ROOT),
                    "discord", plugin.getSupportDiscord(),
                    "support", plugin.getSupportDiscord(),
                    "update", update.status(),
                    "latest", update.latest(),
                    "download", update.download()
            ));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!require(sender, "pnbans.admin")) return true;
            plugin.reloadPlugin();
            message(sender, "reload", Map.of());
        } else usage(sender, "/pnbans reload");
        return true;
    }

    private MenuUpdateInfo menuUpdateInfo(String current) {
        PnLibraryRuntime runtime = plugin.getPnRuntime();
        if (runtime == null) return new MenuUpdateInfo("&7проверяется", "неизвестно", "");
        PnLibraryRuntime.UpdateInfo info = runtime.updateInfo(current);
        return new MenuUpdateInfo(info.status(), info.latest(), info.download());
    }

    private Input parseInput(CommandSender sender, String[] args, PunishmentType type, String usage) {
        if (args.length < 2) {
            usage(sender, usage);
            return null;
        }
        Target target = target(sender, new String[]{args[0]}, usage);
        if (target == null) return null;

        Optional<DurationParser.DurationValue> duration = DurationParser.parse(args[1]);
        if (duration.isPresent()) {
            if (args.length < 3) {
                usage(sender, usage);
                return null;
            }
            return new Input(target, duration.get(), resolveReason(type, Arrays.copyOfRange(args, 2, args.length)));
        }

        ReasonPreset preset = preset(type, args[1]);
        if (preset != null && preset.duration() != null) {
            String[] extra = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[0];
            return new Input(target, preset.duration(), preset.reason(extra));
        }

        invalidDuration(sender);
        return null;
    }

    private Target target(CommandSender sender, String[] args, String usage) {
        if (args.length != 1 || !PLAYER_NAME.matcher(args[0]).matches()) {
            if (args.length == 1) message(sender, "invalid-player", Map.of()); else usage(sender, usage);
            return null;
        }
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) return new Target(online.getUniqueId(), online.getName());
        OfflinePlayer offline = Bukkit.getOfflinePlayer(args[0]);
        // An unrecognized name has no reliable UUID yet; the name remains the enforcement key until first login.
        return new Target(offline.hasPlayedBefore() ? offline.getUniqueId() : null, args[0]);
    }

    private boolean canPunish(CommandSender sender, Target target) {
        if (sender instanceof Player player && player.getUniqueId().equals(target.uuid())) {
            message(sender, "self-target", Map.of());
            return false;
        }
        Player online = Bukkit.getPlayerExact(target.name());
        if (online != null && online.hasPermission("pnbans.exempt")) {
            message(sender, "target-exempt", Map.of());
            return false;
        }
        return true;
    }

    private Punishment create(Target target, String ip, PunishmentType type, String reason, String actor, DurationParser.DurationValue duration) throws Exception {
        long now = System.currentTimeMillis();
        long expiresAt = Math.addExact(now, duration.duration().toMillis());
        Punishment punishment = new Punishment(UUID.randomUUID().toString(), target.uuid(), target.name(), ip, type, reason, actor, now, expiresAt, true, null, 0L);
        service.create(punishment);
        return punishment;
    }

    private void applyEscalation(Target target, String actor) throws Exception {
        if (!plugin.getConfig().getBoolean("escalation.enabled", false)) return;
        if (service.getPlayerPunishment(target.uuid(), target.name(), PunishmentType.MUTE) != null) return;
        int threshold = plugin.getConfig().getInt("escalation.warning-count", 3);
        if (service.activeWarnings(target.uuid(), target.name()) < threshold) return;
        DurationParser.DurationValue duration = DurationParser.parse(plugin.getConfig().getString("escalation.mute-duration", "1d")).orElse(null);
        if (duration == null) return;
        create(target, null, PunishmentType.MUTE, plugin.getConfig().getString("escalation.reason", "Автоматический мут"), "pnBans", duration);
        notifyTarget(target.name(), "target-automatic-mute", Map.of("reason", plugin.getConfig().getString("escalation.reason")));
        Player online = Bukkit.getPlayerExact(target.name());
        if (online != null) effects.muteIssued(online);
    }

    private Reason resolveReason(PunishmentType type, String[] raw) {
        ReasonPreset preset = raw.length == 0 ? null : preset(type, raw[0]);
        if (preset != null) return preset.reason(Arrays.copyOfRange(raw, 1, raw.length));
        String suffix = raw.length > 1 ? " " + String.join(" ", Arrays.copyOfRange(raw, 1, raw.length)) : "";
        String fullReason = raw[0] + suffix;
        return new Reason(fullReason, fullReason);
    }

    private ReasonPreset preset(PunishmentType type, String code) {
        String typeKey = type.name().toLowerCase(Locale.ROOT);
        ConfigurationSection preset = plugin.getConfig().getConfigurationSection("punishment-presets." + typeKey + "." + code);
        if (preset != null) {
            DurationParser.DurationValue duration = DurationParser.parse(preset.getString("duration", "")).orElse(null);
            return new ReasonPreset(code, preset.getString("reason", code), duration);
        }

        Object typedTemplate = plugin.getConfig().get("reason-templates." + typeKey + "." + code);
        if (typedTemplate instanceof String typedReason) {
            return new ReasonPreset(code, typedReason, null);
        }

        Object flatTemplate = plugin.getConfig().get("reason-templates." + code);
        if (flatTemplate instanceof String flatReason) {
            return new ReasonPreset(code, flatReason, null);
        }
        return null;
    }

    private String resolveIp(String input) {
        if (IP_LITERAL.matcher(input).matches()) {
            try {
                return InetAddress.getByName(input).getHostAddress();
            } catch (Exception ignored) {
                return null;
            }
        }
        Player player = Bukkit.getPlayerExact(input);
        return player != null && player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
    }

    private void notifyTarget(String name, String messageKey, Map<String, String> placeholders) {
        Player target = Bukkit.getPlayerExact(name);
        if (target != null) message(target, messageKey, placeholders);
    }

    private SilentArguments extractSilent(String[] arguments) {
        if (arguments.length == 0 || !arguments[arguments.length - 1].equalsIgnoreCase("-s")) return new SilentArguments(arguments, false);
        return new SilentArguments(Arrays.copyOf(arguments, arguments.length - 1), true);
    }

    private void announce(Punishment punishment, boolean silent, CommandSender sender) {
        String target = punishment.type() == PunishmentType.IP_BAN
                && punishment.ip() != null ? punishment.ip() : punishment.targetName();
        Map<String, String> values = Map.of(
                "player", target, "type", punishment.type().displayName(), "reason", punishment.reason(),
                "duration", TimeFormat.remaining(punishment.expiresAt(), System.currentTimeMillis()), "actor", punishment.actor());
        String typeKey = switch (punishment.type()) {
            case BAN -> "ban";
            case MUTE -> "mute";
            case WARN -> "warn";
            case IP_BAN -> "ipban";
        };
        if (silent) {
            String path = "punishment-silent-" + typeKey;
            boolean senderNotified = false;
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (!staff.hasPermission("pnbans.notify")) continue;
                message(staff, path, values);
                if (staff.equals(sender)) senderNotified = true;
            }
            if (sender instanceof Player && !senderNotified) message(sender, path, values);
            Bukkit.getConsoleSender().sendMessage(MessageUtil.color(MessageUtil.lines(plugin, path, List.of(), values)));
            return;
        }
        String path = "punishment-broadcast-" + typeKey;
        for (Player player : Bukkit.getOnlinePlayers()) message(player, path, values);
        Bukkit.getConsoleSender().sendMessage(MessageUtil.color(MessageUtil.lines(plugin, path, List.of(), values)));
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        message(sender, "no-permission", Map.of());
        return false;
    }

    private boolean invalidDuration(CommandSender sender) {
        message(sender, "invalid-duration-v2", Map.of());
        return true;
    }

    private boolean usage(CommandSender sender, String usage) {
        String command = usage.startsWith("/") ? usage.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT) : "fallback";
        String path = plugin.getMessages().contains("usage." + command) ? "usage." + command : "usage.fallback";
        message(sender, path, Map.of("usage", usage));
        return true;
    }

    private void message(CommandSender sender, String path, Map<String, String> placeholders) {
        MessageUtil.send(plugin, sender, path, placeholders);
    }

    private void databaseError(CommandSender sender, Exception exception) {
        plugin.getLogger().warning("Database operation failed: " + exception.getMessage());
        message(sender, "database-error", Map.of());
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pnbans")) return args.length == 1 ? matching(args[0], List.of("reload")) : List.of();
        if (name.equals("history") && args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("staff");
            suggestions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList());
            return matching(args[0], suggestions.stream().distinct().toList());
        }
        if (name.equals("history") && args.length == 2 && args[0].equalsIgnoreCase("staff")) {
            return matching(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList());
        }
        if (args.length == 1) return matching(args[0], Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList());
        if (args.length == 2 && List.of("ban", "mute", "warn", "ipban").contains(name)) {
            return matching(args[1], DURATION_SUGGESTIONS);
        }
        if (args.length == 3 && List.of("ban", "mute", "warn", "ipban").contains(name)) {
            return matching(args[2], reasonCodes(commandType(name)));
        }
        return List.of();
    }

    private PunishmentType commandType(String command) {
        return switch (command) {
            case "ban" -> PunishmentType.BAN;
            case "mute" -> PunishmentType.MUTE;
            case "warn" -> PunishmentType.WARN;
            case "ipban" -> PunishmentType.IP_BAN;
            default -> PunishmentType.BAN;
        };
    }

    private List<String> punishmentPresetCodes(PunishmentType type) {
        List<String> result = new ArrayList<>();
        String typeKey = type.name().toLowerCase(Locale.ROOT);
        ConfigurationSection presets = plugin.getConfig().getConfigurationSection("punishment-presets." + typeKey);
        if (presets != null) {
            for (String key : presets.getKeys(true)) {
                ConfigurationSection preset = presets.getConfigurationSection(key);
                if (preset != null && (preset.isString("duration") || preset.isString("reason"))) result.add(key);
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private List<String> reasonCodes(PunishmentType type) {
        List<String> result = new ArrayList<>(punishmentPresetCodes(type));
        String typeKey = type.name().toLowerCase(Locale.ROOT);
        ConfigurationSection typedTemplates = plugin.getConfig().getConfigurationSection("reason-templates." + typeKey);
        if (typedTemplates != null) {
            for (String key : typedTemplates.getKeys(true)) {
                if (typedTemplates.isString(key)) result.add(key);
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private List<String> matching(String input, List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) result.add(value);
        return result;
    }

    private static String knownOrUnknown(String value) {
        return value == null || value.isBlank() ? "неизвестно" : value;
    }

    private record MenuUpdateInfo(String status, String latest, String download) {
    }

    private record Target(UUID uuid, String name) {
    }

    private record Input(Target target, DurationParser.DurationValue duration, Reason reason) {
    }

    private record SilentArguments(String[] arguments, boolean silent) {
    }

    private record Reason(String code, String full) {
    }

    private record ReasonPreset(String code, String reason, DurationParser.DurationValue duration) {
        private Reason reason(String[] extra) {
            String suffix = extra.length == 0 ? "" : " " + String.join(" ", extra);
            return new Reason(code, reason + suffix);
        }
    }
}
