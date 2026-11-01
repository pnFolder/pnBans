package ru.privatenull.pnbans.listener;

import ru.privatenull.pnbans.integration.Tasks;
import ru.privatenull.pnbans.integration.Log;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.privatenull.pnbans.PnBansPlugin;
import ru.privatenull.pnbans.dupeip.DupeIpService;
import ru.privatenull.pnbans.util.MessageUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DupeIpListener implements Listener {

    private final PnBansPlugin plugin;
    private final DupeIpService service;
    private final Map<AlertPair, AlertState> alertStates = new HashMap<>();

    public DupeIpListener(PnBansPlugin plugin, DupeIpService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("dupeip.enabled", true)) return;
        scan(event.getPlayer());
    }

    public void scan(Player player) {
        if (!plugin.getConfig().getBoolean("dupeip.enabled", true)) return;
        String bypassPermission = plugin.getConfig().getString(
                "dupeip.bypass-permission", "pnbans.dupeip.bypass");
        if (bypassPermission != null && !bypassPermission.isBlank()
                && player.hasPermission(bypassPermission)) return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        Location location = player.getLocation();
        String world = location.getWorld() == null ? null : location.getWorld().getName();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        Tasks.async(() -> {
            try {
                DupeIpService.Analysis analysis = service.recordAndAnalyze(uuid, name, ip, world, x, y, z);
                if (!analysis.shouldAlert()) return;
                Tasks.sync(() -> notifyStaff(analysis));
            } catch (Exception exception) {
                Log.error("DupeIP check failed for " + name + "", exception);
            }
        });
    }

    private void notifyStaff(DupeIpService.Analysis analysis) {
        DupeIpService.Match strongest = analysis.matches().stream()
                .max(java.util.Comparator.comparingInt(DupeIpService.Match::riskScore))
                .orElse(null);
        if (strongest == null) return;

        String permission = plugin.getConfig().getString("dupeip.alert-permission", "pnbans.dupeip.alert");
        List<? extends Player> recipients = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(permission))
                .toList();
        boolean alertConsole = plugin.getConfig().getBoolean("dupeip.alert-console", true);
        if (recipients.isEmpty() && !alertConsole) return;
        if (!shouldSendAlert(analysis, strongest)) return;

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", analysis.name());
        placeholders.put("ip", analysis.ip());
        placeholders.put("country", analysis.country());
        placeholders.put("known_ips", Integer.toString(analysis.knownIpCount()));
        placeholders.put("same_ip", Integer.toString(analysis.sameIpCount()));
        placeholders.put("similar", Integer.toString(analysis.similarNameCount()));
        placeholders.put("active_bans", Integer.toString(analysis.activeBanCount()));
        placeholders.put("active_mutes", Integer.toString(analysis.activeMuteCount()));
        placeholders.put("risk", Integer.toString(analysis.highestRiskScore()));
        placeholders.put("risk_level", analysis.highestRiskLevel());
        placeholders.put("risk_color", riskColor(analysis.highestRiskScore()));
        placeholders.put("related_player", strongest.profile().name());
        placeholders.put("related_ip", strongest.profile().ip());
        placeholders.put("related_country", strongest.country());
        placeholders.put("shared_ips", strongest.sharedIps().isEmpty()
                ? "нет" : String.join(", ", strongest.sharedIps()));
        placeholders.put("evidence", evidence(strongest.signals()));
        placeholders.put("command", "/dupeip " + analysis.name());

        for (Player staff : recipients) {
            MessageUtil.sendDupeIpAlert(plugin, staff, placeholders);
        }
        if (alertConsole) {
            MessageUtil.sendDupeIpAlert(plugin, Bukkit.getConsoleSender(), placeholders);
        }
    }

    private boolean shouldSendAlert(DupeIpService.Analysis analysis, DupeIpService.Match strongest) {
        long cooldownSeconds = Math.max(0L,
                plugin.getConfig().getLong("dupeip.alert-cooldown-seconds", 300L));
        if (cooldownSeconds == 0L) {
            alertStates.clear();
            return true;
        }

        long cooldownMillis = Math.min(cooldownSeconds, Long.MAX_VALUE / 1_000L) * 1_000L;
        long now = System.currentTimeMillis();
        alertStates.entrySet().removeIf(entry -> now - entry.getValue().sentAt() >= cooldownMillis);

        AlertPair pair = AlertPair.of(analysis.uuid(), strongest.profile().uuid());
        int fingerprint = java.util.Objects.hash(
                analysis.matches().stream().map(match -> match.profile().uuid()).sorted().toList(),
                strongest.sharedIps(), strongest.signals(), strongest.summary());
        AlertState previous = alertStates.get(pair);
        if (previous != null && strongest.riskScore() <= previous.riskScore()
                && fingerprint == previous.fingerprint()
                && now - previous.sentAt() < cooldownMillis) {
            return false;
        }

        alertStates.put(pair, new AlertState(now, strongest.riskScore(), fingerprint));
        return true;
    }

    private String evidence(List<String> signals) {
        if (signals.isEmpty()) return "Недостаточно данных";
        return signals.stream().limit(2).collect(java.util.stream.Collectors.joining(" &8• &7"));
    }

    private String riskColor(int score) {
        if (score >= 75) return "&c";
        if (score >= 50) return "&6";
        if (score >= 25) return "&e";
        return "&a";
    }

    private record AlertState(long sentAt, int riskScore, int fingerprint) {
    }

    private record AlertPair(UUID first, UUID second) {
        private static AlertPair of(UUID first, UUID second) {
            return first.compareTo(second) <= 0
                    ? new AlertPair(first, second)
                    : new AlertPair(second, first);
        }
    }
}
