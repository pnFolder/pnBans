package ru.privatenull.pnbans;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import ru.privatenull.pnlibrary.database.DatabaseSettings;
import ru.privatenull.pnlibrary.database.JdbcDatabase;
import ru.privatenull.pnlibrary.database.JdbcSettings;
import ru.privatenull.pnlibrary.database.MongoDatabaseManager;
import ru.privatenull.pnlibrary.database.MongoSettings;
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
import ru.privatenull.pnlibrary.lifecycle.PluginBanner;
import ru.privatenull.pnlibrary.update.UpdateChecker;
import ru.privatenull.pnlibrary.update.UpdateSettings;

public final class PnBansPlugin extends JavaPlugin {
    private static final String SUPPORT_URL = "https://discord.gg/rRbzq6cnc6";
    private static final String GITHUB_REPOSITORY = "pnFolder/pnBans";
    private static final int BSTATS_PLUGIN_ID = 32598;

    private DatabaseBackend database;
    private PunishmentService punishmentService;
    private PunishmentListener punishmentListener;
    private PunishmentEffects punishmentEffects;
    private ModerationLimitService moderationLimits;
    private HistoryGui historyGui;
    private StaffGui staffGui;
    private UpdateChecker updateChecker;
    private DupeIpService dupeIpService;
    private DupeIpGui dupeIpGui;
    private FileConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        try {
            database = createDatabase();
            database.open();
            punishmentService = new PunishmentService(database);
        } catch (Exception exception) {
            getLogger().severe("pnBans could not connect to the database: " + exception.getMessage());
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
        updateChecker = new UpdateChecker(this, updateSettings());
        updateChecker.start();
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("database_type", () ->
                ru.privatenull.pnlibrary.database.DatabaseType.parse(
                        getConfig().getString("database.type", "SQLITE")
                ).name()));
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) { updateChecker.notifyAdminOnJoin(event.getPlayer()); }
        }, this);
        PluginBanner.enabled(this, SUPPORT_URL);
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) updateChecker.cancel();
        if (punishmentEffects != null) punishmentEffects.shutdown();
        getServer().getServicesManager().unregisterAll(this);
        if (database != null) database.close();
        PluginBanner.disabled(this, SUPPORT_URL);
    }

    public void reloadPlugin() {
        reloadConfig();
        loadMessages();
        if (punishmentListener != null) punishmentListener.reload();
        if (updateChecker != null) updateChecker.restart(updateSettings());
    }

    public PunishmentService getPunishmentService() {
        return punishmentService;
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
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
            getLogger().warning("Could not load messages.yml defaults: " + exception.getMessage());
        }
    }

    private UpdateSettings updateSettings() {
        return new UpdateSettings(
                true,
                GITHUB_REPOSITORY,
                "pnbans.admin",
                6L,
                SUPPORT_URL
        );
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
