package com.spectralreplay.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public class ReplayFrame {
    private final double x, y, z;
    private final float yaw, pitch;
    private final String worldName;
    private final PlayerAction action;
    private final boolean isSneaking;
    private final ItemStack itemInHand;
    private final ItemStack[] armor;

    public ReplayFrame(Location location, PlayerAction action, boolean isSneaking, ItemStack itemInHand, ItemStack[] armor) {
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
        this.worldName = location.getWorld().getName();
        
        this.action = action;
        this.isSneaking = isSneaking;
        // Store directly, caller must handle cloning/reuse
        this.itemInHand = itemInHand;
        this.armor = armor;
    }

    public Location getLocation() {
        return new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
    }

    public PlayerAction getAction() {
        return action;
    }

    public boolean isSneaking() {
        return isSneaking;
    }

    public ItemStack getItemInHand() {
        return itemInHand;
    }

    public ItemStack[] getArmor() {
        return armor;
    }
}
