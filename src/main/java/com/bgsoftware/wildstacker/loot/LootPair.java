package com.bgsoftware.wildstacker.loot;

import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.objects.StackedEntity;
import com.bgsoftware.wildstacker.utils.Random;
import com.bgsoftware.wildstacker.utils.json.JsonUtils;
import com.bgsoftware.wildstacker.utils.threads.Executor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

@SuppressWarnings({"WeakerAccess", "unchecked"})
public class LootPair {

    private final List<LootItem> lootItems = new LinkedList<>();
    private final List<LootCommand> lootCommands = new LinkedList<>();
    private final double chance, lootingChance;
    private final List<Predicate<Entity>> entityFilters = new LinkedList<>();
    private final List<Predicate<Entity>> killerFilters = new LinkedList<>();

    private LootPair(List<LootItem> lootItems, List<LootCommand> lootCommands, double chance,
                     double lootingChance, List<Predicate<Entity>> entityFilters, List<Predicate<Entity>> killerFilters) {
        this.lootItems.addAll(lootItems);
        this.lootCommands.addAll(lootCommands);
        this.chance = chance;
        this.lootingChance = lootingChance;
        this.entityFilters.addAll(entityFilters);
        this.killerFilters.addAll(killerFilters);
    }

    public static LootPair fromJson(JSONObject jsonObject, String lootTableName) {
        double chance = JsonUtils.getDouble(jsonObject, "chance", 100);
        double lootingChance = JsonUtils.getDouble(jsonObject, "lootingChance", 0);
        List<LootItem> lootItems = new ArrayList<>();
        List<LootCommand> lootCommands = new ArrayList<>();

        List<Predicate<Entity>> entityFilters = new ArrayList<>();
        List<Predicate<Entity>> killerFilters = new ArrayList<>();

        String requiredPermission = (String) jsonObject.getOrDefault("permission", "");
        if (!requiredPermission.isEmpty())
            killerFilters.add(EntityFilters.checkPermissionFilter(requiredPermission));

        String requiredUpgrade = (String) jsonObject.getOrDefault("upgrade", "");
        if (!requiredUpgrade.isEmpty())
            entityFilters.add(EntityFilters.checkUpgradeFilter(requiredUpgrade));

        try {
            Object spawnCauseFilterObject = jsonObject.get("spawn-cause");
            if (spawnCauseFilterObject instanceof String)
                entityFilters.add(EntityFilters.spawnCauseFilter((String) spawnCauseFilterObject));
            else if (spawnCauseFilterObject instanceof JSONArray)
                entityFilters.add(EntityFilters.spawnCausesFilter((JSONArray) spawnCauseFilterObject));
        } catch (IllegalArgumentException ignored) {
        }

        try {
            Object deathCauseFilterObject = jsonObject.get("death-cause");
            if (deathCauseFilterObject instanceof String)
                entityFilters.add(EntityFilters.deathCauseFilter((String) deathCauseFilterObject));
            else if (deathCauseFilterObject instanceof JSONArray)
                entityFilters.add(EntityFilters.deathCausesFilter((JSONArray) deathCauseFilterObject));
        } catch (IllegalArgumentException ignored) {
        }

        try {
            Object jsonKillerFilters = jsonObject.get("killer");
            if (jsonKillerFilters instanceof JSONArray) {
                ((JSONArray) jsonKillerFilters).forEach(filterObject -> {
                    if (filterObject instanceof String)
                        killerFilters.add(EntityFilters.typeFilter((String) filterObject));
                    else if (filterObject instanceof JSONObject)
                        killerFilters.add(EntityFilters.advancedFilter((JSONObject) filterObject));
                });
            } else if (jsonKillerFilters instanceof String) {
                killerFilters.add(EntityFilters.typeFilter((String) jsonKillerFilters));
            }
        } catch (IllegalArgumentException ignored) {
        }

        if ((Boolean) jsonObject.getOrDefault("killedByPlayer", false)) {
            killerFilters.add(entity -> entity instanceof Player);
        }

        if ((Boolean) jsonObject.getOrDefault("killedByCharged", false)) {
            killerFilters.add(entity -> entity instanceof Creeper && ((Creeper) entity).isPowered());
        }

        if (jsonObject.containsKey("items")) {
            ((JSONArray) jsonObject.get("items")).forEach(element -> {
                try {
                    lootItems.add(LootItem.fromJson((JSONObject) element));
                } catch (IllegalArgumentException ex) {
                    WildStackerPlugin.log("[" + lootTableName + "] " + ex.getMessage());
                }
            });
        }

        if (jsonObject.containsKey("commands")) {
            ((JSONArray) jsonObject.get("commands")).forEach(element -> lootCommands.add(LootCommand.fromJson((JSONObject) element)));
        }

        return new LootPair(lootItems, lootCommands, chance, lootingChance, entityFilters, killerFilters);
    }

    public List<ItemStack> getItems(StackedEntity stackedEntity, int amountOfPairs, int lootBonusLevel) {
        List<ItemStack> items = new ArrayList<>();

        for (LootItem lootItem : lootItems) {
            if (!lootItem.checkKiller(LootTable.getEntityKiller(stackedEntity)) ||
                    !lootItem.checkEntity(stackedEntity.getLivingEntity()))
                continue;

            int amountOfItems = (int) (lootItem.getChance(lootBonusLevel, lootingChance) * amountOfPairs / 100);

            if (amountOfItems == 0) {
                amountOfItems = Random.nextChance(lootItem.getChance(lootBonusLevel, lootingChance), amountOfPairs);
            }

            ItemStack itemStack = lootItem.getItemStack(stackedEntity, amountOfItems, lootBonusLevel);

            if (itemStack != null)
                items.add(itemStack);
        }

        return items;
    }

    public void executeCommands(Player player, int amountOfPairs, int lootBonusLevel) {
        List<String> commands = new ArrayList<>();

        for (LootCommand lootCommand : lootCommands) {
            if (!lootCommand.getRequiredPermission().isEmpty() && !player.hasPermission(lootCommand.getRequiredPermission()))
                continue;

            int amountOfCommands = (int) (lootCommand.getChance(lootBonusLevel, lootingChance) * amountOfPairs / 100);

            if (amountOfCommands == 0) {
                amountOfCommands = Random.nextChance(lootCommand.getChance(lootBonusLevel, lootingChance), amountOfPairs);
            }

            commands.addAll(lootCommand.getCommands(player, amountOfCommands));
        }

        Executor.sync(() -> commands.forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)));
    }

    public boolean checkEntity(@Nullable Entity entity) {
        return checkFiltersOnEntity(this.entityFilters, entity);
    }

    public boolean checkKiller(@Nullable Entity killer) {
        return checkFiltersOnEntity(this.killerFilters, killer);
    }

    private static boolean checkFiltersOnEntity(List<Predicate<Entity>> filters, @Nullable Entity entity) {
        if (filters.isEmpty())
            return true;

        if (entity == null)
            return false;

        for (Predicate<Entity> filter : filters) {
            if (filter.test(entity))
                return true;
        }

        return false;
    }

    public double getChance() {
        return chance;
    }

    @Override
    public String toString() {
        return "LootPair{items=" + lootItems + "}";
    }

}
