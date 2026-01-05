package com.spectralreplay.model;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public class ReplayFrame {
    private final Location location;
    private final PlayerAction action;
    private final boolean isSneaking;
    private final ItemStack itemInHand;
    private final ItemStack[] armor;

    public ReplayFrame(Location location, PlayerAction action, boolean isSneaking, ItemStack itemInHand, ItemStack[] armor) {
        this.location = location.clone();
        this.action = action;
        this.isSneaking = isSneaking;
        this.itemInHand = itemInHand != null ? itemInHand.clone() : null;
        this.armor = armor != null ? armor.clone() : null;
    }

    public Location getLocation() {
        return location;
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
