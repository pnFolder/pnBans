package ru.privatenull.pnbans.integration;

import org.bukkit.plugin.java.JavaPlugin;
import org.pnfolder.pnlibrary.api.diagnostics.DiagnosticConfiguration;
import org.pnfolder.pnlibrary.api.diagnostics.DiagnosticContainer;
import org.pnfolder.pnlibrary.api.plugin.ModuleContext;
import org.pnfolder.pnlibrary.api.plugin.PluginRegistration;
import org.pnfolder.pnlibrary.api.remote.PolicyErrorAction;
import org.pnfolder.pnlibrary.api.remote.RemotePolicySettings;
import org.pnfolder.pnlibrary.api.runtime.PnLibrary;
import org.pnfolder.pnlibrary.api.runtime.PnLibraryIdentity;
import org.pnfolder.pnlibrary.api.runtime.PnLibraryProvider;
import org.pnfolder.pnlibrary.api.updates.UpdateChannel;
import org.pnfolder.pnlibrary.api.updates.UpdateRegistration;
import org.pnfolder.pnlibrary.api.updates.UpdateSnapshot;

import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Managed pnLibrary integration loaded only after the dependency is enabled. */
public final class PnLibraryRuntime {

    public static final String MINIMUM_VERSION = "0.0.1-dev.6";
    private static final String MODULE_ID = "pnbans";
    private static final int BSTATS_PROJECT_ID = 32598;
    private static final String FASTSTATS_TOKEN = "981b88f95acc36635869f80c9582efd2";
    private static final String POLICY_SOURCE =
            "https://raw.githubusercontent.com/pnFolder/pnlibrary-policy/main/.pnlibrary/policies/PnBansPolicy.java";

    /** What the /pnbans menu shows about updates. */
    public record UpdateInfo(String status, String latest, String download) { }

    private final JavaPlugin owner;
    private final PnLibrary runtime;
    private PluginRegistration registration;
    private ModuleContext module;

    private PnLibraryRuntime(JavaPlugin owner, PnLibrary runtime) {
        this.owner = owner;
        this.runtime = runtime;
    }

    public static PnLibraryRuntime connect(JavaPlugin owner) {
        PnLibrary runtime = PnLibraryProvider.get();
        if (!runtime.isAtLeastVersion(MINIMUM_VERSION)) {
            throw new IllegalStateException("pnBans требует pnLibrary " + MINIMUM_VERSION
                    + " или новее, установлена " + runtime.getVersion());
        }
        return new PnLibraryRuntime(owner, runtime);
    }

    public void register(Supplier<String> databaseType, Supplier<Map<String, Object>> diagnostics) {
        if (registration != null) throw new IllegalStateException("pnBans уже зарегистрирован в pnLibrary");
        registration = runtime.getPlugins().register(owner);
        try {
            module = registration.registerModule(MODULE_ID, builder -> builder
                    // A policy that cannot be checked (offline server, JRE without a compiler) never stops pnBans;
                    // only an explicit denial from the policy does.
                    .remotePolicy(RemotePolicySettings.builder()
                            .source(POLICY_SOURCE)
                            .onError(PolicyErrorAction.KEEP_RUNNING)
                            .support(PnLibraryIdentity.SUPPORT_URL)
                            .build())
                    .metrics(metrics -> metrics
                            .bStats(BSTATS_PROJECT_ID).fastStats(FASTSTATS_TOKEN)
                            .charts(charts -> charts.simplePie("database_type", databaseType::get)))
                    .diagnostics(owner.getDataFolder().toPath(), DiagnosticContainer.builder("pnbans")
                            .snapshot(diagnostics::get)
                            .configuration(DiagnosticConfiguration.builder("config.yml")
                                    .secretKeyRegex("(?i).*(password|passwd|secret|token|uri|url|user(name)?)$")
                                    .build())
                            .build())
                    .updates("pnFolder", "pnBans", updater -> updater
                            .channel(UpdateChannel.STABLE)
                            .automaticDownload(false)
                            .artifact("(?i)^pnBans-[0-9].*\\.jar$", 21)));
        } catch (RuntimeException failure) {
            closeRegistration();
            throw failure;
        }
    }

    public void enabled(String databaseType) {
        if (module == null) return;
        module.getLifecycle().enabled()
                .ok("Хранилище", databaseType)
                .show();
    }

    public UpdateInfo updateInfo(String currentVersion) {
        UpdateRegistration updates = module == null ? null : module.getUpdates();
        if (updates == null) return new UpdateInfo("&7проверяется", "неизвестно", "");
        UpdateSnapshot snapshot = updates.getSnapshot();
        String latest = known(snapshot.getLatestVersion());
        String download = known(snapshot.getReleaseUrl());
        return switch (snapshot.getState()) {
            case CHECKING, DOWNLOADING -> new UpdateInfo("&7проверяется", latest, "");
            case UPDATE_AVAILABLE, UPDATE_STAGED -> new UpdateInfo(
                    "&eдоступно &f" + currentVersion + " &8→ &#D8DF9D" + latest, latest, download);
            case FAILED -> new UpdateInfo("&cошибка проверки", latest, "");
            case INCOMPATIBLE, BLOCKED -> new UpdateInfo("&eновая версия несовместима с сервером", latest, "");
            case FROZEN -> new UpdateInfo("&7обновления приостановлены", latest, "");
            case ROLLED_BACK -> new UpdateInfo("&7выполнен откат версии", latest, "");
            case UP_TO_DATE -> new UpdateInfo("&aпоследняя версия", latest, "");
        };
    }

    private static String known(String value) {
        return value == null || value.isBlank() ? "неизвестно" : value;
    }

    public void close() {
        ModuleContext closing = module;
        module = null;
        if (closing != null && !closing.isClosed()) {
            try {
                closing.getLifecycle().disabled().show();
            } catch (RuntimeException failure) {
                owner.getLogger().log(Level.FINE, "Не удалось вывести сообщение об остановке pnBans", failure);
            }
        }
        closeRegistration();
    }

    private void closeRegistration() {
        PluginRegistration closing = registration;
        registration = null;
        if (closing != null && !closing.isClosed()) closing.close();
    }
}
