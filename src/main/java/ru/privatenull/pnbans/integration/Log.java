package ru.privatenull.pnbans.integration;

import org.bukkit.plugin.java.JavaPlugin;
import org.pnfolder.pnlibrary.api.plugin.ModuleContext;

import java.util.logging.Level;

/**
 * Единая точка логирования плагина: пишет через логгер модуля pnLibrary, а пока модуль не зарегистрирован
 * или уже закрыт, пишет в обычный логгер плагина. Никогда не бросает исключений.
 */
public final class Log {

    private static volatile JavaPlugin plugin;
    private static volatile ModuleContext module;

    private Log() {
    }

    static void bind(JavaPlugin owner, ModuleContext context) {
        plugin = owner;
        module = context;
    }

    static void unbind() {
        module = null;
    }

    public static void info(String message) {
        ModuleContext context = active();
        if (context != null) {
            try {
                context.getLogger().info(message);
                return;
            } catch (RuntimeException | LinkageError ignored) {
                // запасной путь ниже
            }
        }
        fallback(Level.INFO, message, null);
    }

    public static void success(String message) {
        ModuleContext context = active();
        if (context != null) {
            try {
                context.getLogger().success(message);
                return;
            } catch (RuntimeException | LinkageError ignored) {
                // запасной путь ниже
            }
        }
        fallback(Level.INFO, message, null);
    }

    public static void warning(String message) {
        ModuleContext context = active();
        if (context != null) {
            try {
                context.getLogger().warning(message);
                return;
            } catch (RuntimeException | LinkageError ignored) {
                // запасной путь ниже
            }
        }
        fallback(Level.WARNING, message, null);
    }

    public static void error(String message) {
        error(message, null);
    }

    public static void error(String message, Throwable error) {
        ModuleContext context = active();
        if (context != null) {
            try {
                context.getLogger().error(message, error);
                return;
            } catch (RuntimeException | LinkageError ignored) {
                // запасной путь ниже
            }
        }
        fallback(Level.SEVERE, message, error);
    }

    private static ModuleContext active() {
        ModuleContext context = module;
        try {
            return context != null && !context.isClosed() ? context : null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static void fallback(Level level, String message, Throwable error) {
        JavaPlugin owner = plugin;
        try {
            if (owner != null) owner.getLogger().log(level, message, error);
            else java.util.logging.Logger.getLogger("pn").log(level, message, error);
        } catch (RuntimeException ignored) {
            // логирование не должно ронять плагин
        }
    }
}
