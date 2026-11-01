package ru.privatenull.pnbans.gui;

import ru.privatenull.pnbans.integration.Tasks;
import ru.privatenull.pnbans.integration.Log;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.pnfolder.pnlibrary.api.tasks.TaskHandle;
import ru.privatenull.pnbans.PnBansPlugin;
import ru.privatenull.pnbans.dupeip.DupeIpService;
import ru.privatenull.pnbans.model.AccountProfile;
import ru.privatenull.pnbans.storage.PunishmentService;
import ru.privatenull.pnbans.util.MessageUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DupeIpGui implements Listener {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final int[] MATCH_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};
    private static final int PREVIOUS_SLOT = 48;
    private static final int NEXT_SLOT = 50;
    private static final long AUTO_REFRESH_TICKS = 40L;

    private final PnBansPlugin plugin;
    private final DupeIpService service;
    private final HistoryGui historyGui;

    public DupeIpGui(PnBansPlugin plugin, DupeIpService service, HistoryGui historyGui) {
        this.plugin = plugin;
        this.service = service;
        this.historyGui = historyGui;
    }

    public void open(Player viewer, UUID uuid, String name) {
        load(viewer, uuid, name, 0);
    }

    private void load(Player viewer, UUID uuid, String name, int requestedPage) {
        Tasks.async(() -> {
            try {
                DupeIpService.Analysis analysis = service.analyze(uuid, name);
                Tasks.sync(() -> openLoaded(viewer, analysis, requestedPage));
            } catch (Exception exception) {
                Log.error("Could not open DupeIP GUI", exception);
                Tasks.sync(() -> MessageUtil.send(plugin, viewer, "dupeip-load-error", java.util.Map.of()));
            }
        });
    }

    private void openLoaded(Player viewer, DupeIpService.Analysis analysis, int requestedPage) {
        DupeHolder holder = new DupeHolder(analysis);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MessageUtil.color("&7Проверка связей &8| &f" + analysis.name()));
        holder.setInventory(inventory);
        decorate(inventory);
        render(holder, analysis, requestedPage, true);
        viewer.openInventory(inventory);
        startAutoRefresh(viewer, holder);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DupeHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) return;
        if (event.getRawSlot() == 4) {
            openHistory(player, holder.analysis().uuid(), holder.analysis().name());
            return;
        }
        if (event.getRawSlot() == PREVIOUS_SLOT && holder.page() > 0) {
            render(holder, holder.analysis(), holder.page() - 1, false);
            return;
        }
        if (event.getRawSlot() == NEXT_SLOT && holder.hasNextPage()) {
            render(holder, holder.analysis(), holder.page() + 1, false);
            return;
        }
        int pageIndex = slotIndex(event.getRawSlot());
        int index = holder.page() * MATCH_SLOTS.length + pageIndex;
        if (pageIndex < 0 || index >= holder.analysis().matches().size()) return;
        AccountProfile profile = holder.analysis().matches().get(index).profile();
        openHistory(player, profile.uuid(), profile.name());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof DupeHolder)) return;
        for (int slot : event.getRawSlots()) {
            if (slot < event.getInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof DupeHolder holder) holder.stopAutoRefresh();
    }

    private void render(DupeHolder holder, DupeIpService.Analysis analysis,
                        int requestedPage, boolean initial) {
        List<DupeIpService.Match> matches = analysis.matches();
        int lastPage = Math.max(0, (matches.size() - 1) / MATCH_SLOTS.length);
        int page = Math.max(0, Math.min(requestedPage, lastPage));
        int start = page * MATCH_SLOTS.length;
        int end = Math.min(start + MATCH_SLOTS.length, matches.size());
        boolean hasNextPage = end < matches.size();
        int previousPage = holder.page();
        boolean previouslyHadNextPage = holder.hasNextPage();
        holder.update(analysis, page, hasNextPage);

        Inventory inventory = holder.getInventory();
        setIfChanged(inventory, 4, summaryItem(analysis, page, lastPage));
        for (int index = 0; index < MATCH_SLOTS.length; index++) {
            int matchIndex = start + index;
            ItemStack desired = matchIndex < end ? matchItem(matches.get(matchIndex)) : null;
            if (MATCH_SLOTS[index] == 22 && matches.isEmpty()) {
                desired = analysis.status() == DupeIpService.Status.READY
                        ? noMatchesItem() : statusItem(analysis.status());
            }
            setIfChanged(inventory, MATCH_SLOTS[index], desired);
        }

        if (initial || previousPage != page) {
            setIfChanged(inventory, PREVIOUS_SLOT, GuiNavigation.previous(page));
        }
        if (initial || previousPage != page || previouslyHadNextPage != hasNextPage) {
            setIfChanged(inventory, NEXT_SLOT, GuiNavigation.next(page, hasNextPage));
        }
    }

    private void startAutoRefresh(Player viewer, DupeHolder holder) {
        TaskHandle task = Tasks.syncTimer(AUTO_REFRESH_TICKS, AUTO_REFRESH_TICKS, () -> {
            if (!isOpen(viewer, holder)) {
                holder.stopAutoRefresh();
                return;
            }
            if (!holder.beginRefresh()) return;
            DupeIpService.Analysis current = holder.analysis();
            Tasks.async(() -> {
                try {
                    DupeIpService.Analysis refreshed = service.analyze(current.uuid(), current.name());
                    Tasks.sync(() -> {
                        holder.finishRefresh();
                        if (isOpen(viewer, holder)) render(holder, refreshed, holder.page(), false);
                    });
                } catch (Exception exception) {
                    plugin.getLogger().fine("Could not refresh DupeIP GUI: " + exception.getMessage());
                    Tasks.sync(holder::finishRefresh);
                }
            });
        });
        holder.setAutoRefreshTask(task);
    }

    private boolean isOpen(Player viewer, DupeHolder holder) {
        return viewer.isOnline()
                && viewer.getOpenInventory().getTopInventory().getHolder() == holder;
    }

    private void setIfChanged(Inventory inventory, int slot, ItemStack desired) {
        ItemStack current = inventory.getItem(slot);
        if (current == null && desired == null) return;
        if (current != null && current.equals(desired)) return;
        inventory.setItem(slot, desired);
    }

    private int slotIndex(int slot) {
        for (int index = 0; index < MATCH_SLOTS.length; index++) {
            if (MATCH_SLOTS[index] == slot) return index;
        }
        return -1;
    }

    private void openHistory(Player player, UUID uuid, String name) {
        player.closeInventory();
        Tasks.sync(() -> historyGui.open(player, uuid, name, 0));
    }

    private ItemStack summaryItem(DupeIpService.Analysis analysis, int page, int lastPage) {
        return button(Material.COMPASS, "&#A0EFA1Проверка DupeIP &8| &f" + analysis.name(),
                "",
                "&#A0EFA1 «Основное»",
                " &7- &fIP: &#D8DF9D" + analysis.ip(),
                " &7- &fСтрана: &#D8DF9D" + analysis.country(),
                " &7- &fИзвестно IP: &#D8DF9D" + analysis.knownIpCount(),
                " &7- &fСтраница: &#D8DF9D" + (page + 1) + "&8/&f" + (lastPage + 1),
                " &7- &fВсего связей: &#D8DF9D" + analysis.matches().size(),
                "",
                "&#C096AB «Найденные связи»",
                " &7- &fСовпадений по IP: &#D8DF9D" + analysis.sameIpCount(),
                " &7- &fПохожих ников: &#D8DF9D" + analysis.similarNameCount(),
                " &7- &fАктивных банов: &c" + analysis.activeBanCount(),
                " &7- &fАктивных мутов: &6" + analysis.activeMuteCount(),
                " &7- &fМаксимальный риск: " + riskColor(analysis.highestRiskScore())
                        + analysis.highestRiskScore() + "/100 &8(" + analysis.highestRiskLevel() + "&8)",
                "",
                "&#FFC67A «Действие»",
                "&7ЛКМ &8— &fоткрыть историю аккаунта",
                "");
    }

    private ItemStack noMatchesItem() {
        return button(Material.LIME_DYE, "&aСвязанных аккаунтов нет",
                "", "&#A0EFA1 «Результат проверки»",
                " &7- &fСовпадений по IP и нику нет",
                " &7- &fПодозрительных связей не найдено", "");
    }

    private ItemStack matchItem(DupeIpService.Match match) {
        AccountProfile profile = match.profile();
        PunishmentService.PunishmentSummary summary = match.summary();
        Material material = summary.activeBans() > 0 ? Material.REDSTONE_BLOCK : summary.activeMutes() > 0 ? Material.ORANGE_CONCRETE : Material.PLAYER_HEAD;
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&#A0EFA1 «Связь аккаунта»");
        lore.add(" &7- &fОдин IP: " + (match.sameIp() ? "&aда" : "&8нет"));
        lore.add(" &7- &fОбщие IP: &#D8DF9D" + (match.sharedIps().isEmpty()
                ? "нет" : String.join(", ", match.sharedIps())));
        lore.add(" &7- &fВсего известных IP: &#D8DF9D" + match.knownIpCount());
        lore.add(" &7- &fСхожесть ника: &e" + (int) Math.round(match.nameSimilarity() * 100D) + "%");
        lore.add(" &7- &fПоследний IP: &#D8DF9D" + profile.ip());
        lore.add(" &7- &fСтрана: &#D8DF9D" + match.country());
        lore.add(" &7- &fБыл в сети: &#D8DF9D" + DATE_FORMAT.format(Instant.ofEpochMilli(profile.lastSeen())));
        lore.add("");
        lore.add("&#FFC67A «Оценка риска»");
        lore.add(" &7- &fУровень: " + riskColor(match.riskScore()) + match.riskLevel());
        lore.add(" &7- &fБалл: " + riskColor(match.riskScore()) + match.riskScore() + "/100");
        lore.add("");
        lore.add("&#C096AB «Почему найдено»");
        if (match.signals().isEmpty()) lore.add(" &7- &fНедостаточно данных");
        else for (String signal : match.signals()) lore.add(" &7- &f" + signal);
        lore.add("");
        lore.add("&#C096AB «Наказания»");
        lore.add(" &7- &fБаны: &c" + summary.bans() + " &8(активно: &f" + summary.activeBans() + "&8)");
        lore.add(" &7- &fМуты: &6" + summary.mutes() + " &8(активно: &f" + summary.activeMutes() + "&8)");
        lore.add(" &7- &fПредупреждения: &e" + summary.warns());
        lore.add("");
        lore.add("&#FFC67A «Действие»");
        lore.add("&7ЛКМ &8— &fоткрыть историю наказаний");
        lore.add("");
        return button(material, "&#D8DF9D" + profile.name(), lore.toArray(String[]::new));
    }

    private String riskColor(int score) {
        if (score >= 75) return "&c";
        if (score >= 50) return "&6";
        if (score >= 25) return "&e";
        return "&a";
    }

    private ItemStack statusItem(DupeIpService.Status status) {
        if (status == DupeIpService.Status.NO_PROFILE) {
            return button(Material.YELLOW_DYE, "&eДанных об игроке ещё нет",
                    "", "&#FFC67A «DupeIP»",
                    " &7- &fИгрок ещё не входил на сервер",
                    " &7- &fПроверка появится после первого входа", "");
        }
        return button(Material.ORANGE_DYE, "&eIP не определён",
                "", "&#FFC67A «DupeIP»",
                " &7- &fУ игрока нет доступного IP-адреса", "");
    }

    private void decorate(Inventory inventory) {
        ItemStack orange = pane(Material.ORANGE_STAINED_GLASS_PANE);
        ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot <= 8; slot++) inventory.setItem(slot, orange);
        for (int slot : new int[]{9, 17, 18, 26, 27, 35, 36, 44}) inventory.setItem(slot, orange);
        for (int slot = 45; slot <= 53; slot++) inventory.setItem(slot, black);
    }

    private ItemStack pane(Material material) {
        return button(material, " ");
    }

    private ItemStack button(Material material, String name, String... lore) {
        return GuiItemFactory.item(material, name, lore);
    }

    private static final class DupeHolder implements InventoryHolder {
        private DupeIpService.Analysis analysis;
        private int page;
        private boolean hasNextPage;
        private boolean refreshing;
        private TaskHandle autoRefreshTask;
        private Inventory inventory;

        private DupeHolder(DupeIpService.Analysis analysis) {
            this.analysis = analysis;
        }

        private DupeIpService.Analysis analysis() {
            return analysis;
        }

        private int page() {
            return page;
        }

        private boolean hasNextPage() {
            return hasNextPage;
        }

        private void update(DupeIpService.Analysis analysis, int page, boolean hasNextPage) {
            this.analysis = analysis;
            this.page = page;
            this.hasNextPage = hasNextPage;
        }

        private boolean beginRefresh() {
            if (refreshing) return false;
            refreshing = true;
            return true;
        }

        private void finishRefresh() {
            refreshing = false;
        }

        private void setAutoRefreshTask(TaskHandle task) {
            if (autoRefreshTask != null) autoRefreshTask.cancel();
            autoRefreshTask = task;
        }

        private void stopAutoRefresh() {
            if (autoRefreshTask != null) autoRefreshTask.cancel();
            autoRefreshTask = null;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

}
