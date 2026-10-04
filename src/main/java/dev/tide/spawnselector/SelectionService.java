package dev.tide.spawnselector;

import dev.tide.spawnselector.compat.OriginsCompat;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class SelectionService {
    private enum Phase { WAITING, PICKING, LOCATING, SEARCHING, VANILLA, REVEAL }
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final class Session {
        Phase phase = Phase.WAITING;
        long nextAttempt;
        boolean probed;
        boolean recovery;
        Vec3 frozen;
        ResourceLocation dimension;
        Map<String, SpawnEntry> offered = Map.of();
        SpawnEntry selected;
        SafeSpawnFinder finder;
        StructureInstanceId instance;
        StructureInstanceLocator startReader;
        net.minecraft.world.level.levelgen.structure.BoundingBox bounds;
        Map<String, StructureInstanceId> offeredInstances = Map.of();
        TeleportLifecycle revealTickets;
        String revealToken = "";
        String progressMessage = "";
        int lastProgressCount;
        long lastProgressTick;
        long lastActionTick = -1000L;
        long revealDeadline;
        Component completionMessage=Component.empty();
        Session(ServerPlayer p) {}
    }
    private static boolean protectedPlayer(Entity e) {
        if (!(e instanceof ServerPlayer p)) return false;
        return sessions.containsKey(p.getUUID());
    }
    private static Packets.View view(int state, Component message, List<Packets.Card> cards) {
        return new Packets.View(state, message, cards, SpawnUiConfig.current());
    }
    @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (PlayerSelections.get(p.server).awaitingReveal(p.getUUID())) {
            Session session = new Session(p);
            sessions.put(p.getUUID(), session);
            Packets.send(p, view(4,Component.empty(),List.of()));
            awaitReveal(p, session, Component.translatable("spawnselector.message.restored"));
        } else if (SelectionEligibility.shouldOffer(p)) {
            sessions.put(p.getUUID(), new Session(p)); PlayerSelections.get(p.server).pending(p.getUUID(), true);
            // Arm the client before Origins finishes. This closes the render gap between
            // Origins' screen and the first selector packet.
            Packets.send(p, view(4,Component.empty(),List.of()));
        }
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        Session s = sessions.remove(event.getEntity().getUUID());
        if (s != null) releasePreparation((ServerPlayer)event.getEntity(), s);
        OriginsCompat.forget(event.getEntity().getUUID());
    }
    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        sessions.forEach((id,s) -> {
            if (s.finder != null) s.finder.close();
            if (s.startReader != null) s.startReader.close();
            if (s.revealTickets != null) s.revealTickets.close();
            PlayerSelections.get(event.getServer()).pool.release(event.getServer(), id);
        });
        sessions.clear();
    }
    @SubscribeEvent public void died(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Session s = sessions.get(p.getUUID());
            if (s != null) {
                if(PlayerSelections.get(p.server).chosen(p.getUUID())) {
                    close(p, Component.empty()); return;
                }
                releasePreparation(p,s);
                s.phase=Phase.WAITING; s.probed=false; s.finder=null; s.selected=null;
                PlayerSelections.get(p.server).pending(p.getUUID(), true);
                Packets.send(p, view(3,Component.empty(),List.of()));
                Packets.send(p, view(4,Component.empty(),List.of()));
            }
        }
    }
    @SubscribeEvent public void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer p)) return;
        Session s = sessions.get(p.getUUID()); if (s == null) return;
        try {
            if (!p.isAlive() || p.isRemoved()) return;
            long now = p.serverLevel().getGameTime();
            if (SpawnSelectorDimensions.isHolding(p.serverLevel())) {
                if (p.position().distanceToSqr(SpawnSelectorDimensions.HOLDING_VEC)>.001)
                    p.connection.teleport(SpawnSelectorDimensions.HOLDING_VEC.x,SpawnSelectorDimensions.HOLDING_VEC.y,
                        SpawnSelectorDimensions.HOLDING_VEC.z,p.getYRot(),p.getXRot());
                p.setDeltaMovement(Vec3.ZERO); p.fallDistance=0;
            }
            if (s.phase==Phase.VANILLA) { finishVanilla(p,s); return; }
            if (s.phase == Phase.WAITING) {
                if (!s.probed && OriginsCompat.ready(p)) {
                    s.probed=true; s.frozen=p.position(); s.dimension=p.serverLevel().dimension().location();
                    Packets.send(p, view(0,Component.empty(),List.of())); offer(p,s,Component.translatable("spawnselector.message.choose"));
                }
                return;
            }
            if (!p.serverLevel().dimension().location().equals(s.dimension) || p.position().distanceToSqr(s.frozen)>256) {
                s.dimension=p.serverLevel().dimension().location(); s.frozen=p.position();
            }
            if (p.isPassenger()) p.stopRiding();
            if (p.position().distanceToSqr(s.frozen)>.0001) p.connection.teleport(s.frozen.x,s.frozen.y,s.frozen.z,p.getYRot(),p.getXRot());
            p.setDeltaMovement(Vec3.ZERO); p.fallDistance=0;
            if(s.phase==Phase.REVEAL) {
                if(now>=s.revealDeadline) {
                    s.revealDeadline=now+400;
                    SpawnSelector.LOG.warn("Player {} is still waiting for destination chunk acknowledgement in {}", p.getUUID(), s.dimension);
                    sendReveal(p,s);
                }
                return;
            }
            if (s.phase==Phase.LOCATING && now>=s.nextAttempt) {
                var level=Locators.level(p.server,s.selected);
                if (level==null || !OriginsCompat.ready(p)) { offer(p,s,Component.translatable("spawnselector.message.origin_changed")); return; }
                if (s.instance == null) {
                    if (!SpawnSelectorManager.get(p.server).discover(p.server,s.selected)) { s.nextAttempt=now+1; return; }
                    showInstances(p,s);
                    return;
                }
                if (s.startReader == null || !s.startReader.done()) { s.nextAttempt=now+1; return; }
                try { s.bounds=s.startReader.bounds(); }
                finally { s.startReader.close(); s.startReader=null; }
                if (s.bounds == null) { offer(p,s,Component.translatable("spawnselector.message.instance_invalid")); return; }
                s.finder=new SafeSpawnFinder(level,s.bounds,s.selected,p);
                if (s.finder.exhausted()) {
                    Component reason=s.finder.failureReason();
                    offer(p,s,reason.equals(Component.empty())?Component.translatable("spawnselector.message.no_safe"):reason);
                    return;
                }
                s.phase=Phase.SEARCHING;
                s.progressMessage="";
                int firstBatch=Math.min(5,s.finder.maximum());
                s.lastProgressCount=firstBatch;
                Packets.send(p,view(2,Component.translatable("spawnselector.message.safe_progress", firstBatch, s.finder.maximum()),List.of()));
            } else if (s.phase==Phase.SEARCHING) {
                BlockPos found=s.finder.step(p);
                if (found!=null) complete(p,s,found);
                else if (s.finder.exhausted()) {
                    Component reason=s.finder.failureReason();
                    offer(p,s,Component.translatable("spawnselector.message.retry_location", reason.equals(Component.empty())?Component.translatable("spawnselector.message.no_safe"):reason));
                }
                else {
                    int count=Math.max(s.lastProgressCount,s.finder.progress());
                    String progress=count+"/"+s.finder.maximum();
                    if (count>s.lastProgressCount) {
                        s.progressMessage=progress;
                        s.lastProgressCount=count;
                        Packets.send(p,view(2,Component.translatable("spawnselector.message.safe_progress", count, s.finder.maximum()),List.of()));
                    }
                }
            }
        } catch (Exception | LinkageError ex) {
            SpawnSelector.LOG.error("Spawn selection failed for {}",p.getUUID(),ex);
            abortSession(p,s,Component.translatable("spawnselector.message.flow_failed"));
        }
    }
    static void action(ServerPlayer p,String id) {
        try { actionChecked(p,id); }
        catch(RuntimeException | LinkageError ex) {
            SpawnSelector.LOG.error("Spawn selection action failed for {}",p.getUUID(),ex);
            Session session=sessions.get(p.getUUID());
            if(session!=null) abortSession(p,session,Component.translatable("spawnselector.message.action_failed"));
        }
    }
    private static void actionChecked(ServerPlayer p,String id) {
        Session s=sessions.get(p.getUUID()); if (s==null) return;
        if(id.startsWith("@world_ready:")) {
            if(s.phase==Phase.REVEAL && p.isAlive() && id.equals("@world_ready:"+s.revealToken)) close(p,s.completionMessage);
            return;
        }
        if (PlayerSelections.get(p.server).chosen(p.getUUID()) || !p.isAlive()) return;
        long now=p.serverLevel().getGameTime();
        if(id.equals("@vanilla")) {
            if(s.recovery && s.phase==Phase.PICKING && now-s.lastActionTick>=3) {
                s.lastActionTick=now; beginVanilla(p);
            }
            return;
        }
        if (id.equals("@skip")) {
            if (s.phase==Phase.PICKING && now-s.lastActionTick>=3 && SpawnUiConfig.current().allowPermanentSkip()) {
                s.lastActionTick=now; skip(p);
            }
            return;
        }
        if (id.equals("@back") && (s.phase==Phase.LOCATING || s.phase==Phase.SEARCHING || !s.offeredInstances.isEmpty())) { s.selected=null; offer(p,s,Component.translatable("spawnselector.message.choose_other")); return; }
        if (id.equals("@ready")) {
            if (s.phase!=Phase.WAITING || !s.probed) return;
            if (!OriginsCompat.ready(p)) { s.probed=false; return; }
            s.frozen=p.position(); s.dimension=p.serverLevel().dimension().location(); offer(p,s,Component.translatable("spawnselector.message.choose")); return;
        }
        if (s.phase!=Phase.PICKING) return;
        // Normal selection packets are client-controlled. Ignore quick repeats before any
        // availability scan and drop unknown IDs without rebuilding the offered list.
        if (now-s.lastActionTick < 3) return;
        s.lastActionTick=now;
        if (!OriginsCompat.ready(p)) { s.phase=Phase.WAITING; s.probed=false; Packets.send(p,view(3,Component.empty(),List.of())); return; }
        if (now<s.nextAttempt) return;
        SpawnEntry selected=s.offered.get(id);
        if (selected==null) return;
        boolean stillAvailable;
        try { stillAvailable=SpawnEntries.available(p.server).contains(selected); }
        catch (RuntimeException | LinkageError ex) {
            SpawnSelector.LOG.error("Could not revalidate spawn entry {} for {}", selected.id(), p.getUUID(), ex);
            abortSession(p,s,Component.translatable("spawnselector.message.entry_check_failed"));
            return;
        }
        if (!stillAvailable) { s.nextAttempt=now+20; offer(p,s,Component.translatable("spawnselector.message.options_changed")); return; }
        StructureInstanceId instance=s.offeredInstances.get(id);
        if (instance != null) {
            if (!PlayerSelections.get(p.server).pool.claim(p.server,selected,instance,p.getUUID())) {
                showInstances(p,s); return;
            }
            s.instance=instance;
            try { s.startReader=new StructureInstanceLocator(Locators.level(p.server,selected),instance); }
            catch(RuntimeException | LinkageError ex) {
                SpawnSelector.LOG.error("Could not prepare {} for {}", instance, p.getUUID(), ex);
                abortSession(p,s,Component.translatable("spawnselector.message.structure_failed")); return;
            }
            s.offeredInstances=Map.of();
        }
        s.selected=selected; s.phase=Phase.LOCATING; s.nextAttempt=now+10; s.progressMessage="";
        Packets.send(p,view(2,Component.translatable("spawnselector.message.locating", selected.name()),List.of()));
    }
    private static void releasePreparation(ServerPlayer p,Session s) {
        if(s.finder!=null) { s.finder.close(); s.finder=null; }
        if(s.startReader!=null) { s.startReader.close(); s.startReader=null; }
        if(s.revealTickets!=null) { s.revealTickets.close(); s.revealTickets=null; }
        PlayerSelections.get(p.server).pool.release(p.server,p.getUUID());
        s.instance=null;
    }
    private static void sendReveal(ServerPlayer p, Session s) {
        Packets.send(p,new Packets.View(6,Component.translatable("spawnselector.message.entering"),List.of(),SpawnUiConfig.current(),
            s.dimension.toString(),p.blockPosition().asLong(),s.revealToken));
    }
    private static void showInstances(ServerPlayer p,Session s) {
        var pool=PlayerSelections.get(p.server).pool;
        var candidates=pool.available(p.server,s.selected);
        if(candidates.isEmpty()) { offer(p,s,Component.translatable("spawnselector.message.no_instances")); return; }
        LinkedHashMap<String,SpawnEntry> offered=new LinkedHashMap<>();
        LinkedHashMap<String,StructureInstanceId> instances=new LinkedHashMap<>();
        List<Packets.Card> cards=new ArrayList<>();
        int number=0;
        for(var candidate:candidates) {
            String token="@instance:"+UUID.randomUUID();
            offered.put(token,s.selected); instances.put(token,candidate.id);
            Component capacity=candidate.capacity==-1?Component.translatable("spawnselector.instance.unlimited"):Component.translatable("spawnselector.instance.capacity", candidate.claims.size(), candidate.capacity);
            cards.add(new Packets.Card(token,Component.translatable("spawnselector.instance.name", s.selected.name(), ++number),
                Component.translatable("spawnselector.instance.description", candidate.id.chunkX()*16+8, candidate.id.chunkZ()*16+8, capacity),
                s.selected.icon().toString(),s.selected.preview()==null?"":s.selected.preview().toString()));
        }
        s.offered=Map.copyOf(offered); s.offeredInstances=Map.copyOf(instances); s.phase=Phase.PICKING;
        Packets.send(p,view(7,Component.translatable("spawnselector.message.choose_instance"),cards));
    }
    private static void offer(ServerPlayer p,Session s,Component message) {
        s.recovery=false;
        releasePreparation(p,s); s.offeredInstances=Map.of(); s.phase=Phase.PICKING;
        s.nextAttempt=p.serverLevel().getGameTime()+(s.selected==null?0:20);
        List<SpawnEntry> available;
        try { available=SpawnEntries.available(p.server); }
        catch (RuntimeException | LinkageError ex) {
            SpawnSelector.LOG.error("Could not build spawn entry offer for {}", p.getUUID(), ex);
            abortSession(p,s,Component.translatable("spawnselector.message.config_failed"));
            return;
        }
        LinkedHashMap<String,SpawnEntry> offered=new LinkedHashMap<>(); available.forEach(e->offered.put(e.id().toString(),e));
        if (offered.isEmpty() && !SpawnUiConfig.current().allowPermanentSkip()) {
            abortSession(p,s,Component.translatable("spawnselector.message.no_options"));
            return;
        }
        s.offered=Map.copyOf(offered); Packets.send(p,view(1,message,offered.values().stream().map(Packets.Card::of).toList()));
    }
    /** Failure path that deliberately avoids registry/structure availability queries. */
    private static void abortSession(ServerPlayer p, Session s, Component message) {
        if(PlayerSelections.get(p.server).chosen(p.getUUID())) {
            // Arrival is durable. Do not open a choice page whose actions would be
            // rejected for a completed player; resume destination acknowledgement.
            s.phase=Phase.REVEAL; s.frozen=p.position(); s.dimension=p.serverLevel().dimension().location();
            s.revealToken=UUID.randomUUID().toString(); s.revealDeadline=p.serverLevel().getGameTime()+400;
            s.completionMessage=message; sendReveal(p,s); return;
        }
        // Keep online state and persisted pending state aligned. This recovery page performs
        // no registry query and remains protected even when /reset was used outside holding.
        releasePreparation(p,s); s.offeredInstances=Map.of();
        sessions.put(p.getUUID(),s);
        s.phase=Phase.PICKING; s.offered=Map.of();
        s.frozen=p.position(); s.dimension=p.serverLevel().dimension().location();
        s.recovery=true;
        PlayerSelections.get(p.server).pending(p.getUUID(),true);
        Packets.send(p,view(5,message,List.of()));
    }
    private static void complete(ServerPlayer p,Session s,BlockPos pos) {
        var target=Locators.level(p.server,s.selected);
        if (target==null || !SpawnEntries.available(p.server).contains(s.selected) || !OriginsCompat.ready(p) || !s.finder.recheck(p,pos)) { offer(p,s,Component.translatable("spawnselector.message.spawn_changed")); return; }
        float yaw=(float)(Math.toDegrees(Math.atan2(s.bounds.getCenter().getZ()-(pos.getZ()+.5),
            s.bounds.getCenter().getX()-(pos.getX()+.5)))-90.0);
        PlayerSelections.get(p.server).pool.ready(p.server,s.instance,pos);
        p.stopRiding(); boolean moved=p.teleportTo(target,pos.getX()+.5,pos.getY(),pos.getZ()+.5,Set.of(),yaw,0);
        if (!moved || p.serverLevel()!=target || p.position().distanceToSqr(Vec3.atBottomCenterOf(pos))>1) { offer(p,s,Component.translatable("spawnselector.message.teleport_cancelled")); return; }
        // Dimension-change hooks may alter dimensions or the actual box. Commit
        // the permanent claim only after validating the arrived player's body too.
        if(!s.finder.recheck(p,pos)) { offer(p,s,Component.translatable("spawnselector.message.body_changed")); return; }
        p.setDeltaMovement(Vec3.ZERO); p.fallDistance=0; p.setRespawnPosition(target.dimension(),pos,p.getYRot(),true,false);
        PlayerSelections.get(p.server).complete(p,s.selected.id().toString());
        boolean respawnSet=target.dimension().equals(p.getRespawnDimension()) && pos.equals(p.getRespawnPosition()) && p.isRespawnForced();
        awaitReveal(p,s,respawnSet?Component.translatable("spawnselector.message.structure_complete", s.selected.name()):Component.translatable("spawnselector.message.respawn_intercepted", s.selected.name()));
    }
    private static void skip(ServerPlayer p) {
        if (PlayerSelections.get(p.server).chosen(p.getUUID())) return;
        if (!SpawnUiConfig.current().allowPermanentSkip()) { p.sendSystemMessage(Component.translatable("spawnselector.message.skip_disabled")); return; }
        beginVanilla(p);
    }
    private static void beginVanilla(ServerPlayer p) {
        p.stopRiding();
        Session s=sessions.computeIfAbsent(p.getUUID(),id->new Session(p));
        releasePreparation(p,s); s.offeredInstances=Map.of();
        s.phase=Phase.VANILLA;
        Packets.send(p,view(2,Component.translatable("spawnselector.message.vanilla_preparing"),List.of()));
    }
    private static void finishVanilla(ServerPlayer p,Session s) {
        BlockPos pos;
        net.minecraft.server.level.ServerLevel level;
        try {
            var spawn=DeferredVanillaSpawn.preparePlayerSpawn(p);
            level=spawn.level();
            pos=spawn.position();
            boolean moved=p.teleportTo(level,pos.getX()+.5,pos.getY(),pos.getZ()+.5,Set.of(),p.getYRot(),0);
            if(!moved || p.serverLevel()!=level || p.position().distanceToSqr(Vec3.atBottomCenterOf(pos))>1)
                throw new IllegalStateException("Vanilla spawn teleport was rejected");
        } catch(Exception | LinkageError ex) {
            SpawnSelector.LOG.error("Could not enter vanilla spawn",ex);
            abortSession(p,s,Component.translatable("spawnselector.message.vanilla_failed"));
            return;
        }
        try {
            p.setDeltaMovement(Vec3.ZERO); p.fallDistance=0;
            PlayerSelections.get(p.server).complete(p,"spawnselector:vanilla");
            awaitReveal(p,s,Component.translatable("spawnselector.message.vanilla_complete"));
        } catch(Exception | LinkageError ex) {
            SpawnSelector.LOG.error("Vanilla spawn entered but finalization failed",ex);
            close(p,Component.translatable("spawnselector.message.vanilla_reveal_failed"));
        }
    }
    private static void awaitReveal(ServerPlayer p,Session s,Component completionMessage) {
        if (s.revealTickets != null) s.revealTickets.close();
        s.revealTickets=new TeleportLifecycle(p);
        s.revealToken=UUID.randomUUID().toString();
        if(s.finder!=null) { s.finder.close(); s.finder=null; }
        s.phase=Phase.REVEAL;
        s.frozen=p.position(); s.dimension=p.serverLevel().dimension().location();
        s.revealDeadline=p.serverLevel().getGameTime()+400;
        s.completionMessage=completionMessage;
        sendReveal(p,s);
    }
    private static void close(ServerPlayer p,Component message) {
        Session previous=sessions.remove(p.getUUID()); if (previous!=null) releasePreparation(p,previous);
        PlayerSelections.get(p.server).revealed(p.getUUID());
        PlayerSelections.get(p.server).pending(p.getUUID(),false); Packets.send(p,view(3,Component.empty(),List.of()));
        if (!message.equals(Component.empty())) p.sendSystemMessage(message);
    }
    @SubscribeEvent public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("spawnselector").executes(ctx->{
            ServerPlayer p=ctx.getSource().getPlayerOrException();
            if (PlayerSelections.get(p.server).chosen(p.getUUID())) { p.sendSystemMessage(Component.translatable("spawnselector.command.already_complete")); return 0; }
            if (!sessions.containsKey(p.getUUID())) sessions.put(p.getUUID(),new Session(p)); PlayerSelections.get(p.server).pending(p.getUUID(),true);
            p.sendSystemMessage(Component.translatable("spawnselector.command.queued")); return 1;
        }).then(Commands.literal("reset").requires(source->source.hasPermission(2)).executes(ctx->{
            ServerPlayer p=ctx.getSource().getPlayerOrException(); Session previous=sessions.remove(p.getUUID()); if(previous!=null) releasePreparation(p,previous);
            Packets.send(p,view(3,Component.empty(),List.of())); PlayerSelections data=PlayerSelections.get(p.server); data.reset(p.getUUID()); data.pending(p.getUUID(),true); sessions.put(p.getUUID(),new Session(p));
            p.sendSystemMessage(Component.translatable("spawnselector.command.reset")); return 1;
        })));
    }
    @SubscribeEvent public void attacked(LivingAttackEvent e){if(protectedPlayer(e.getEntity())||protectedPlayer(e.getSource().getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void hurt(LivingHurtEvent e){if(protectedPlayer(e.getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void damage(LivingDamageEvent e){if(protectedPlayer(e.getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void attack(AttackEntityEvent e){if(protectedPlayer(e.getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void interact(PlayerInteractEvent e){if(e.isCancelable()&&protectedPlayer(e.getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void breakBlock(BlockEvent.BreakEvent e){if(protectedPlayer(e.getPlayer()))e.setCanceled(true);}
    @SubscribeEvent public void place(BlockEvent.EntityPlaceEvent e){if(protectedPlayer(e.getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void pickup(EntityItemPickupEvent e){if(protectedPlayer(e.getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void toss(ItemTossEvent e){if(protectedPlayer(e.getPlayer()))e.setCanceled(true);}
    @SubscribeEvent public void use(LivingEntityUseItemEvent.Start e){if(protectedPlayer(e.getEntity()))e.setCanceled(true);}
    @SubscribeEvent public void xp(PlayerXpEvent.PickupXp e){if(protectedPlayer(e.getEntity()))e.setCanceled(true);}
}
