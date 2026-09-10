package com.ethan.hungerguard;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HungerGuard (hard-floor version)
 *
 * The previous "detect a fast drop" approach failed because whatever is
 * causing the bug drains hunger to 0 faster than a single tick - by the time
 * this mod's tick handler runs, food is already 0, so there's no "before"
 * value to compare against and detect a drop from.
 *
 * This version sidesteps that entirely: it just enforces a hard minimum
 * hunger floor every tick. If food is ever below the floor, it's
 * immediately restored. This can't be "raced" the way a drop-detector can,
 * since it doesn't care HOW food got low, only that it's currently too low.
 */
@EventBusSubscriber(modid = HungerGuard.MODID, bus = EventBusSubscriber.Bus.GAME)
public class HungerWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("HungerGuard");

    // Hard floor: food is never allowed to sit below this value.
    private static final int SAFE_MINIMUM_FOOD = 6;

    // Also cap how much can be lost in a single tick, in case the bug is a
    // large-but-not-instant drop (e.g. drops from 15 to 2 in one tick, which
    // is still above SAFE_MINIMUM_FOOD but clearly not normal).
    private static final int MAX_LOSS_PER_TICK = 2;

    private static long globalTick = 0;
    private static long lastHeartbeatTick = 0;
    private static final int HEARTBEAT_INTERVAL_TICKS = 100; // 5 seconds

    private static final Map<UUID, PlayerState> STATE = new ConcurrentHashMap<>();

    private static class PlayerState {
        int lastFood = -1;
        long lastMessageTick = -1000;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        globalTick++;

        PlayerState state = STATE.computeIfAbsent(player.getUUID(), k -> new PlayerState());

        FoodData food = player.getFoodData();
        int currentFood = food.getFoodLevel();

        if (globalTick - lastHeartbeatTick >= HEARTBEAT_INTERVAL_TICKS) {
            lastHeartbeatTick = globalTick;
            LOGGER.info("[HungerGuard] Heartbeat - tick {} - player {} food={}",
                globalTick, player.getGameProfile().getName(), currentFood);
        }

        boolean corrected = false;
        int correctedFood = currentFood;

        // Rule 1: hard floor. Never let food sit below the safe minimum.
        if (currentFood < SAFE_MINIMUM_FOOD) {
            correctedFood = SAFE_MINIMUM_FOOD;
            corrected = true;
        }

        // Rule 2: if we DO have a previous tick's value and it dropped by
        // more than MAX_LOSS_PER_TICK in one go, cap the loss even if the
        // result is still above SAFE_MINIMUM_FOOD.
        if (state.lastFood >= 0) {
            int delta = state.lastFood - currentFood;
            if (delta > MAX_LOSS_PER_TICK) {
                int cappedFood = state.lastFood - MAX_LOSS_PER_TICK;
                if (cappedFood > correctedFood) {
                    correctedFood = cappedFood;
                }
                corrected = true;
            }
        }

        if (corrected && correctedFood != currentFood) {
            LOGGER.info("[HungerGuard] Correcting food: {} -> {} (was going to be {})",
                state.lastFood, correctedFood, currentFood);
            food.setFoodLevel(correctedFood);

            if (globalTick - state.lastMessageTick > 40) {
                state.lastMessageTick = globalTick;
                player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "[HungerGuard] Corrected abnormal hunger in '" +
                        player.level().dimension().location() + "' (set to " + correctedFood + ")."
                    )
                );
            }
        }

        state.lastFood = correctedFood;
    }
}
