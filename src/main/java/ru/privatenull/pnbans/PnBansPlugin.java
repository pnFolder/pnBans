package ru.privatenull.pnbans;

import ru.privatenull.pnbans.integration.Log;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.pnfolder.pnlibrary.bukkit.bootstrap.PnLibraryBootstrap;
import ru.privatenull.pnbans.bootstrap.PnLibraryLifecycleListener;
import ru.privatenull.pnbans.integration.PnLibraryRuntime;
import ru.privatenull.pnbans.support.database.DatabaseSettings;
import ru.privatenull.pnbans.support.database.DatabaseType;
import ru.privatenull.pnbans.support.database.JdbcDatabase;
import ru.privatenull.pnbans.support.database.JdbcSettings;
import ru.privatenull.pnbans.support.database.MongoDatabaseManager;
import ru.privatenull.pnbans.support.database.MongoSettings;
import ru.privatenull.pnbans.command.ModerationCommand;
import ru.privatenull.pnbans.api.PnBansMuteApi;
import ru.privatenull.pnbans.api.PnBansMuteApiImpl;
import ru.privatenull.pnbans.database.DatabaseBackend;
import ru.privatenull.pnbans.database.JdbcDatabaseBackend;
import ru.privatenull.pnbans.database.MongoDatabaseBackend;
import ru.privatenull.pnbans.dupeip.DupeIpService;
import ru.privatenull.pnbans.effect.PunishmentEffects;
import ru.privatenull.pnbans.gui.DupeIpGui;
import ru.privatenull.pnbans.gui.HistoryGui;
import ru.privatenull.pnbans.gui.StaffGui;
import ru.privatenull.pnbans.limit.ModerationLimitService;
import ru.privatenull.pnbans.listener.DupeIpListener;
import ru.privatenull.pnbans.listener.PunishmentListener;
import ru.privatenull.pnbans.storage.PunishmentService;

public final class PnBansPlugin extends JavaPlugin {
    private static final String SUPPORT_URL = "https://discord.gg/rRbzq6cnc6";

    private DatabaseBackend database;
    private PunishmentService punishmentService;
    private PunishmentListener punishmentListener;
    private PunishmentEffects punishmentEffects;
    private ModerationLimitService moderationLimits;
    private HistoryGui historyGui;
    private StaffGui staffGui;
    private boolean pnLibraryReady;
    private PnLibraryRuntime pnRuntime;
    private DupeIpService dupeIpService;
    private DupeIpGui dupeIpGui;
    private FileConfiguration messages;

    @Override
    public void onLoad() {
        try {
            pnLibraryReady = PnLibraryBootstrap.ensureInstalled(this, PnLibraryRuntime.MINIMUM_VERSION);
        } catch (Throwable failure) {
            Log.error("Не удалось автоматически подготовить pnLibrary. pnBans не будет запущен.", failure);
        }
    }

    @Override
    public void onEnable() {
        if (!pnLibraryReady) {
            Log.error("pnLibrary не подготовлена: проверьте доступ к GitHub и ошибку загрузки выше.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            pnRuntime = PnLibraryRuntime.connect(this);
        } catch (Throwable failure) {
            Log.error("pnLibrary не удалось включить или подключить к pnBans.", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new PnLibraryLifecycleListener(this), this);
        saveDefaultConfig();
        loadMessages();
        try {
            database = createDatabase();
            database.open();
            punishmentService = new PunishmentService(database);
        } catch (Exception exception) {
            Log.error("pnBans could not connect to the database", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        historyGui = new HistoryGui(this, punishmentService);
        punishmentEffects = new PunishmentEffects(this);
        moderationLimits = new ModerationLimitService(this);
        staffGui = new StaffGui(historyGui);
        dupeIpService = new DupeIpService(this, punishmentService);
        dupeIpGui = new DupeIpGui(this, dupeIpService, historyGui);
        ModerationCommand command = new ModerationCommand(this, punishmentService, historyGui, staffGui, dupeIpService, dupeIpGui, punishmentEffects, moderationLimits);
        registerCommand("ban", command);
        registerCommand("unban", command);
        registerCommand("ipban", command);
        registerCommand("unipban", command);
        registerCommand("mute", command);
        registerCommand("unmute", command);
        registerCommand("warn", command);
        registerCommand("unwarn", command);
        registerCommand("history", command);
        registerCommand("dupeip", command);
        registerCommand("pnbans", command);

        punishmentListener = new PunishmentListener(this, punishmentService, punishmentEffects);
        getServer().getPluginManager().registerEvents(punishmentListener, this);
        getServer().getPluginManager().registerEvents(historyGui, this);
        getServer().getPluginManager().registerEvents(staffGui, this);
        getServer().getPluginManager().registerEvents(dupeIpGui, this);
        DupeIpListener dupeIpListener = new DupeIpListener(this, dupeIpService);
        getServer().getPluginManager().registerEvents(dupeIpListener, this);
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) dupeIpListener.scan(online);
        getServer().getServicesManager().register(PnBansMuteApi.class, new PnBansMuteApiImpl(punishmentService), this, org.bukkit.plugin.ServicePriority.Normal);
        String databaseType = databaseTypeName();
        pnRuntime.register(() -> databaseType, () -> java.util.Map.of(
                "database", databaseType,
                "version", getDescription().getVersion()));
        pnRuntime.started(databaseType, 11);
    }

    @Override
    public void onDisable() {
        if (punishmentEffects != null) punishmentEffects.shutdown();
        getServer().getServicesManager().unregisterAll(this);
        if (database != null) database.close();
        PnLibraryRuntime closing = pnRuntime;
        pnRuntime = null;
        if (closing != null) {
            try {
                closing.close();
            } catch (Exception exception) {
                Log.error("Не удалось корректно закрыть контекст pnLibrary", exception);
            }
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        loadMessages();
        if (punishmentListener != null) punishmentListener.reload();
    }

    public PunishmentService getPunishmentService() {
        return punishmentService;
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public PnLibraryRuntime getPnRuntime() {
        return pnRuntime;
    }

    private String databaseTypeName() {
        return DatabaseType.parse(getConfig().getString("database.type", "SQLITE")).name();
    }

    public String getSupportDiscord() {
        return SUPPORT_URL;
    }

    private void loadMessages() {
        java.io.File file = new java.io.File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        try (java.io.InputStream stream = getResource("messages.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
            }
        } catch (java.io.IOException exception) {
            Log.error("Could not load messages.yml defaults", exception);
        }
    }

    private DatabaseBackend createDatabase() {
        DatabaseSettings settings = DatabaseSettings.from(
                getConfig().getConfigurationSection("database"),
                getDataFolder()
        );
        if (settings instanceof JdbcSettings jdbc) {
            return new JdbcDatabaseBackend(new JdbcDatabase(jdbc));
        }
        if (settings instanceof MongoSettings mongo) {
            return new MongoDatabaseBackend(new MongoDatabaseManager(mongo));
        }
        throw new IllegalStateException("Unsupported database settings: " + settings.type());
    }

    private void registerCommand(String name, ModerationCommand executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Command /" + name + " is missing from plugin.yml");
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
