/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography;

import net.mehvahdjukaar.moonlight.api.map.ExpandedMapData;
import net.mehvahdjukaar.moonlight.api.map.MapDataRegistry;
import net.mehvahdjukaar.moonlight.api.map.markers.MapBlockMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;
import org.nivpisum.bettermapatlases.cartography.mixin.MapDecorationsAccessor;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;
import pepjebs.mapatlases.PlatStuff;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.map_collection.IMapCollection;
import pepjebs.mapatlases.mixin.MapItemSavedDataAccessor;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;
import pepjebs.mapatlases.utils.MapDataHolder;

import java.util.function.Predicate;

/** All costs follow a confirmed server mutation. Failed/replayed requests cost nothing. */
public final class CartographyActions {
    private CartographyActions() {}

    public static void addPin(ServerPlayer player, String mapName, ColumnPos pos) {
        MapDataHolder holder = accessibleMap(player, mapName);
        if (holder == null) return;
        ItemStack banner = findBanner(player);
        if (banner.isEmpty()) {
            message(player, "banner_required");
            return;
        }
        if (!addBannerPin(holder.data, banner, pos, player.isCreative())) return;
        inventoryChanged(player);
        sync(holder, player);
    }

    /** Also used by detached runtime checks; never writes a world block or vanilla bannerMarkers. */
    static boolean addBannerPin(MapItemSavedData map, ItemStack banner, ColumnPos pos, boolean creative) {
        if (!(banner.getItem() instanceof BannerItem item) || banner.isEmpty()
                || !(map instanceof ExpandedMapData expanded)
                || !AtlasActionRules.insideMap(map.centerX, map.centerZ, map.scale, pos.x(), pos.z())
                || expanded.getCustomMarkers().size() >= AtlasActionRules.MAX_PINS_PER_MAP) return false;
        for (MapBlockMarker<?> old : expanded.getCustomMarkers().values()) {
            if (old instanceof BannerPins.BannerMarker && old.getPos().getX() == pos.x()
                    && old.getPos().getZ() == pos.z()) return false;
        }
        var type = MapDataRegistry.getOptional(BannerPins.id(item.getColor())).orElse(null);
        if (type == null) return false;
        MapBlockMarker<?> marker = type.createEmptyMarker();
        if (!(marker instanceof BannerPins.BannerMarker)) return false;
        marker.setPos(new BlockPos(pos.x(), 0, pos.z()));
        marker.setName(banner.hasCustomHoverName() ? banner.getHoverName().copy() : null);
        marker.setPersistent(true);
        marker.setRotation(0);
        if (marker.createDecorationFromMarker(map) == null
                || expanded.getCustomMarkers().containsKey(marker.getMarkerId())) return false;
        expanded.addCustomMarker(marker);
        if (expanded.getCustomMarkers().get(marker.getMarkerId()) != marker) return false;
        if (!creative) banner.shrink(1);
        return true;
    }

    public static void removeMarker(ServerPlayer player, String mapName, int hash, boolean custom) {
        MapDataHolder holder = accessibleMap(player, mapName);
        if (holder == null) return;
        ItemStack shears = findShears(player);
        if (shears.isEmpty()) {
            message(player, "shears_required");
            return;
        }
        boolean removed = custom ? removeCustomMarker(holder.data, hash) : removeBannerMarker(holder.data, hash);
        if (!removed) return;
        damageShears(player, shears);
        sync(holder, player);
    }

    static boolean removeCustomMarker(MapItemSavedData map, int hash) {
        if (!(map instanceof ExpandedMapData expanded)) return false;
        String match = null;
        for (var entry : expanded.getCustomMarkers().entrySet()) {
            var decoration = entry.getValue().createDecorationFromMarker(map);
            if (decoration == null || decoration.hashCode() != hash) continue;
            if (match != null) return false; // The native hash protocol cannot disambiguate a collision.
            match = entry.getKey();
        }
        return match != null && expanded.removeCustomMarker(match);
    }

    private static boolean removeBannerMarker(MapItemSavedData map, int hash) {
        if (!(map instanceof MapItemSavedDataAccessor accessor)) return false;
        String match = null;
        for (String id : accessor.getBannerMarkers().keySet()) {
            var decoration = ((MapDecorationsAccessor) map).betterMapAtlases$getDecorations().get(id);
            if (decoration == null || decoration.hashCode() != hash) continue;
            if (match != null) return false;
            match = id;
        }
        if (match == null || accessor.getBannerMarkers().remove(match) == null) return false;
        accessor.invokeRemoveDecoration(match);
        map.setDirty();
        return true;
    }

    public static void cutMap(ServerPlayer player, int id) {
        if (!canEdit(player) || id < 0) return;
        ItemStack atlas = MapAtlasesAccessUtils.getAtlasFromPlayerByConfig(player);
        if (!(atlas.getItem() instanceof MapAtlasItem)) return;
        IMapCollection maps = MapAtlasItem.getMaps(atlas, player.level());
        if (!maps.hasId(id)) return;
        ItemStack shears = findShears(player);
        if (shears.isEmpty()) {
            message(player, "shears_required");
            return;
        }
        MapDataHolder holder = maps.getAll().stream().filter(map -> map.id == id).findFirst().orElse(null);
        if (holder == null) return;
        ItemStack extracted = holder.createExistingMapItem();
        if (extracted.isEmpty() || !maps.remove(holder)) return;
        if (!player.getInventory().add(extracted)) player.drop(extracted, false);
        damageShears(player, shears);
    }

    public static ItemStack findBanner(Player player) {
        return findCarried(player, stack -> stack.getItem() instanceof BannerItem);
    }

    public static ItemStack findShears(Player player) {
        return findCarried(player, stack -> PlatStuff.isShear(stack)
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage()));
    }

    private static ItemStack findCarried(Player player, Predicate<ItemStack> matches) {
        ItemStack main = player.getMainHandItem();
        if (!main.isEmpty() && matches.test(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty() && matches.test(off)) return off;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && matches.test(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static @Nullable MapDataHolder accessibleMap(ServerPlayer player, String name) {
        if (!canEdit(player)) return null;
        int id = AtlasActionRules.mapNumber(name);
        if (id < 0 || !hasMapAccess(player, id)) return null;
        MapDataHolder holder = MapDataHolder.findFromId(player.level(), id);
        // Reject alternate prefixes which could otherwise alias another saved-data object.
        return holder != null && holder.stringId.equals(name) ? holder : null;
    }

    private static boolean hasMapAccess(ServerPlayer player, int id) {
        if (SurveyAtlas.canAccessMap(player, id)) return true;
        if (player.containerMenu instanceof CartographyTableMenu menu && menu.stillValid(player)) {
            ItemStack atlas = menu.getSlot(0).getItem();
            return atlas.getItem() instanceof MapAtlasItem && MapAtlasItem.getMaps(atlas, player.level()).hasId(id);
        }
        return false;
    }

    private static boolean canEdit(ServerPlayer player) { return player.isAlive() && !player.isSpectator(); }

    private static void damageShears(ServerPlayer player, ItemStack shears) {
        if (!player.isCreative()) shears.hurtAndBreak(1, player, ignored -> {});
        inventoryChanged(player);
    }

    private static void inventoryChanged(ServerPlayer player) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }

    private static void sync(MapDataHolder holder, ServerPlayer player) {
        holder.data.getHoldingPlayer(player);
        if (holder.data instanceof ExpandedMapData expanded) expanded.setCustomDecorationsDirty();
        var packet = holder.data.getUpdatePacket(holder.id, player);
        if (packet != null) player.connection.send(packet);
    }

    public static void message(Player player, String key) {
        player.displayClientMessage(Component.translatable("message.better_map_atlases." + key), true);
    }
}
