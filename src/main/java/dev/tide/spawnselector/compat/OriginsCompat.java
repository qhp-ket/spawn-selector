package dev.tide.spawnselector.compat;

import dev.tide.spawnselector.SpawnSelector;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;

/** No Origins class appears in a signature, so removal never triggers a linkage error. */
public final class OriginsCompat {
    public enum Status { ABSENT, WAITING, READY, BROKEN }
    private static Method get, all, getPowerContainer, getPowers, getFactory, isReady;
    private static Class<?> callback;
    private static Status status = Status.ABSENT;
    private static long lastInvocationFailureNanos;
    private static final Map<UUID, Long> nextInvocationRetryNanos = new HashMap<>();
    private static final Map<UUID, Integer> invocationFailures = new HashMap<>();
    private static final java.util.Set<UUID> bypass = new java.util.HashSet<>();
    public static void forget(UUID id) { nextInvocationRetryNanos.remove(id); invocationFailures.remove(id); bypass.remove(id); }
    public static Status status() { return status; }
    public static boolean ready(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (bypass.contains(playerId)) return true;
        if (!ModList.get().isLoaded("origins")) { nextInvocationRetryNanos.remove(playerId); status = Status.ABSENT; return true; }
        if (status == Status.BROKEN) return true;
        if (get == null && !initialize()) return status != Status.WAITING;
        long now = System.nanoTime();
        Long retryAt = nextInvocationRetryNanos.get(playerId);
        if (retryAt != null && now < retryAt) { status = Status.WAITING; return false; }
        if (retryAt != null) nextInvocationRetryNanos.remove(playerId);
        try {
            Object container = ((LazyOptional<?>) get.invoke(null, player)).resolve().orElse(null);
            if (container == null || !Boolean.TRUE.equals(all.invoke(container))) { status = Status.WAITING; return false; }
            Object powers = ((LazyOptional<?>) getPowerContainer.invoke(null, player)).resolve().orElse(null);
            if (powers == null) { status = Status.WAITING; return false; }
            for (Object value : (java.util.List<?>) getPowers.invoke(powers)) {
                Holder<?> holder = (Holder<?>) value;
                if (!holder.isBound()) { status = Status.WAITING; return false; }
                Object power = holder.value(), factory = getFactory.invoke(power);
                if (callback.isInstance(factory) && !Boolean.TRUE.equals(isReady.invoke(factory, power, player, false))) { status = Status.WAITING; return false; }
            }
            nextInvocationRetryNanos.remove(playerId);
            invocationFailures.remove(playerId);
            status = Status.READY;
            return true;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (isCompatibilityFailure(cause)) {
                markBroken("Origins API invocation is incompatible; continuing as if Origins were absent.", ex);
                return true;
            }
            status = Status.WAITING;
            nextInvocationRetryNanos.put(playerId, System.nanoTime() + 2_000_000_000L);
            logInvocationFailure(ex);
            return failSoft(playerId, ex);
        } catch (ClassCastException | IllegalArgumentException ex) {
            markBroken("Origins API invocation shape is incompatible; continuing as if Origins were absent.", ex);
            return true;
        } catch (ReflectiveOperationException ex) {
            status = Status.WAITING;
            nextInvocationRetryNanos.put(playerId, System.nanoTime() + 2_000_000_000L);
            logInvocationFailure(ex);
            return failSoft(playerId, ex);
        } catch (LinkageError ex) {
            markBroken("Origins API linkage failed after initialization; continuing as if Origins were absent.", ex);
            return true;
        }
    }
    private static boolean failSoft(UUID player, Throwable ex) {
        if (invocationFailures.merge(player, 1, Integer::sum) < 3) return false;
        bypass.add(player); nextInvocationRetryNanos.remove(player);
        SpawnSelector.LOG.warn("Origins API repeatedly failed for {}; bypassing compatibility for this player's session", player, ex);
        return true;
    }
    private static boolean initialize() {
        try {
            Class<?> api = Class.forName("io.github.edwinmindcraft.origins.api.capabilities.IOriginContainer");
            Method nextGet = api.getMethod("get", Entity.class);
            Method nextAll = api.getMethod("hasAllOrigins");
            Class<?> powers = Class.forName("io.github.edwinmindcraft.apoli.api.component.IPowerContainer");
            Class<?> configured = Class.forName("io.github.edwinmindcraft.apoli.api.power.configuration.ConfiguredPower");
            Class<?> nextCallback = Class.forName("io.github.edwinmindcraft.origins.api.origin.IOriginCallbackPower");
            Method nextPowerContainer = powers.getMethod("get", Entity.class);
            Method nextPowers = powers.getMethod("getPowers");
            Method nextFactory = configured.getMethod("getFactory");
            Method nextReady = nextCallback.getMethod("isReady", configured, Entity.class, boolean.class);
            get=nextGet; all=nextAll; callback=nextCallback; getPowerContainer=nextPowerContainer;
            getPowers=nextPowers; getFactory=nextFactory; isReady=nextReady;
            status = Status.WAITING;
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            markBroken("Origins compatibility is unavailable; continuing as if Origins were absent.", ex);
            return false;
        }
    }
    private static void markBroken(String message, Throwable ex) {
        if (status != Status.BROKEN) SpawnSelector.LOG.warn(message, ex);
        status = Status.BROKEN;
    }
    private static boolean isCompatibilityFailure(Throwable cause) {
        return cause instanceof LinkageError
            || cause instanceof ClassCastException
            || cause instanceof IllegalArgumentException;
    }
    private static void logInvocationFailure(Throwable ex) {
        long now = System.nanoTime();
        if (now - lastInvocationFailureNanos < 60_000_000_000L) return;
        lastInvocationFailureNanos = now;
        SpawnSelector.LOG.warn("Origins readiness check failed temporarily; will retry.", ex);
    }
}
