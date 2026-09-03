package com.mira.bounties;

import com.mira.bounties.api.MiraBountiesApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;

public final class MiraBountiesPlugin extends JavaPlugin implements Listener, TabExecutor, MiraBountiesApi {
    private Economy economy;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, List<Contribution>> bounties = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider is required. Disabling MiraBounties.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "bounties.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadData();
        var bountyCommand = Objects.requireNonNull(getCommand("bounty"), "bounty command missing");
        bountyCommand.setExecutor(this);
        bountyCommand.setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(MiraBountiesApi.class, this, this, ServicePriority.Normal);
        getServer().getScheduler().runTaskTimer(this, this::expireContributions, 20L * 60L, 20L * 60L * 10L);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) new BountyExpansion().register();
        getLogger().info("MiraBounties v" + getPluginMeta().getVersion() + " enabled with " + bounties.size() + " active target(s).");
    }

    @Override
    public void onDisable() {
        if (data != null) saveData();
        getServer().getServicesManager().unregisterAll(this);
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        var registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    @Override public double bounty(UUID player) { return bounties.getOrDefault(player, List.of()).stream().mapToDouble(Contribution::amount).sum(); }
    @Override public boolean hasBounty(UUID player) { return bounty(player) > 0.0; }

    @Override
    public Map<UUID, Double> top(int limit) {
        LinkedHashMap<UUID, Double> out = new LinkedHashMap<>();
        bounties.keySet().stream()
                .map(uuid -> Map.entry(uuid, bounty(uuid)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(Math.max(0, limit))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mirabounties.use")) { msg(sender, "&cYou do not have permission."); return true; }
        if (args.length == 0) {
            msg(sender, "&d/bounty <player> &7- view a bounty");
            msg(sender, "&d/bounty <player> <amount> &7- post a bounty");
            msg(sender, "&d/bounty top &7- richest active bounties");
            if (sender.hasPermission("mirabounties.admin")) msg(sender, "&d/bounty admin <set|clear> <player> [amount]");
            return true;
        }
        if (args[0].equalsIgnoreCase("top")) return showTop(sender);
        if (args[0].equalsIgnoreCase("admin")) return admin(sender, args);

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target.getName() == null && !target.hasPlayedBefore() && !target.isOnline()) { msg(sender, "&cPlayer not found."); return true; }
        if (args.length == 1) {
            msg(sender, "&d" + name(target) + " &7has a bounty of &f" + money(bounty(target.getUniqueId())) + "&7.");
            return true;
        }
        if (!(sender instanceof Player player)) { msg(sender, "&cPlayers must post normal bounties. Use the admin command from console."); return true; }
        if (!sender.hasPermission("mirabounties.post")) { msg(sender, "&cYou cannot post bounties."); return true; }
        if (target.getUniqueId().equals(player.getUniqueId())) { msg(sender, "&cYou cannot place a bounty on yourself."); return true; }

        double amount;
        try { amount = Double.parseDouble(args[1]); }
        catch (NumberFormatException ex) { msg(sender, "&cAmount must be a number."); return true; }
        double min = getConfig().getDouble("minimum-post", 1000.0);
        double max = getConfig().getDouble("maximum-post", 1_000_000_000.0);
        if (!Double.isFinite(amount) || amount < min || amount > max) {
            msg(sender, "&cAmount must be between " + money(min) + " and " + money(max) + ".");
            return true;
        }
        if (economy.getBalance(player) < amount) { msg(sender, "&cYou do not have enough money."); return true; }
        var result = economy.withdrawPlayer(player, amount);
        if (!result.transactionSuccess()) { msg(sender, "&cEconomy transaction failed: " + result.errorMessage); return true; }

        long days = Math.max(0, getConfig().getLong("expire-days", 30L));
        long expiresAt = days == 0 ? 0L : System.currentTimeMillis() + days * 86_400_000L;
        bounties.computeIfAbsent(target.getUniqueId(), ignored -> new ArrayList<>())
                .add(new Contribution(UUID.randomUUID(), player.getUniqueId(), amount, System.currentTimeMillis(), expiresAt));
        saveData();
        msg(sender, "&aPosted &f" + money(amount) + " &aon &f" + name(target) + "&a. Total bounty: &f" + money(bounty(target.getUniqueId())));
        if (amount >= getConfig().getDouble("broadcast.post-threshold", 100000.0)) {
            broadcast("&6&lBOUNTY &e" + player.getName() + " &7placed &6" + money(amount) + " &7on &c" + name(target) + "&7. Total: &6" + money(bounty(target.getUniqueId())));
        }
        return true;
    }

    private boolean showTop(CommandSender sender) {
        msg(sender, "&5&m------&d Top Bounties &5&m------");
        int place = 1;
        for (var entry : top(10).entrySet()) {
            msg(sender, "&d#" + place++ + " &f" + name(Bukkit.getOfflinePlayer(entry.getKey())) + " &7- &6" + money(entry.getValue()));
        }
        if (place == 1) msg(sender, "&7No active bounties.");
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mirabounties.admin")) { msg(sender, "&cYou do not have permission."); return true; }
        if (args.length < 3) { msg(sender, "&eUsage: /bounty admin <set|clear> <player> [amount]"); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (args[1].equalsIgnoreCase("clear")) {
            bounties.remove(target.getUniqueId());
            saveData();
            msg(sender, "&aCleared bounty on &f" + name(target) + "&a.");
            return true;
        }
        if (args[1].equalsIgnoreCase("set")) {
            if (args.length < 4) { msg(sender, "&eUsage: /bounty admin set <player> <amount>"); return true; }
            double amount;
            try { amount = Double.parseDouble(args[3]); } catch (NumberFormatException ex) { msg(sender, "&cInvalid amount."); return true; }
            if (!Double.isFinite(amount) || amount < 0) { msg(sender, "&cInvalid amount."); return true; }
            bounties.remove(target.getUniqueId());
            if (amount > 0) bounties.put(target.getUniqueId(), new ArrayList<>(List.of(new Contribution(UUID.randomUUID(), null, amount, System.currentTimeMillis(), 0L))));
            saveData();
            msg(sender, "&aSet bounty on &f" + name(target) + " &ato &f" + money(amount) + "&a.");
            return true;
        }
        msg(sender, "&cUnknown admin action.");
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        double amount = bounty(victim.getUniqueId());
        if (amount <= 0) return;
        bounties.remove(victim.getUniqueId());
        var deposit = economy.depositPlayer(killer, amount);
        if (!deposit.transactionSuccess()) {
            getLogger().severe("Could not pay bounty " + amount + " to " + killer.getName() + ": " + deposit.errorMessage);
            bounties.put(victim.getUniqueId(), new ArrayList<>(List.of(new Contribution(UUID.randomUUID(), null, amount, System.currentTimeMillis(), 0L))));
            return;
        }
        saveData();
        msg(killer, "&aYou claimed &f" + money(amount) + " &afrom &f" + victim.getName() + "&a's bounty.");
        if (amount >= getConfig().getDouble("broadcast.claim-threshold", 100000.0)) {
            broadcast("&6&lBOUNTY CLAIMED &e" + killer.getName() + " &7killed &c" + victim.getName() + " &7for &6" + money(amount) + "&7!");
        }
    }

    private void expireContributions() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<UUID, List<Contribution>>> targets = bounties.entrySet().iterator();
        while (targets.hasNext()) {
            var target = targets.next();
            Iterator<Contribution> iterator = target.getValue().iterator();
            while (iterator.hasNext()) {
                Contribution contribution = iterator.next();
                if (contribution.expiresAt() <= 0 || contribution.expiresAt() > now) continue;
                if (getConfig().getBoolean("refund-expired", true) && contribution.poster() != null) {
                    OfflinePlayer poster = Bukkit.getOfflinePlayer(contribution.poster());
                    economy.depositPlayer(poster, contribution.amount());
                    if (poster.isOnline() && poster.getPlayer() != null) msg(poster.getPlayer(), "&eA bounty contribution of &f" + money(contribution.amount()) + " &eexpired and was refunded.");
                }
                iterator.remove();
                changed = true;
            }
            if (target.getValue().isEmpty()) targets.remove();
        }
        if (changed) saveData();
    }

    private void loadData() {
        bounties.clear();
        ConfigurationSection root = data.getConfigurationSection("bounties");
        if (root == null) return;
        for (String targetRaw : root.getKeys(false)) {
            UUID target;
            try { target = UUID.fromString(targetRaw); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection section = root.getConfigurationSection(targetRaw + ".contributions");
            if (section == null) continue;
            List<Contribution> list = new ArrayList<>();
            for (String idRaw : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(idRaw);
                    String posterRaw = section.getString(idRaw + ".poster");
                    UUID poster = posterRaw == null || posterRaw.isBlank() ? null : UUID.fromString(posterRaw);
                    double amount = section.getDouble(idRaw + ".amount");
                    long created = section.getLong(idRaw + ".created-at");
                    long expires = section.getLong(idRaw + ".expires-at");
                    if (amount > 0) list.add(new Contribution(id, poster, amount, created, expires));
                } catch (Exception ignored) { }
            }
            if (!list.isEmpty()) bounties.put(target, list);
        }
    }

    private void saveData() {
        data.set("bounties", null);
        for (var target : bounties.entrySet()) {
            for (Contribution c : target.getValue()) {
                String path = "bounties." + target.getKey() + ".contributions." + c.id();
                data.set(path + ".poster", c.poster() == null ? null : c.poster().toString());
                data.set(path + ".amount", c.amount());
                data.set(path + ".created-at", c.createdAt());
                data.set(path + ".expires-at", c.expiresAt());
            }
        }
        try { data.save(dataFile); } catch (IOException ex) { getLogger().severe("Could not save bounties.yml: " + ex.getMessage()); }
    }

    private String money(double amount) { return NumberFormat.getCurrencyInstance(Locale.US).format(amount); }
    private String name(OfflinePlayer player) { return player.getName() == null ? player.getUniqueId().toString() : player.getName(); }
    private void msg(CommandSender sender, String message) { sender.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&5&lMira &8>> &r") + message)); }
    private void broadcast(String message) { Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&5&lMira &8>> &r") + message)); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("top"));
            if (sender.hasPermission("mirabounties.admin")) values.add("admin");
            values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            return match(args[0], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return match(args[1], List.of("set", "clear"));
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) return match(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        return List.of();
    }

    private static List<String> match(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }

    private record Contribution(UUID id, UUID poster, double amount, long createdAt, long expiresAt) { }

    private final class BountyExpansion extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "mirabounties"; }
        @Override public String getAuthor() { return "FiveS"; }
        @Override public String getVersion() { return getPluginMeta().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            if (params.equalsIgnoreCase("bounty")) return player == null ? "0" : String.format(Locale.US, "%.2f", bounty(player.getUniqueId()));
            if (params.equalsIgnoreCase("bounty_formatted")) return player == null ? money(0) : money(bounty(player.getUniqueId()));
            String lower = params.toLowerCase(Locale.ROOT);
            if (lower.startsWith("top_")) {
                String[] parts = lower.split("_");
                if (parts.length == 3) {
                    try {
                        int rank = Integer.parseInt(parts[1]);
                        if (rank < 1 || rank > 10) return "";
                        var entries = new ArrayList<>(top(10).entrySet());
                        if (entries.size() < rank) return "";
                        var entry = entries.get(rank - 1);
                        if (parts[2].equals("name")) return name(Bukkit.getOfflinePlayer(entry.getKey()));
                        if (parts[2].equals("value")) return String.format(Locale.US, "%.2f", entry.getValue());
                        if (parts[2].equals("formatted")) return money(entry.getValue());
                    } catch (NumberFormatException ignored) { }
                }
            }
            return null;
        }
    }
}
