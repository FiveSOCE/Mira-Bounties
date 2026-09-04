package com.mira.bounties.api;

import java.util.Map;
import java.util.UUID;

public interface MiraBountiesApi {
    double bounty(UUID player);
    Map<UUID, Double> top(int limit);
    boolean hasBounty(UUID player);

    int claims(UUID hunter);
    double claimedTotal(UUID hunter);
    Map<UUID, Double> topHunters(int limit);
    double totalPosted();
    double totalClaimed();
}
