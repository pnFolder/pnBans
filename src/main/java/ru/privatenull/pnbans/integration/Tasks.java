package ru.privatenull.pnbans.integration;

import org.pnfolder.pnlibrary.api.plugin.ModuleContext;
import org.pnfolder.pnlibrary.api.tasks.TaskExecution;
import org.pnfolder.pnlibrary.api.tasks.TaskHandle;
import org.pnfolder.pnlibrary.api.tasks.TaskSpec;

/**
 * Планировщик плагина поверх задач pnLibrary ({@code ModuleContext.tasks}): задачи выполняются на нужном потоке
 * платформы и отменяются вместе с модулем. Пока модуль не зарегистрирован или уже закрыт, задачи не запускаются.
 */
public final class Tasks {

    private static volatile ModuleContext module;

    private Tasks() {
    }

    static void bind(ModuleContext context) {
        module = context;
    }

    static void unbind() {
        module = null;
    }

    /** Выполняет действие в основном (глобальном) потоке как можно скорее. */
    public static TaskHandle sync(Runnable action) {
        return schedule(TaskExecution.global(), 0L, 0L, action);
    }

    /** Выполняет действие в основном потоке один раз через {@code ticks} тиков. */
    public static TaskHandle syncLater(long ticks, Runnable action) {
        return schedule(TaskExecution.global(), ticks, 0L, action);
    }

    /** Повторяет действие в основном потоке: первый запуск через {@code delay} тиков, затем каждые {@code period} тиков. */
    public static TaskHandle syncTimer(long delay, long period, Runnable action) {
        return schedule(TaskExecution.global(), delay, period, action);
    }

    /** Выполняет действие в асинхронном потоке библиотеки как можно скорее. */
    public static TaskHandle async(Runnable action) {
        return schedule(TaskExecution.async(), 0L, 0L, action);
    }

    /** Выполняет действие в асинхронном потоке один раз через {@code ticks} тиков. */
    public static TaskHandle asyncLater(long ticks, Runnable action) {
        return schedule(TaskExecution.async(), ticks, 0L, action);
    }

    /** Повторяет действие в асинхронном потоке: первый запуск через {@code delay} тиков, затем каждые {@code period} тиков. */
    public static TaskHandle asyncTimer(long delay, long period, Runnable action) {
        return schedule(TaskExecution.async(), delay, period, action);
    }

    /** Выполняет действие в потоке сущности (игрока) как можно скорее. */
    public static TaskHandle entity(Object target, Runnable action) {
        return schedule(TaskExecution.entity(target), 0L, 0L, action);
    }

    /** Выполняет действие в потоке сущности один раз через {@code ticks} тиков. */
    public static TaskHandle entityLater(Object target, long ticks, Runnable action) {
        return schedule(TaskExecution.entity(target), ticks, 0L, action);
    }

    /** Повторяет действие в потоке сущности: первый запуск через {@code delay} тиков, затем каждые {@code period} тиков. */
    public static TaskHandle entityTimer(Object target, long delay, long period, Runnable action) {
        return schedule(TaskExecution.entity(target), delay, period, action);
    }

    private static TaskHandle schedule(TaskExecution execution, long delay, long period, Runnable action) {
        ModuleContext context = module;
        if (context == null || context.isClosed()) return null;
        TaskSpec.Builder builder = TaskSpec.builder()
                .name("pnbans")
                .hidden(period <= 0L)
                .execution(execution)
                .delayTicks(delay)
                .action(run -> action.run());
        if (period > 0L) builder.intervalTicks(period);
        return context.getTasks().schedule(builder.build());
    }
}
