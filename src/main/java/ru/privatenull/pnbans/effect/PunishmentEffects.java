package ru.privatenull.pnbans.effect;

import ru.privatenull.pnbans.integration.Tasks;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.pnfolder.pnlibrary.api.tasks.TaskHandle;
import ru.privatenull.pnbans.PnBansPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Player-only feedback; punishment persistence and enforcement remain independent from visuals. */
public final class PunishmentEffects {
    private final PnBansPlugin plugin;
    private final Map<UUID, TaskHandle> banSequences = new ConcurrentHashMap<>();
    public PunishmentEffects(PnBansPlugin plugin) { this.plugin = plugin; }
    public void muteIssued(Player player) { play(player, "effects.mute-issued-sound", Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f); }
    public void muteAttempt(Player player) { play(player, "effects.mute-attempt-sound", Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.6f, 0.8f); }
    public void ban(Player player, String kickMessage) {
        if (!plugin.getConfig().getBoolean("effects.ban-sequence.enabled", true)) { player.kickPlayer(kickMessage); return; }
        TaskHandle previous = banSequences.remove(player.getUniqueId()); if (previous != null) previous.cancel();
        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 38, 0, false, false, false));
        play(player, "effects.ban-sequence-start-sound", Sound.ENTITY_WITHER_SPAWN, 0.45f, 1.35f);
        final int[] ticks = {0};
        TaskHandle task = Tasks.syncTimer(0L, 5L, () -> {
            if (!player.isOnline()) { cancel(player.getUniqueId()); return; }
            Location at = player.getLocation().add(0, 1.0, 0);
            player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at, 18, 0.45, 0.75, 0.45, 0.015);
            player.getWorld().spawnParticle(Particle.SMOKE, at, 12, 0.35, 0.65, 0.35, 0.01);
            ticks[0] += 5;
            if (ticks[0] >= 35) { cancel(player.getUniqueId()); play(player, "effects.ban-sequence-end-sound", Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.55f, 1.0f); player.kickPlayer(kickMessage); }
        });
        if (task != null) banSequences.put(player.getUniqueId(), task);
    }
    public void shutdown() { banSequences.values().forEach(TaskHandle::cancel); banSequences.clear(); }
    private void cancel(UUID id) { TaskHandle task = banSequences.remove(id); if (task != null) task.cancel(); }
    private void play(Player player, String path, Sound fallback, float volume, float pitch) {
        String configured = plugin.getConfig().getString(path, "");
        try {
            Sound sound = configured == null || configured.isBlank() ? fallback : Sound.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            player.playSound(player.getLocation(), fallback, volume, pitch);
        }
    }
}
