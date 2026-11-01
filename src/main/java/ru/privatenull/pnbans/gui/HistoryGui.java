package ru.privatenull.pnbans.gui;

import ru.privatenull.pnbans.integration.Log;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ru.privatenull.pnbans.PnBansPlugin;
import ru.privatenull.pnbans.model.Punishment;
import ru.privatenull.pnbans.storage.PunishmentService;
import ru.privatenull.pnbans.util.MessageUtil;
import ru.privatenull.pnbans.util.TimeFormat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class HistoryGui implements Listener {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final int[] HISTORY_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final PnBansPlugin plugin;
    private final PunishmentService service;

    public HistoryGui(PnBansPlugin plugin, PunishmentService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void open(Player player, UUID uuid, String name, int page) {
        open(player, new Query(uuid, name, false), page);
    }

    public void openForActor(Player player, String actor, int page) {
        open(player, new Query(null, actor, true), page);
    }

    private void open(Player player, Query query, int page) {
        int pageSize = HISTORY_SLOTS.length;
        try {
            List<Punishment> loaded = query.byActor()
                    ? service.historyByActor(query.name(), page * pageSize, pageSize + 1)
                    : service.history(query.uuid(), query.name(), page * pageSize, pageSize + 1);
            boolean hasNextPage = loaded.size() > pageSize;
            List<Punishment> entries = hasNextPage ? loaded.subList(0, pageSize) : loaded;
            HistoryHolder holder = new HistoryHolder(query, page, hasNextPage);
            String title = query.byActor() ? "&7Наказания модератора &8| &f" : "&7История наказаний &8| &f";
            Inventory inventory = Bukkit.createInventory(holder, 54, MessageUtil.color(title + query.name()));
            holder.setInventory(inventory);
            decorate(inventory);
            inventory.setItem(4, summaryItem(query, entries.size(), page));
            long now = System.currentTimeMillis();
            for (int index = 0; index < entries.size(); index++) {
                inventory.setItem(HISTORY_SLOTS[index], punishmentItem(entries.get(index), now, query.byActor()));
            }
            if (entries.isEmpty()) inventory.setItem(31, emptyItem(query));
            inventory.setItem(48, GuiNavigation.previous(page));
            inventory.setItem(50, GuiNavigation.next(page, hasNextPage));
            player.openInventory(inventory);
        } catch (Exception exception) {
            Log.error("Could not open history GUI", exception);
            MessageUtil.send(plugin, player, "history-load-error", java.util.Map.of());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof HistoryHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) return;
        if (event.getRawSlot() == 48 && holder.page() > 0) open(player, holder.query(), holder.page() - 1);
        if (event.getRawSlot() == 50 && holder.hasNextPage()) open(player, holder.query(), holder.page() + 1);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof HistoryHolder)) return;
        for (int slot : event.getRawSlots()) {
            if (slot < event.getInventory().getSize()) { event.setCancelled(true); return; }
        }
    }

    private ItemStack summaryItem(Query query, int visibleEntries, int page) {
        String mode = query.byActor() ? "Выданные наказания" : "Наказания игрока";
        String subject = query.byActor() ? "Модератор" : "Игрок";
        return button(Material.COMPASS, "&#A0EFA1" + mode + " &8| &f" + query.name(), "",
                "&#A0EFA1 «Основное»",
                " &7- &f" + subject + ": &#D8DF9D" + query.name(),
                " &7- &fСтраница: &#D8DF9D" + (page + 1),
                " &7- &fЗаписей: &#D8DF9D" + visibleEntries,
                "");
    }

    private ItemStack punishmentItem(Punishment punishment, long now, boolean actorView) {
        Material material = switch (punishment.type()) {
            case BAN, IP_BAN -> Material.BARRIER;
            case MUTE -> Material.PAPER;
            case WARN -> Material.BOOK;
        };
        String status = punishment.isActive(now) ? "&aАктивно" : punishment.revokedBy() != null
                ? "&eСнято: &f" + punishment.revokedBy() : "&8Истекло";
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&#A0EFA1 «Наказание»");
        lore.add(" &7- &fТип: " + typeColor(punishment) + punishment.type().displayName());
        lore.add(" &7- &fСтатус: " + status);
        lore.add("");
        lore.add("&#C096AB «Причина»");
        lore.add(" &7- &f" + punishment.reason());
        lore.add("");
        lore.add("&#FFC67A «Детали»");
        if (actorView) lore.add(" &7- &fПолучил: &#D8DF9D" + punishment.targetName());
        lore.add(" &7- &fВыдал: &#D8DF9D" + punishment.actor());
        lore.add(" &7- &fСрок: &#D8DF9D" + TimeFormat.remaining(punishment.expiresAt(), now));
        lore.add(" &7- &fДата: &#D8DF9D" + DATE_FORMAT.format(Instant.ofEpochMilli(punishment.createdAt())));
        if (punishment.ip() != null) lore.add(" &7- &fIP: &#D8DF9D" + punishment.ip());
        lore.add(" &7- &fID: &8" + punishment.id());
        lore.add("");
        return button(material, typeColor(punishment) + "● &f" + punishment.type().displayName(), lore.toArray(String[]::new));
    }

    private ItemStack emptyItem(Query query) {
        String text = query.byActor() ? "Модератор ещё не выдавал наказаний." : "Для игрока ещё нет наказаний.";
        return button(Material.BARRIER, "&cИстория пуста", "",
                "&#A0EFA1 «История наказаний»",
                " &7- &f" + text,
                " &7- &fЗаписи появятся здесь",
                "");
    }

    private String typeColor(Punishment punishment) {
        return switch (punishment.type()) {
            case BAN, IP_BAN -> "&c";
            case MUTE -> "&#FFC67A";
            case WARN -> "&#C096AB";
        };
    }

    private void decorate(Inventory inventory) {
        ItemStack orange = pane(Material.ORANGE_STAINED_GLASS_PANE);
        ItemStack black = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot <= 8; slot++) inventory.setItem(slot, orange);
        for (int slot : new int[]{9, 17, 26, 35}) inventory.setItem(slot, orange);
        for (int slot : new int[]{18, 27, 36, 44}) inventory.setItem(slot, black);
        for (int slot = 45; slot <= 53; slot++) inventory.setItem(slot, black);
    }

    private ItemStack pane(Material material) { return button(material, " "); }

    private ItemStack button(Material material, String name, String... lore) {
        return GuiItemFactory.item(material, name, lore);
    }

    private record Query(UUID uuid, String name, boolean byActor) { }

    private static final class HistoryHolder implements InventoryHolder {
        private final Query query;
        private final int page;
        private final boolean hasNextPage;
        private Inventory inventory;
        private HistoryHolder(Query query, int page, boolean hasNextPage) { this.query = query; this.page = page; this.hasNextPage = hasNextPage; }
        private Query query() { return query; }
        private int page() { return page; }
        private boolean hasNextPage() { return hasNextPage; }
        private void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
