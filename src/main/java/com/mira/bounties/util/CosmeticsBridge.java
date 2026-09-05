package com.mira.bounties.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CosmeticsBridge {
    private CosmeticsBridge() { }

    public static void play(Player player, String eventId) {
        if (player == null || eventId == null) return;
        Plugin cosmetics = Bukkit.getPluginManager().getPlugin("MiraCosmetics");
        if (cosmetics == null || !cosmetics.isEnabled()) return;
        try {
            cosmetics.getClass().getMethod("playEvent", Player.class, String.class, Location.class)
                    .invoke(cosmetics, player, eventId, player.getLocation());
        } catch (NoSuchMethodException ignored) {
            try {
                cosmetics.getClass().getMethod("playVisualEvent", Player.class, String.class, Location.class)
                        .invoke(cosmetics, player, eventId, player.getLocation());
            } catch (ReflectiveOperationException ignoredToo) { }
        } catch (ReflectiveOperationException ignored) { }
    }

    public static void playNearby(Location location, String eventId, double radius) {
        if (location == null || location.getWorld() == null) return;
        double radiusSquared = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSquared) play(player, eventId);
        }
    }

    public static void playGlobal(String eventId, Location source) {
        if (eventId == null) return;
        Plugin cosmetics = Bukkit.getPluginManager().getPlugin("MiraCosmetics");
        if (cosmetics == null || !cosmetics.isEnabled()) return;
        try {
            cosmetics.getClass().getMethod("playAudioEventGlobal", String.class, Location.class)
                    .invoke(cosmetics, eventId, source);
        } catch (ReflectiveOperationException ignored) { }
    }
}
