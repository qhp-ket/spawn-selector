package dev.tide.spawnselector;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

public final class SelectionEligibility {
    private SelectionEligibility() {}
    /** Shared by pre-placement routing and login: never strand an exempt player in holding. */
    public static boolean shouldOffer(ServerPlayer player) {
        PlayerSelections data=PlayerSelections.get(player.server);
        return !data.chosen(player.getUUID()) && (data.pending(player.getUUID())
            || player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME))<=200);
    }
}
