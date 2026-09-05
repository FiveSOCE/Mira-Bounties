package com.mira.bounties;

import com.mira.bounties.api.MiraBountiesApi;
import com.mira.bounties.util.CosmeticsBridge;
import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
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
    private MiraCore core;
    private File dataFile;
    private YamlConfiguration data;

    private final Map<UUID, List<Contribution>> bounties = new HashMap<>();
    private final List<ClaimRecord> claimHistory = new ArrayList<>();
    private double totalPosted;
    private double totalClaimed;
    private final Map<UUID, PendingIncrease> pendingIncreases = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();

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
        core.services().register(MiraBountiesApi.class, this);
        core.modules().register(this, "MiraBounties");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Persistent bounty contributions, hunter history, milestones and leaderboard data ready");

        getServer().getScheduler().runTaskTimer(this, this::expireContributions, 20L * 60L, 20L * 60L * 10L);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new BountyExpansion().register();
        }

        getLogger().info("MiraBounties v" + getPluginMeta().getVersion() + " enabled with "
                + bounties.size() + " active target(s) and " + claimHistory.size() + " recorded claim(s).");
    }

    @Override
    public void onDisable() {
        if (data != null) saveData();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            core.services().unregister(MiraBountiesApi.class, this);
            core.modules().unregister(this);
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        var registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    @Override
    public double bounty(UUID player) {
        return bounties.getOrDefault(player, List.of()).stream().mapToDouble(Contribution::amount).sum();
    }

    @Override public boolean hasBounty(UUID player) { return bounty(player) > 0.0D; }

    @Override
    public Map<UUID, Double> top(int limit) {
        LinkedHashMap<UUID, Double> out = new LinkedHashMap<>();
        bounties.keySet().stream()
                .map(uuid -> Map.entry(uuid, bounty(uuid)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(Math.max(0, limit))
                .forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public int claims(UUID hunter) {
        if (hunter == null) return 0;
        return (int) claimHistory.stream().filter(record -> hunter.equals(record.killer())).count();
    }

    @Override
    public double claimedTotal(UUID hunter) {
        if (hunter == null) return 0D;
        return claimHistory.stream().filter(record -> hunter.equals(record.killer()))
                .mapToDouble(ClaimRecord::amount).sum();
    }

    @Override
    public Map<UUID, Double> topHunters(int limit) {
        Map<UUID, Double> totals = new HashMap<>();
        for (ClaimRecord record : claimHistory) {
            totals.merge(record.killer(), record.amount(), Double::sum);
        }
        LinkedHashMap<UUID, Double> out = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(Math.max(0, limit))
                .forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(out);
    }

    @Override public double totalPosted() { return totalPosted; }
    @Override public double totalClaimed() { return totalClaimed; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mirabounties.use")) {
            msg(sender, "&cYou do not have permission.");
            return true;
        }

        if (args.length == 0) {
            msg(sender, "&d/bounty <player> &7- view a bounty");
            msg(sender, "&d/bounty <player> <amount> &7- post a bounty");
            msg(sender, "&d/bounty add <player> <amount> &7- increase an existing bounty with confirmation");
            msg(sender, "&d/bounty confirm &7- confirm your pending bounty increase");
            msg(sender, "&d/bounty top &7- richest active bounties");
            msg(sender, "&d/bounty hunters &7- top bounty hunters by money claimed");
            msg(sender, "&d/bounty history [player] &7- recent bounty claims");
            if (sender.hasPermission("mirabounties.admin")) {
                msg(sender, "&d/bounty admin <set|clear> <player> [amount]");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("top")) return showTop(sender);
        if (args[0].equalsIgnoreCase("add")) return prepareIncrease(sender, args);
        if (args[0].equalsIgnoreCase("confirm")) return confirmIncrease(sender);
        if (args[0].equalsIgnoreCase("hunters")) return showHunters(sender);
        if (args[0].equalsIgnoreCase("history")) return showHistory(sender, args);
        if (args[0].equalsIgnoreCase("admin")) return admin(sender, args);

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target.getName() == null && !target.hasPlayedBefore() && !target.isOnline()) {
            msg(sender, "&cPlayer not found.");
            return true;
        }

        if (args.length == 1) {
            msg(sender, "&d" + name(target) + " &7has a bounty of &f" + money(bounty(target.getUniqueId())) + "&7.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers must post normal bounties. Use the admin command from console.");
            return true;
        }
        if (!sender.hasPermission("mirabounties.post")) {
            msg(sender, "&cYou cannot post bounties.");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(sender, "&cYou cannot place a bounty on yourself.");
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException ex) {
            msg(sender, "&cAmount must be a number.");
            return true;
        }

        return postContribution(player, target, amount, false);
    }

    private boolean prepareIncrease(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers must fund bounty increases.");
            return true;
        }
        if (!sender.hasPermission("mirabounties.post")) {
            msg(sender, "&cYou cannot post bounties.");
            return true;
        }
        if (args.length < 3) {
            msg(sender, "&eUsage: /bounty add <player> <amount>");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target.getName() == null && !target.hasPlayedBefore() && !target.isOnline()) {
            msg(sender, "&cPlayer not found.");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(sender, "&cYou cannot increase a bounty on yourself.");
            return true;
        }
        double existing = bounty(target.getUniqueId());
        if (existing <= 0D) {
            msg(sender, "&cThat player has no active bounty. Use /bounty " + name(target) + " <amount> to create one.");
            return true;
        }

        double amount;
        try { amount = Double.parseDouble(args[2]); }
        catch (NumberFormatException ex) {
            msg(sender, "&cAmount must be a number.");
            return true;
        }
        if (!validPostAmount(amount)) {
            double min = getConfig().getDouble("minimum-post", 1000.0);
            double max = getConfig().getDouble("maximum-post", 1_000_000_000.0);
            msg(sender, "&cAmount must be between " + money(min) + " and " + money(max) + ".");
            return true;
        }

        long seconds = Math.max(10L, getConfig().getLong("increase-confirm-seconds", 30L));
        pendingIncreases.put(player.getUniqueId(),
                new PendingIncrease(target.getUniqueId(), name(target), amount, existing,
                        System.currentTimeMillis() + seconds * 1000L));

        msg(sender, "&dBounty Increase Confirmation");
        msg(sender, "&7Target: &f" + name(target));
        msg(sender, "&7Existing bounty: &f" + money(existing));
        msg(sender, "&7Adding: &6" + money(amount));
        msg(sender, "&7New total: &a" + money(existing + amount));
        msg(sender, "&eNo money has been taken. Use &f/bounty confirm &ewithin " + seconds + "s.");
        return true;
    }

    private boolean confirmIncrease(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, "&cPlayers only.");
            return true;
        }
        PendingIncrease pending = pendingIncreases.remove(player.getUniqueId());
        if (pending == null || System.currentTimeMillis() >= pending.expiresAt()) {
            msg(sender, "&cYou do not have a valid pending bounty increase.");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(pending.target());
        double current = bounty(pending.target());
        if (current <= 0D) {
            msg(sender, "&cThat bounty is no longer active. Nothing was charged.");
            return true;
        }
        return postContribution(player, target, pending.amount(), true);
    }

    private boolean postContribution(Player player, OfflinePlayer target, double amount, boolean increase) {
        if (!validPostAmount(amount)) {
            double min = getConfig().getDouble("minimum-post", 1000.0);
            double max = getConfig().getDouble("maximum-post", 1_000_000_000.0);
            msg(player, "&cAmount must be between " + money(min) + " and " + money(max) + ".");
            return true;
        }
        if (economy.getBalance(player) < amount) {
            msg(player, "&cYou do not have enough money.");
            return true;
        }

        var result = economy.withdrawPlayer(player, amount);
        if (!result.transactionSuccess()) {
            msg(player, "&cEconomy transaction failed: " + result.errorMessage);
            return true;
        }

        long days = Math.max(0, getConfig().getLong("expire-days", 30L));
        long expiresAt = days == 0 ? 0L : System.currentTimeMillis() + days * 86_400_000L;
        bounties.computeIfAbsent(target.getUniqueId(), ignored -> new ArrayList<>())
                .add(new Contribution(UUID.randomUUID(), player.getUniqueId(), amount,
                        System.currentTimeMillis(), expiresAt));
        totalPosted += amount;
        saveData();

        core.audit().record("MiraBounties", increase ? "BOUNTY_INCREASED" : "BOUNTY_POSTED",
                player.getUniqueId(), player.getName(), target.getUniqueId().toString(),
                increase ? "Increased active bounty" : "Posted bounty contribution",
                Map.of("amount", Double.toString(amount), "targetName", name(target),
                        "newTotal", Double.toString(bounty(target.getUniqueId()))));

        msg(player, (increase ? "&aAdded &f" : "&aPosted &f") + money(amount) + " &aon &f" + name(target)
                + "&a. Total bounty: &f" + money(bounty(target.getUniqueId())));
        Player onlineTarget = Bukkit.getPlayer(target.getUniqueId());
        if (onlineTarget != null) CosmeticsBridge.play(onlineTarget, "bounty_placed");

        if (amount >= getConfig().getDouble("broadcast.post-threshold", 100000.0)) {
            broadcast("&6&lBOUNTY &e" + player.getName() + (increase ? " &7added &6" : " &7placed &6")
                    + money(amount) + " &7on &c" + name(target) + "&7. Total: &6" + money(bounty(target.getUniqueId())));
        }
        return true;
    }

    private boolean validPostAmount(double amount) {
        double min = getConfig().getDouble("minimum-post", 1000.0);
        double max = getConfig().getDouble("maximum-post", 1_000_000_000.0);
        return Double.isFinite(amount) && amount >= min && amount <= max;
    }

    private boolean showTop(CommandSender sender) {
        msg(sender, "&5&m------&d Top Bounties &5&m------");
        int place = 1;
        for (var entry : top(10).entrySet()) {
            msg(sender, "&d#" + place++ + " &f" + name(Bukkit.getOfflinePlayer(entry.getKey()))
                    + " &7- &6" + money(entry.getValue()));
        }
        if (place == 1) msg(sender, "&7No active bounties.");
        return true;
    }

    private boolean showHunters(CommandSender sender) {
        msg(sender, "&5&m------&d Top Bounty Hunters &5&m------");
        int place = 1;
        for (var entry : topHunters(10).entrySet()) {
            msg(sender, "&d#" + place++ + " &f" + name(Bukkit.getOfflinePlayer(entry.getKey()))
                    + " &7- &6" + money(entry.getValue())
                    + " &8(" + claims(entry.getKey()) + " claim" + (claims(entry.getKey()) == 1 ? "" : "s") + ")");
        }
        if (place == 1) msg(sender, "&7No bounty claims recorded yet.");
        return true;
    }

    private boolean showHistory(CommandSender sender, String[] args) {
        UUID filter = null;
        String filterName = null;
        if (args.length >= 2) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
            if (player.getName() == null && !player.hasPlayedBefore() && !player.isOnline()) {
                msg(sender, "&cPlayer not found.");
                return true;
            }
            filter = player.getUniqueId();
            filterName = name(player);
        }

        final UUID wanted = filter;
        List<ClaimRecord> rows = claimHistory.stream()
                .filter(record -> wanted == null || wanted.equals(record.killer()) || wanted.equals(record.victim()))
                .sorted(Comparator.comparingLong(ClaimRecord::time).reversed())
                .limit(10)
                .toList();

        msg(sender, "&5&m------&d Bounty Claim History" + (filterName == null ? "" : " - " + filterName) + " &5&m------");
        if (rows.isEmpty()) {
            msg(sender, "&7No matching bounty claims recorded.");
            return true;
        }

        for (ClaimRecord row : rows) {
            msg(sender, "&f" + row.killerName() + " &7claimed &6" + money(row.amount())
                    + " &7from &c" + row.victimName() + " &8(" + relativeAge(row.time()) + ")");
        }
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mirabounties.admin")) {
            msg(sender, "&cYou do not have permission.");
            return true;
        }
        if (args.length < 3) {
            msg(sender, "&eUsage: /bounty admin <set|clear> <player> [amount]");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (args[1].equalsIgnoreCase("clear")) {
            bounties.remove(target.getUniqueId());
            saveData();
            core.audit().record("MiraBounties", "BOUNTY_ADMIN_CLEAR",
                    sender instanceof Player p ? p.getUniqueId() : null, sender.getName(),
                    target.getUniqueId().toString(), "Cleared active bounty");
            msg(sender, "&aCleared bounty on &f" + name(target) + "&a.");
            return true;
        }

        if (args[1].equalsIgnoreCase("set")) {
            if (args.length < 4) {
                msg(sender, "&eUsage: /bounty admin set <player> <amount>");
                return true;
            }
            double amount;
            try {
                amount = Double.parseDouble(args[3]);
            } catch (NumberFormatException ex) {
                msg(sender, "&cInvalid amount.");
                return true;
            }
            if (!Double.isFinite(amount) || amount < 0) {
                msg(sender, "&cInvalid amount.");
                return true;
            }

            bounties.remove(target.getUniqueId());
            if (amount > 0) {
                bounties.put(target.getUniqueId(), new ArrayList<>(List.of(
                        new Contribution(UUID.randomUUID(), null, amount, System.currentTimeMillis(), 0L))));
            }
            saveData();
            core.audit().record("MiraBounties", "BOUNTY_ADMIN_SET",
                    sender instanceof Player p ? p.getUniqueId() : null, sender.getName(),
                    target.getUniqueId().toString(), "Set active bounty",
                    Map.of("amount", Double.toString(amount)));
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

        var deposit = economy.depositPlayer(killer, amount);
        if (!deposit.transactionSuccess()) {
            getLogger().severe("Could not pay bounty " + amount + " to " + killer.getName() + ": " + deposit.errorMessage);
            return;
        }

        bounties.remove(victim.getUniqueId());
        recordClaim(killer, victim, amount);
        totalClaimed += amount;
        saveData();

        core.milestones().award(killer.getUniqueId(), "mirabounties.first_claim", "MiraBounties",
                Map.of("victim", victim.getName(), "amount", Double.toString(amount)));

        double highValue = Math.max(0D, getConfig().getDouble("milestones.high-value-claim", 100000D));
        if (highValue > 0D && amount >= highValue) {
            core.milestones().award(killer.getUniqueId(), "mirabounties.high_value_claim", "MiraBounties",
                    Map.of("victim", victim.getName(), "amount", Double.toString(amount)));
        }

        core.audit().record("MiraBounties", "BOUNTY_CLAIMED", killer.getUniqueId(), killer.getName(),
                victim.getUniqueId().toString(), "Claimed player bounty",
                Map.of("amount", Double.toString(amount), "victimName", victim.getName()));

        msg(killer, "&aYou claimed &f" + money(amount) + " &afrom &f" + victim.getName() + "&a's bounty.");

        double claimThreshold = getConfig().getDouble("broadcast.claim-threshold", 100000.0);
        if (amount >= claimThreshold) {
            CosmeticsBridge.playNearby(killer.getLocation(), "bounty_claimed_large", 20.0D);
        } else {
            CosmeticsBridge.play(killer, "bounty_claimed");
        }

        if (amount >= claimThreshold) {
            broadcast("&6&lBOUNTY CLAIMED &e" + killer.getName() + " &7killed &c" + victim.getName()
                    + " &7for &6" + money(amount) + "&7!");
        }
    }

    private void recordClaim(Player killer, Player victim, double amount) {
        claimHistory.add(new ClaimRecord(killer.getUniqueId(), killer.getName(), victim.getUniqueId(),
                victim.getName(), amount, System.currentTimeMillis()));
        int limit = Math.max(100, getConfig().getInt("history-limit", 5000));
        while (claimHistory.size() > limit) claimHistory.removeFirst();
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
                    var refund = economy.depositPlayer(poster, contribution.amount());
                    if (!refund.transactionSuccess()) {
                        getLogger().warning("Could not refund expired bounty contribution " + contribution.id()
                                + ": " + refund.errorMessage + ". Contribution retained for retry.");
                        continue;
                    }
                    if (poster.isOnline() && poster.getPlayer() != null) {
                        msg(poster.getPlayer(), "&eA bounty contribution of &f" + money(contribution.amount())
                                + " &eexpired and was refunded.");
                    }
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
        claimHistory.clear();

        ConfigurationSection root = data.getConfigurationSection("bounties");
        if (root != null) {
            for (String targetRaw : root.getKeys(false)) {
                UUID target;
                try {
                    target = UUID.fromString(targetRaw);
                } catch (IllegalArgumentException ex) {
                    continue;
                }

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
                    } catch (Exception ignored) {
                    }
                }
                if (!list.isEmpty()) bounties.put(target, list);
            }
        }

        for (Map<?, ?> raw : data.getMapList("history.claims")) {
            try {
                UUID killer = UUID.fromString(String.valueOf(raw.get("killer")));
                UUID victim = UUID.fromString(String.valueOf(raw.get("victim")));
                String killerName = String.valueOf(raw.containsKey("killer-name") ? raw.get("killer-name") : killer.toString());
                String victimName = String.valueOf(raw.containsKey("victim-name") ? raw.get("victim-name") : victim.toString());
                double amount = number(raw.get("amount"));
                long time = longNumber(raw.get("time"));
                if (amount > 0 && time > 0) {
                    claimHistory.add(new ClaimRecord(killer, killerName, victim, victimName, amount, time));
                }
            } catch (Exception ignored) {
            }
        }

        totalPosted = Math.max(0D, data.getDouble("stats.total-posted", 0D));
        totalClaimed = Math.max(0D, data.getDouble("stats.total-claimed", 0D));
    }

    private void saveData() {
        data.set("bounties", null);
        for (var target : bounties.entrySet()) {
            for (Contribution contribution : target.getValue()) {
                String path = "bounties." + target.getKey() + ".contributions." + contribution.id();
                data.set(path + ".poster", contribution.poster() == null ? null : contribution.poster().toString());
                data.set(path + ".amount", contribution.amount());
                data.set(path + ".created-at", contribution.createdAt());
                data.set(path + ".expires-at", contribution.expiresAt());
            }
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (ClaimRecord record : claimHistory) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("killer", record.killer().toString());
            row.put("killer-name", record.killerName());
            row.put("victim", record.victim().toString());
            row.put("victim-name", record.victimName());
            row.put("amount", record.amount());
            row.put("time", record.time());
            history.add(row);
        }
        data.set("history.claims", history);
        data.set("stats.total-posted", totalPosted);
        data.set("stats.total-claimed", totalClaimed);

        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save bounties.yml: " + ex.getMessage());
        }
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0D; }
    }

    private long longNumber(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return 0L; }
    }

    private String money(double amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    private String name(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private String relativeAge(long time) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - time) / 1000L);
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.prefix", "&5&lMira &8>> &r") + message));
    }

    private void broadcast(String message) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.prefix", "&5&lMira &8>> &r") + message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("top", "hunters", "history", "add", "confirm"));
            if (sender.hasPermission("mirabounties.admin")) values.add("admin");
            values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            return match(args[0], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return match(args[1], List.of("set", "clear"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            return match(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private static List<String> match(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .distinct().sorted().toList();
    }

    private record PendingIncrease(UUID target, String targetName, double amount, double previousTotal, long expiresAt) {}
    private record Contribution(UUID id, UUID poster, double amount, long createdAt, long expiresAt) {}
    private record ClaimRecord(UUID killer, String killerName, UUID victim, String victimName, double amount, long time) {}

    private final class BountyExpansion extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "mirabounties"; }
        @Override public String getAuthor() { return "FiveS"; }
        @Override public String getVersion() { return getPluginMeta().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            if (params.equalsIgnoreCase("bounty")) {
                return player == null ? "0" : String.format(Locale.US, "%.2f", bounty(player.getUniqueId()));
            }
            if (params.equalsIgnoreCase("bounty_formatted")) {
                return player == null ? money(0) : money(bounty(player.getUniqueId()));
            }
            if (params.equalsIgnoreCase("claims")) {
                return player == null ? "0" : Integer.toString(claims(player.getUniqueId()));
            }
            if (params.equalsIgnoreCase("claimed_total")) {
                return player == null ? "0" : String.format(Locale.US, "%.2f", claimedTotal(player.getUniqueId()));
            }
            if (params.equalsIgnoreCase("claimed_total_formatted")) {
                return player == null ? money(0) : money(claimedTotal(player.getUniqueId()));
            }
            if (params.equalsIgnoreCase("total_posted")) return String.format(Locale.US, "%.2f", totalPosted());
            if (params.equalsIgnoreCase("total_claimed")) return String.format(Locale.US, "%.2f", totalClaimed());

            ClaimRecord last = claimHistory.isEmpty() ? null : claimHistory.getLast();
            if (params.equalsIgnoreCase("last_claim_killer")) return last == null ? "" : last.killerName();
            if (params.equalsIgnoreCase("last_claim_victim")) return last == null ? "" : last.victimName();
            if (params.equalsIgnoreCase("last_claim_value")) return last == null ? "0" : String.format(Locale.US, "%.2f", last.amount());
            if (params.equalsIgnoreCase("last_claim_formatted")) return last == null ? money(0) : money(last.amount());

            String lower = params.toLowerCase(Locale.ROOT);
            if (lower.startsWith("top_")) {
                return rankedPlaceholder(lower, top(10));
            }
            if (lower.startsWith("hunter_top_")) {
                return rankedPlaceholder(lower.substring("hunter_".length()), topHunters(10));
            }
            return null;
        }

        private String rankedPlaceholder(String params, Map<UUID, Double> ranked) {
            String[] parts = params.split("_");
            if (parts.length != 3) return null;
            try {
                int rank = Integer.parseInt(parts[1]);
                if (rank < 1 || rank > 10) return "";
                var entries = new ArrayList<>(ranked.entrySet());
                if (entries.size() < rank) return "";
                var entry = entries.get(rank - 1);
                return switch (parts[2]) {
                    case "name" -> name(Bukkit.getOfflinePlayer(entry.getKey()));
                    case "value" -> String.format(Locale.US, "%.2f", entry.getValue());
                    case "formatted" -> money(entry.getValue());
                    default -> null;
                };
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
