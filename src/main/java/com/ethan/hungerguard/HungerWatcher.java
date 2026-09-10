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

@EventBusSubscriber(modid = HungerGuard.MODID, bus = EventBusSubscriber.Bus.GAME)
public class HungerWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("HungerGuard");

    private static final int REGEN_INTERVAL_TICKS = 40; // 2 seconds per point
    private static final int MAX_BANKED_ALLOWANCE = 2;   // small burst tolerance
    private static final int HEARTBEAT_INTERVAL_TICKS = 100; // 5 seconds

    private static long globalTick = 0;
    private static long lastHeartbeatTick = 0;
    private static boolean loggedFirstTick = false;

    private static final Map<UUID, PlayerState> STATE = new ConcurrentHashMap<>();

    private static class PlayerState {
        int lastFood = -1;
        int allowance = MAX_BANKED_ALLOWANCE;
        long lastRegenTick = 0;
        long lastMessageTick = -1000;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!loggedFirstTick) {
            loggedFirstTick = true;
            LOGGER.info("[HungerGuard] onPlayerTick handler IS firing. First event received for entity: {}", event.getEntity());
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        globalTick++;

        PlayerState state = STATE.computeIfAbsent(player.getUUID(), k -> {
            PlayerState s = new PlayerState();
            s.lastRegenTick = globalTick;
            LOGGER.info("[HungerGuard] Now tracking player {}", player.getGameProfile().getName());
            return s;
        });

        FoodData food = player.getFoodData();
        int currentFood = food.getFoodLevel();

        if (globalTick - lastHeartbeatTick >= HEARTBEAT_INTERVAL_TICKS) {
            lastHeartbeatTick = globalTick;
            LOGGER.info("[HungerGuard] Heartbeat - tick {} - player {} food={} allowance={}",
                globalTick, player.getGameProfile().getName(), currentFood, state.allowance);
        }

        if (state.lastFood < 0) {
            state.lastFood = currentFood;
            return;
        }

        while (globalTick - state.lastRegenTick >= REGEN_INTERVAL_TICKS) {
            if (state.allowance < MAX_BANKED_ALLOWANCE) {
                state.allowance++;
            }
            state.lastRegenTick += REGEN_INTERVAL_TICKS;
        }

        int delta = state.lastFood - currentFood;

        if (delta <= 0) {
            state.lastFood = currentFood;
            return;
        }

        LOGGER.info("[HungerGuard] Detected food drop: {} -> {} (delta={}), allowance={}",
            state.lastFood, currentFood, delta, state.allowance);

        int permitted = Math.min(delta, state.allowance);
        state.allowance -= permitted;

        int correctedFood = state.lastFood - permitted;

        if (correctedFood != currentFood) {
            LOGGER.info("[HungerGuard] BLOCKING excess drop: setting food back to {} (was {})", correctedFood, currentFood);
            food.setFoodLevel(correctedFood);

            if (globalTick - state.lastMessageTick > 40) {
                state.lastMessageTick = globalTick;
                player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                        "[HungerGuard] Capping abnormal hunger drain (blocked " +
                        (delta - permitted) + " extra point(s) this tick) in '" +
                        player.level().dimension().location() + "'."
                    )
                );
            }
        }

        state.lastFood = correctedFood;
    }
}
