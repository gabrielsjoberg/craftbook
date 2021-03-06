// $Id$
/*
 * CraftBook Copyright (C) 2010 sk89q <http://www.sk89q.com>
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.craftbook.mech;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.material.Button;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import com.sk89q.craftbook.AbstractCraftBookMechanic;
import com.sk89q.craftbook.ChangedSign;
import com.sk89q.craftbook.LocalPlayer;
import com.sk89q.craftbook.bukkit.BukkitPlayer;
import com.sk89q.craftbook.bukkit.CraftBookPlugin;
import com.sk89q.craftbook.bukkit.util.BukkitUtil;
import com.sk89q.craftbook.util.EventUtil;
import com.sk89q.craftbook.util.ProtectionUtil;
import com.sk89q.craftbook.util.RegexUtil;
import com.sk89q.craftbook.util.SignUtil;
import com.sk89q.worldedit.blocks.BlockType;

/**
 * The default elevator mechanism -- wall signs in a vertical column that teleport the player vertically when triggered.
 *
 * @author sk89q
 * @author hash
 */
public class Elevator extends AbstractCraftBookMechanic {

    @Override
    public boolean enable() {
        if(CraftBookPlugin.inst().getConfiguration().elevatorSlowMove)
            flyingPlayers = new HashSet<String>();
        return true;
    }

    @Override
    public void disable() {

        if(flyingPlayers != null) {
            Iterator<String> it = flyingPlayers.iterator();
            while(it.hasNext()) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(it.next());
                if(!op.isOnline()) {
                    it.remove();
                    continue;
                }
                op.getPlayer().setFlying(false);
                op.getPlayer().setAllowFlight(op.getPlayer().getGameMode() == GameMode.CREATIVE);
                it.remove();
            }

            flyingPlayers = null;
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {

        if(!CraftBookPlugin.inst().getConfiguration().elevatorSlowMove) return;
        if(!(event.getEntity() instanceof Player)) return;
        if(!flyingPlayers.contains(((Player) event.getEntity()).getName())) return;
        if(event instanceof EntityDamageByEntityEvent) return;

        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {

        if(!CraftBookPlugin.inst().getConfiguration().elevatorSlowMove) return;
        //Clean up mechanics that store players that we don't want anymore.
        Iterator<String> it = flyingPlayers.iterator();
        while(it.hasNext()) {
            String p = it.next();
            if(event.getPlayer().getName().equalsIgnoreCase(p)) {
                event.getPlayer().setFlying(false);
                event.getPlayer().setAllowFlight(event.getPlayer().getGameMode() == GameMode.CREATIVE);
                it.remove();
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent event) {

        if(!EventUtil.passesFilter(event)) return;

        Direction dir = Direction.NONE;
        if(event.getLine(1).equalsIgnoreCase("[lift down]")) dir = Direction.DOWN;
        if(event.getLine(1).equalsIgnoreCase("[lift up]")) dir = Direction.UP;
        if(event.getLine(1).equalsIgnoreCase("[lift]")) dir = Direction.RECV;

        if(dir == Direction.NONE) return;
        LocalPlayer player = CraftBookPlugin.inst().wrapPlayer(event.getPlayer());

        if(!player.hasPermission("craftbook.mech.elevator")) {
            if(CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                player.printError("mech.create-permission");
            SignUtil.cancelSign(event);
            return;
        }

        switch (dir) {
            case UP:
                player.print("mech.lift.up-sign-created");
                event.setLine(1, "[Lift Up]");
                break;
            case DOWN:
                player.print("mech.lift.down-sign-created");
                event.setLine(1, "[Lift Down]");
                break;
            case RECV:
                player.print("mech.lift.target-sign-created");
                event.setLine(1, "[Lift]");
                break;
            default:
                SignUtil.cancelSign(event);
                return;
        }
    }

    public static enum Direction {
        NONE, UP, DOWN, RECV
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEvent event) {

        if (!EventUtil.passesFilter(event))
            return;

        if(event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        LocalPlayer localPlayer = CraftBookPlugin.inst().wrapPlayer(event.getPlayer());

        // check if this looks at all like something we're interested in first
        Direction dir = isLift(event.getClickedBlock());
        switch (dir) {
            case UP:
            case DOWN:
                break;
            case RECV:
                localPlayer.printError("mech.lift.no-depart");
                return;
            default:
                return;
        }

        // find destination sign
        BlockFace shift = dir == Direction.UP ? BlockFace.UP : BlockFace.DOWN;
        int f = dir == Direction.UP ? event.getClickedBlock().getWorld().getMaxHeight() : 0;
        Block destination = event.getClickedBlock();
        // heading up from top or down from bottom
        if (destination.getY() == f) {
            localPlayer.printError("mech.lift.no-destination");
            return;
        }
        boolean loopd = false;
        while (true) {
            destination = destination.getRelative(shift);
            Direction derp = isLift(destination);
            if (derp != Direction.NONE && isValidLift(BukkitUtil.toChangedSign(event.getClickedBlock()), BukkitUtil.toChangedSign(destination)))
                break; // found it!

            if (destination.getY() == event.getClickedBlock().getY()) {
                localPlayer.printError("mech.lift.no-destination");
                return;
            }
            if (CraftBookPlugin.inst().getConfiguration().elevatorLoop && !loopd) {
                if (destination.getY() == event.getClickedBlock().getWorld().getMaxHeight()) { // hit the top of the world
                    org.bukkit.Location low = destination.getLocation();
                    low.setY(0);
                    destination = destination.getWorld().getBlockAt(low);
                    loopd = true;
                } else if (destination.getY() == 0) { // hit the bottom of the world
                    org.bukkit.Location low = destination.getLocation();
                    low.setY(event.getClickedBlock().getWorld().getMaxHeight());
                    destination = destination.getWorld().getBlockAt(low);
                    loopd = true;
                }
            } else {
                if (destination.getY() == event.getClickedBlock().getWorld().getMaxHeight()) {
                    localPlayer.printError("mech.lift.no-destination");
                    return;
                }
                else if (destination.getY() == 0) {
                    localPlayer.printError("mech.lift.no-destination");
                    return;
                }
            }
        }

        if(task != null) {
            localPlayer.printError("mech.lift.busy");
            return;
        }

        if (!localPlayer.hasPermission("craftbook.mech.elevator.use")) {
            event.setCancelled(true);
            if(CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                localPlayer.printError("mech.use-permission");
            return;
        }

        if(!ProtectionUtil.canUse(event.getPlayer(), event.getClickedBlock().getLocation(), event.getBlockFace(), event.getAction())) {
            if(CraftBookPlugin.inst().getConfiguration().showPermissionMessages)
                localPlayer.printError("area.use-permissions");
            return;
        }

        makeItSo(localPlayer, destination, shift);

        event.setCancelled(true);
    }

    private void makeItSo(LocalPlayer player, Block destination, BlockFace shift) {
        // start with the block shifted vertically from the player
        // to the destination sign's height (plus one).
        Block floor = destination.getWorld().getBlockAt((int) Math.floor(player.getPosition().getPosition().getX()), destination.getY() + 1, (int) Math.floor(player.getPosition().getPosition().getZ()));
        // well, unless that's already a ceiling.
        if (!BlockType.canPassThrough(floor.getTypeId())) {
            floor = floor.getRelative(BlockFace.DOWN);
        }

        // now iterate down until we find enough open space to stand in
        // or until we're 5 blocks away, which we consider too far.
        int foundFree = 0;
        boolean foundGround = false;
        for (int i = 0; i < 5; i++) {
            if (BlockType.canPassThrough(floor.getTypeId())) {
                foundFree++;
            } else {
                foundGround = true;
                break;
            }
            if (floor.getY() == 0x0) {
                break;
            }
            floor = floor.getRelative(BlockFace.DOWN);
        }
        if (!foundGround) {
            player.printError("mech.lift.no-floor");
            return;
        }
        if (foundFree < 2) {
            player.printError("mech.lift.obstruct");
            return;
        }

        teleportPlayer(player, floor, destination, shift);
    }

    public HashSet<String> flyingPlayers;

    public void teleportPlayer(final LocalPlayer player, final Block floor, final Block destination, final BlockFace shift) {

        final Location newLocation = BukkitUtil.toLocation(player.getPosition());
        newLocation.setY(floor.getY() + 1);

        if(CraftBookPlugin.inst().getConfiguration().elevatorSlowMove) {

            final Location lastLocation = BukkitUtil.toLocation(player.getPosition());

            task = CraftBookPlugin.inst().getServer().getScheduler().runTaskTimer(CraftBookPlugin.inst(), new Runnable() {

                @Override
                public void run () {

                    OfflinePlayer op = ((BukkitPlayer)player).getPlayer();
                    if(!op.isOnline()) {
                        task.cancel();
                        task = null;
                        return;
                    }
                    Player p = op.getPlayer();
                    if(!flyingPlayers.contains(p.getName()))
                        flyingPlayers.add(p.getName());
                    p.setAllowFlight(true);
                    p.setFlying(true);
                    p.setFallDistance(0f);
                    p.setNoDamageTicks(2);
                    double speed = CraftBookPlugin.inst().getConfiguration().elevatorMoveSpeed;
                    newLocation.setPitch(p.getLocation().getPitch());
                    newLocation.setYaw(p.getLocation().getYaw());

                    if(Math.abs(newLocation.getY() - p.getLocation().getY()) < 0.7) {
                        p.teleport(newLocation);
                        teleportFinish(player, destination, shift);
                        p.setFlying(false);
                        p.setAllowFlight(p.getGameMode() == GameMode.CREATIVE);
                        task.cancel();
                        task = null;
                        flyingPlayers.remove(p.getName());
                        return;
                    }

                    if(lastLocation.getBlockX() != p.getLocation().getBlockX() || lastLocation.getBlockZ() != p.getLocation().getBlockZ()) {
                        player.print("mech.lift.leave");
                        p.setFlying(false);
                        p.setAllowFlight(p.getGameMode() == GameMode.CREATIVE);
                        task.cancel();
                        task = null;
                        flyingPlayers.remove(p.getName());
                        return;
                    }

                    if(newLocation.getY() > p.getLocation().getY()) {
                        p.setVelocity(new Vector(0, speed,0));
                        if(!BlockType.canPassThrough(p.getLocation().add(0, 2, 0).getBlock().getTypeId()))
                            p.teleport(p.getLocation().add(0, speed, 0));
                    } else if (newLocation.getY() < p.getLocation().getY()) {
                        p.setVelocity(new Vector(0, -speed,0));
                        if(!BlockType.canPassThrough(p.getLocation().add(0, -1, 0).getBlock().getTypeId()))
                            p.teleport(p.getLocation().add(0, -speed, 0));
                    } else {
                        p.setFlying(false);
                        p.setAllowFlight(p.getGameMode() == GameMode.CREATIVE);
                        teleportFinish(player, destination, shift);
                        task.cancel();
                        task = null;
                        flyingPlayers.remove(p.getName());
                        return;
                    }

                    lastLocation.setY(p.getLocation().getY());
                }
            }, 1, 1);
        } else {
            // Teleport!
            if (player.isInsideVehicle()) {

                newLocation.setX(((BukkitPlayer)player).getPlayer().getVehicle().getLocation().getX());
                newLocation.setY(floor.getY() + 2);
                newLocation.setZ(((BukkitPlayer)player).getPlayer().getVehicle().getLocation().getZ());
                newLocation.setYaw(((BukkitPlayer)player).getPlayer().getVehicle().getLocation().getYaw());
                newLocation.setPitch(((BukkitPlayer)player).getPlayer().getVehicle().getLocation().getPitch());
                ((BukkitPlayer)player).getPlayer().getVehicle().teleport(newLocation);
            }
            player.setPosition(BukkitUtil.toLocation(newLocation).getPosition(), newLocation.getPitch(), newLocation.getYaw());

            teleportFinish(player, destination, shift);
        }
    }

    private BukkitTask task;

    public void teleportFinish(LocalPlayer player, Block destination, BlockFace shift) {
        // Now, we want to read the sign so we can tell the player
        // his or her floor, but as that may not be avilable, we can
        // just print a generic message
        ChangedSign info = null;
        if (!SignUtil.isSign(destination)) {
            if (destination.getType() == Material.STONE_BUTTON || destination.getType() == Material.WOOD_BUTTON) {

                Button button = (Button) destination.getState().getData();
                if (SignUtil.isSign(destination.getRelative(button.getAttachedFace(), 2)))
                    info = BukkitUtil.toChangedSign(destination.getRelative(button.getAttachedFace(), 2));
            }
            if (info == null)
                return;
        } else
            info = BukkitUtil.toChangedSign(destination);
        String title = info.getLines()[0];
        if (!title.isEmpty()) {
            player.print(player.translate("mech.lift.floor") + ": " + title);
        } else {
            player.print(shift.getModY() > 0 ? "mech.lift.up" : "mech.lift.down");
        }
    }

    public static boolean isValidLift(ChangedSign start, ChangedSign stop) {

        if (start == null || stop == null) return true;
        if (start.getLine(2).toLowerCase(Locale.ENGLISH).startsWith("to:")) {
            try {
                return stop.getLine(0).equalsIgnoreCase(RegexUtil.COLON_PATTERN.split(start.getLine(2))[0].trim());
            } catch (Exception e) {
                start.setLine(2, "");
                return false;
            }
        } else return true;
    }

    private static Elevator.Direction isLift(Block block) {

        if (!SignUtil.isSign(block)) {
            if (CraftBookPlugin.inst().getConfiguration().elevatorButtonEnabled && (block.getType() == Material.STONE_BUTTON || block.getType() == Material.WOOD_BUTTON)) {
                Button b = (Button) block.getState().getData();
                if(b == null || b.getAttachedFace() == null)
                    return Direction.NONE;
                Block sign = block.getRelative(b.getAttachedFace(), 2);
                if (SignUtil.isSign(sign))
                    return isLift(BukkitUtil.toChangedSign(sign));
            }
            return Direction.NONE;
        }

        return isLift(BukkitUtil.toChangedSign(block));
    }

    private static Elevator.Direction isLift(ChangedSign sign) {
        // if you were really feeling frisky this could definitely
        // be optomized by converting the string to a char[] and then
        // doing work

        if (sign.getLine(1).equalsIgnoreCase("[Lift Up]")) return Direction.UP;
        if (sign.getLine(1).equalsIgnoreCase("[Lift Down]")) return Direction.DOWN;
        if (sign.getLine(1).equalsIgnoreCase("[Lift]")) return Direction.RECV;
        return Direction.NONE;
    }
}