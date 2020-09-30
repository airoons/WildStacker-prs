package com.bgsoftware.wildstacker.objects;

import com.bgsoftware.wildstacker.api.enums.StackCheckResult;
import com.bgsoftware.wildstacker.api.enums.StackResult;
import com.bgsoftware.wildstacker.api.enums.UnstackResult;
import com.bgsoftware.wildstacker.api.events.SpawnerStackEvent;
import com.bgsoftware.wildstacker.api.events.SpawnerUnstackEvent;
import com.bgsoftware.wildstacker.api.objects.StackedObject;
import com.bgsoftware.wildstacker.api.objects.StackedSpawner;
import com.bgsoftware.wildstacker.database.Query;
import com.bgsoftware.wildstacker.utils.GeneralUtils;
import com.bgsoftware.wildstacker.utils.entity.EntityUtils;
import com.bgsoftware.wildstacker.utils.particles.ParticleWrapper;
import com.bgsoftware.wildstacker.utils.spawners.SyncedCreatureSpawner;
import com.bgsoftware.wildstacker.utils.threads.Executor;
import com.bgsoftware.wildstacker.utils.threads.StackService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class WStackedSpawner extends WStackedObject<CreatureSpawner> implements StackedSpawner {

    private final List<Inventory> linkedInventories = new ArrayList<>();

    private LivingEntity linkedEntity = null;

    public WStackedSpawner(CreatureSpawner creatureSpawner){
        this(creatureSpawner, 1);
    }

    public WStackedSpawner(CreatureSpawner creatureSpawner, int stackAmount){
        super(SyncedCreatureSpawner.of(creatureSpawner), stackAmount);
    }

    @Override
    public void setStackAmount(int stackAmount, boolean updateName) {
        super.setStackAmount(stackAmount, updateName);
        Query.SPAWNER_INSERT.insertParameters().setLocation(getLocation()).setObject(getStackAmount()).queue(getLocation());
    }

    @Override
    public CreatureSpawner getSpawner(){
        return object;
    }

    @Override
    public EntityType getSpawnedType(){
        if(object.getSpawnedType() == null)
            object.setSpawnedType(EntityType.PIG);
        return object.getSpawnedType();
    }

    @Override
    public Location getLocation() {
        return object.getLocation();
    }

    @Override
    public World getWorld() {
        return object.getWorld();
    }

    /*
     * StackedObject's methods
     */

    @Override
    public Chunk getChunk() {
        return getLocation().getChunk();
    }

    @Override
    public int getStackLimit() {
        int limit = plugin.getSettings().spawnersLimits.getOrDefault(getSpawnedType().name(), Integer.MAX_VALUE);
        return limit < 1 ? Integer.MAX_VALUE : limit;
    }

    @Override
    public int getMergeRadius() {
        int radius = plugin.getSettings().spawnersMergeRadius.getOrDefault(getSpawnedType().name(), 0);
        return radius < 1 ? 0 : radius;
    }

    @Override
    public boolean isBlacklisted() {
        return plugin.getSettings().blacklistedSpawners.contains(getSpawnedType().name());
    }

    @Override
    public boolean isWhitelisted() {
        return plugin.getSettings().whitelistedSpawners.isEmpty() ||
                plugin.getSettings().whitelistedSpawners.contains(getSpawnedType().name());
    }

    @Override
    public boolean isWorldDisabled() {
        return plugin.getSettings().spawnersDisabledWorlds.contains(object.getWorld().getName());
    }

    @Override
    public boolean isCached() {
        return plugin.getSettings().spawnersStackingEnabled && super.isCached();
    }

    @Override
    public void remove() {
        if(!Bukkit.isPrimaryThread()){
            Executor.sync(this::remove);
            return;
        }

        plugin.getSystemManager().removeStackObject(this);

        Query.SPAWNER_DELETE.insertParameters().setLocation(getLocation()).queue(getLocation());

        plugin.getProviders().deleteHologram(this);

        List<HumanEntity> viewers = new ArrayList<>();
        linkedInventories.forEach(i ->  viewers.addAll(i.getViewers()));

        viewers.forEach(HumanEntity::closeInventory);

        linkedInventories.clear();
    }

    @Override
    public void updateName() {
        if(!Bukkit.isPrimaryThread()){
            Executor.sync(this::updateName);
            return;
        }

        String customName = plugin.getSettings().hologramCustomName;

        if (customName.isEmpty())
            return;

        int amount = getStackAmount();

        if(amount <= 1) {
            plugin.getProviders().deleteHologram(this);
            return;
        }

        customName = customName
                .replace("{0}", Integer.toString(amount))
                .replace("{1}", EntityUtils.getFormattedType(getSpawnedType().name()))
                .replace("{2}", EntityUtils.getFormattedType(getSpawnedType().name()).toUpperCase());
        plugin.getProviders().changeLine(this, customName, !plugin.getSettings().floatingSpawnerNames);
    }

    @Override
    public StackCheckResult runStackCheck(StackedObject stackedObject) {
        if(!plugin.getSettings().spawnersStackingEnabled)
            return StackCheckResult.NOT_ENABLED;

        return super.runStackCheck(stackedObject);
    }

    private boolean canStackIntoNoLimit(StackedObject stackedObject){
        StackCheckResult stackCheckResult = runStackCheck(stackedObject);
        return stackCheckResult == StackCheckResult.SUCCESS || stackCheckResult == StackCheckResult.LIMIT_EXCEEDED;
    }

    @Override
    public Optional<CreatureSpawner> runStack() {
        if(getStackLimit() <= 1)
            return Optional.empty();

        Chunk chunk = getChunk();

        boolean chunkMerge = plugin.getSettings().chunkMergeSpawners;
        Location blockLocation = getLocation();

        Stream<StackedSpawner> spawnerStream;

        if (chunkMerge) {
            spawnerStream = plugin.getSystemManager().getStackedSpawners(chunk).stream();
        } else {
            int range = getMergeRadius();

            if(range <= 0)
                return Optional.empty();

            Location location = getLocation();

            int maxX = location.getBlockX() + range, maxY = location.getBlockY() + range, maxZ = location.getBlockZ() + range;
            int minX = location.getBlockX() - range, minY = location.getBlockY() - range, minZ = location.getBlockZ() - range;

            spawnerStream = plugin.getSystemManager().getStackedSpawners().stream()
                    .filter(stackedSpawner -> {
                        Location loc = stackedSpawner.getLocation();
                        return loc.getBlockX() >= minX && loc.getBlockX() <= maxX &&
                                loc.getBlockY() >= minY && loc.getBlockY() <= maxY &&
                                loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
                    });
        }

        Optional<StackedSpawner> spawnerOptional = GeneralUtils.getClosest(blockLocation, spawnerStream
                .filter(stackedSpawner -> runStackCheck(stackedSpawner) == StackCheckResult.SUCCESS));

        if (spawnerOptional.isPresent()) {
            StackedSpawner targetSpawner = spawnerOptional.get();

            StackResult stackResult = runStack(targetSpawner);

            if (stackResult == StackResult.SUCCESS) {
                return spawnerOptional.map(StackedSpawner::getSpawner);
            }
        }

        return Optional.empty();
    }

    @Override
    public StackResult runStack(StackedObject stackedObject) {
        if (!StackService.canStackFromThread())
            return StackResult.THREAD_CATCHER;

        if (runStackCheck(stackedObject) != StackCheckResult.SUCCESS)
            return StackResult.NOT_SIMILAR;

        StackedSpawner targetSpawner = (StackedSpawner) stackedObject;
        int newStackAmount = this.getStackAmount() + targetSpawner.getStackAmount();

        SpawnerStackEvent spawnerStackEvent = new SpawnerStackEvent(targetSpawner, this);
        Bukkit.getPluginManager().callEvent(spawnerStackEvent);

        if (spawnerStackEvent.isCancelled())
            return StackResult.EVENT_CANCELLED;

        targetSpawner.setStackAmount(newStackAmount, true);

        this.remove();

        if (plugin.getSettings().spawnersParticlesEnabled) {
            Location location = getLocation();
            for (ParticleWrapper particleWrapper : plugin.getSettings().spawnersParticles)
                particleWrapper.spawnParticle(location);
        }

        return StackResult.SUCCESS;
    }

    @Override
    public UnstackResult runUnstack(int amount, Entity entity) {
        SpawnerUnstackEvent spawnerUnstackEvent = new SpawnerUnstackEvent(this, entity, amount);
        Bukkit.getPluginManager().callEvent(spawnerUnstackEvent);

        if(spawnerUnstackEvent.isCancelled())
            return UnstackResult.EVENT_CANCELLED;

        int stackAmount = this.getStackAmount() - amount;

        setStackAmount(stackAmount, true);

        if(stackAmount < 1)
            remove();

        return UnstackResult.SUCCESS;
    }

    @Override
    public boolean isSimilar(StackedObject stackedObject) {
        return stackedObject instanceof StackedSpawner && getSpawnedType() == ((StackedSpawner) stackedObject).getSpawnedType();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof StackedSpawner ? getLocation().equals(((StackedSpawner) obj).getLocation()) : super.equals(obj);
    }

    @Override
    public String toString() {
        return String.format("StackedSpawner{location=%s,amount=%s,type=%s}", getLocation(), getStackAmount(), getSpawnedType());
    }

    /*
     * StackedSpawner's methods
     */

    @Override
    public LivingEntity getLinkedEntity(){
        if (linkedEntity != null && (linkedEntity.isDead() || !linkedEntity.isValid() || linkedEntity.getLocation().distanceSquared(getLocation()) > Math.pow(plugin.getSettings().linkedEntitiesMaxDistance, 2.0)))
            linkedEntity = null;
        return linkedEntity;
    }

    @Override
    public void setLinkedEntity(LivingEntity linkedEntity){
        this.linkedEntity = linkedEntity;
    }

    @Override
    public List<StackedSpawner> getNearbySpawners() {
        boolean chunkMerge = plugin.getSettings().chunkMergeSpawners;

        Stream<StackedSpawner> spawnerStream;

        if(chunkMerge){
            spawnerStream = plugin.getSystemManager().getStackedSpawners(getChunk()).stream();
        }

        else{
            int range = getMergeRadius();
            Location location = getLocation();

            int maxX = location.getBlockX() + range, maxY = location.getBlockY() + range, maxZ = location.getBlockZ() + range;
            int minX = location.getBlockX() - range, minY = location.getBlockY() - range, minZ = location.getBlockZ() - range;

            spawnerStream = plugin.getSystemManager().getStackedSpawners().stream()
                    .filter(stackedSpawner -> {
                        Location loc = stackedSpawner.getLocation();
                        return loc.getBlockX() >= minX && loc.getBlockX() <= maxX &&
                                loc.getBlockY() >= minY && loc.getBlockY() <= maxY &&
                                loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
                    });
        }

        return spawnerStream.filter(this::canStackIntoNoLimit).collect(Collectors.toList());
    }

    @Override
    public ItemStack getDropItem() {
        return plugin.getProviders().getSpawnerItem(object.getSpawnedType(), getStackAmount());
    }

    public LivingEntity getRawLinkedEntity(){
        return linkedEntity;
    }

    public void linkInventory(Inventory inventory){
        this.linkedInventories.add(inventory);
    }

    public void unlinkInventory(Inventory inventory){
        this.linkedInventories.remove(inventory);
    }

    public static StackedSpawner of(Block block){
        if(block.getState() instanceof CreatureSpawner)
            return of((CreatureSpawner) block.getState());
        throw new IllegalArgumentException("Only spawners can be applied to StackedSpawner object");
    }

    public static StackedSpawner of(CreatureSpawner creatureSpawner){
        return plugin.getSystemManager().getStackedSpawner(creatureSpawner);
    }

}
