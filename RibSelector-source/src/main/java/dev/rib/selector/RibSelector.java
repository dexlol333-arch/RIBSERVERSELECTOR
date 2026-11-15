package dev.rib.selector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RibSelector extends JavaPlugin implements Listener, TabExecutor {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey key;

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "server_selector");
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (String cmd : List.of("servers", "ribserver", "ribselector")) {
            var c = getCommand(cmd);
            if (c != null) {
                c.setExecutor(this);
                c.setTabCompleter(this);
            }
        }
        String here = thisServer();
        if (here.isEmpty()) {
            getLogger().warning("this-server is not set in config.yml. Set it to the id of THIS server (e.g. ffa or smp).");
        } else {
            getLogger().info("This server is '" + here + "'.");
        }
        // Give compasses to anyone already online (e.g. after /reload).
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (getConfig().getBoolean("compass.give-on-join", true)) giveCompass(p);
        }
    }

    // ------------------------------------------------------------------ compass

    private String thisServer() {
        return getConfig().getString("this-server", "").trim().toLowerCase(Locale.ROOT);
    }

    private Component text(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private ItemStack createCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(getConfig().getString("compass.name", "&c&lServer Selector")));
        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("compass.lore")) lore.add(text(line));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isSelector(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public void giveCompass(Player player) {
        PlayerInventory inv = player.getInventory();
        // Remove old selector compasses so players never end up with two.
        for (int i = 0; i < inv.getSize(); i++) {
            if (isSelector(inv.getItem(i))) inv.setItem(i, null);
        }
        int slot = Math.max(0, Math.min(8, getConfig().getInt("compass.slot", 4)));
        ItemStack existing = inv.getItem(slot);
        if (existing != null && !existing.getType().isAir()) {
            // Don't delete whatever was in that slot - move it somewhere else.
            inv.setItem(slot, null);
            var leftover = inv.addItem(existing);
            leftover.values().forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
        }
        inv.setItem(slot, createCompass());
    }

    // ------------------------------------------------------------------ menu

    /** Escapes a string for use inside an SNBT "quoted string". */
    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String color(String c) {
        return (c == null || c.isBlank()) ? "white" : c.trim();
    }

    /** Opens Minecraft's built-in dialog screen (1.21.6+) with one button per server. */
    public void openMenu(Player player) {
        ConfigurationSection servers = getConfig().getConfigurationSection("servers");
        if (servers == null || servers.getKeys(false).isEmpty()) {
            player.sendMessage(text("&cNo servers are set up in RibSelector's config."));
            return;
        }
        String here = thisServer();
        int width = Math.max(40, Math.min(1024, getConfig().getInt("menu.button-width", 160)));

        StringBuilder actions = new StringBuilder();
        for (String id : servers.getKeys(false)) {
            ConfigurationSection s = servers.getConfigurationSection(id);
            if (s == null) continue;
            String name = s.getString("name", id.toUpperCase(Locale.ROOT));
            String tooltip = id.equalsIgnoreCase(here) ? "You're already on this server" : s.getString("tooltip", "Click to join " + name);
            if (!actions.isEmpty()) actions.append(',');
            actions.append("{label:{text:").append(q(name))
                    .append(",color:").append(q(color(s.getString("color"))))
                    .append(",bold:1b},tooltip:{text:").append(q(tooltip))
                    .append(",color:\"gray\"},width:").append(width)
                    .append(",action:{type:\"run_command\",command:").append(q("/ribserver " + id)).append("}}");
        }

        String dialog = "{type:\"minecraft:multi_action\""
                + ",title:{text:" + q(getConfig().getString("menu.title", "Pick a server to play."))
                + ",color:" + q(color(getConfig().getString("menu.title-color", "#AAAAAA"))) + "}"
                + ",can_close_with_escape:1b"
                + ",columns:" + Math.max(1, getConfig().getInt("menu.columns", 2))
                + ",actions:[" + actions + "]}";

        // Target by name: some servers' UUIDs don't resolve as a player selector.
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "minecraft:dialog show " + player.getName() + " " + dialog);
            if (!ok) {
                getLogger().warning("Couldn't open the dialog for " + player.getName() + ".");
            }
        } catch (Exception e) {
            getLogger().warning("Couldn't open the dialog for " + player.getName() + ": " + e.getMessage());
            player.sendMessage(text("&cThe server menu couldn't open. Use &f/ribserver ffa &cor &f/ribserver smp&c."));
        }
    }

    // ------------------------------------------------------------------ transfer

    private void send(Player player, String id) {
        ConfigurationSection s = getConfig().getConfigurationSection("servers." + id.toLowerCase(Locale.ROOT));
        if (s == null) {
            player.sendMessage(text(getConfig().getString("messages.unknown", "&cThat server doesn't exist.")));
            return;
        }
        String name = "&f" + s.getString("name", id);
        if (id.equalsIgnoreCase(thisServer())) {
            player.sendMessage(text(getConfig().getString("messages.already-here", "&cYou're already on %server%&c!")
                    .replace("%server%", name)));
            return;
        }
        String host = s.getString("host", "").trim();
        int port = s.getInt("port", 25565);
        if (host.isEmpty()) {
            player.sendMessage(text("&cThat server has no host set in the config."));
            return;
        }
        player.sendMessage(text(getConfig().getString("messages.sending", "&aSending you to %server%&a...")
                .replace("%server%", name)));
        player.transfer(host, port);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "servers" -> {
                if (sender instanceof Player p) openMenu(p);
                else sender.sendMessage("Players only.");
            }
            case "ribserver" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                } else if (args.length == 0) {
                    openMenu(p);
                } else {
                    send(p, args[0]);
                }
            }
            case "ribselector" -> {
                if (args.length == 0) {
                    sender.sendMessage(text("&7/ribselector reload &8- &7reload the config"));
                    sender.sendMessage(text("&7/ribselector give [player] &8- &7give the compass"));
                    return true;
                }
                if (args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    sender.sendMessage(text(getConfig().getString("messages.reloaded", "&aRibSelector reloaded.")));
                } else if (args[0].equalsIgnoreCase("give")) {
                    Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                    if (target == null) {
                        sender.sendMessage(text("&cPlayer not found."));
                        return true;
                    }
                    giveCompass(target);
                    sender.sendMessage(text(getConfig().getString("messages.given", "&aGave the server selector to %player%.")
                            .replace("%player%", target.getName())));
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("ribserver") && args.length == 1) {
            ConfigurationSection s = getConfig().getConfigurationSection("servers");
            return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
        }
        if (name.equals("ribselector")) {
            if (args.length == 1) return List.of("reload", "give");
            if (args.length == 2 && args[0].equalsIgnoreCase("give")) return null; // player names
        }
        return List.of();
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("compass.give-on-join", true)) giveCompass(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!getConfig().getBoolean("compass.give-on-respawn", true)) return;
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) giveCompass(p); });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isSelector(event.getItem())) return;
        event.setCancelled(true);
        openMenu(event.getPlayer());
    }

    private boolean locked() {
        return getConfig().getBoolean("compass.locked", true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked() && isSelector(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!locked()) return;
        boolean hit = isSelector(event.getCurrentItem()) || isSelector(event.getCursor());
        if (!hit && event.getClick() == ClickType.NUMBER_KEY && event.getWhoClicked() instanceof Player p) {
            hit = isSelector(p.getInventory().getItem(event.getHotbarButton()));
        }
        if (!hit && event.getClick() == ClickType.SWAP_OFFHAND && event.getWhoClicked() instanceof Player p) {
            hit = isSelector(p.getInventory().getItemInOffHand());
        }
        if (hit) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (locked() && isSelector(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (locked() && (isSelector(event.getMainHandItem()) || isSelector(event.getOffHandItem()))) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isSelector);
    }
}
