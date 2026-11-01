package ru.privatenull.pnbans.integration;

import org.pnfolder.pnlibrary.api.plugin.ModuleContext;

import java.time.Duration;
import java.util.UUID;

/** Кулдауны плагина поверх {@code ModuleContext.cooldowns} pnLibrary. */
public final class Cooldowns {

    private static volatile ModuleContext module;

    private Cooldowns() {
    }

    static void bind(ModuleContext context) {
        module = context;
    }

    static void unbind() {
        module = null;
    }

    /** Занимает кулдаун длиной {@code millis}; {@code true}, если прежнего активного кулдауна не было. */
    public static boolean acquire(UUID subject, String action, long millis) {
        ModuleContext context = module;
        if (context == null || context.isClosed()) return true;
        return context.getCooldowns().acquire(subject, action, Duration.ofMillis(Math.max(0L, millis))).getAllowed();
    }

    /** Остаток кулдауна в миллисекундах, 0 если он не активен. */
    public static long remainingMillis(UUID subject, String action) {
        ModuleContext context = module;
        if (context == null || context.isClosed()) return 0L;
        return context.getCooldowns().remaining(subject, action).toMillis();
    }

    /** Запускает кулдаун длиной {@code millis} заново; 0 снимает его. */
    public static void set(UUID subject, String action, long millis) {
        ModuleContext context = module;
        if (context != null && !context.isClosed()) context.getCooldowns().set(subject, action, Duration.ofMillis(Math.max(0L, millis)));
    }

    /** Сбрасывает кулдаун. */
    public static void reset(UUID subject, String action) {
        ModuleContext context = module;
        if (context != null && !context.isClosed()) context.getCooldowns().reset(subject, action);
    }
}
