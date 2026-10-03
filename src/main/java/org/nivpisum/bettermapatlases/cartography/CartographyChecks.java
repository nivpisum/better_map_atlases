/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography;

import io.netty.buffer.Unpooled;
import net.mehvahdjukaar.moonlight.api.map.ExpandedMapData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import pepjebs.mapatlases.recipe.MapAtlasesAddRecipe;
import pepjebs.mapatlases.recipe.MapAtlasesCutExistingRecipe;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.config.MapAtlasesConfig;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.utils.AtlasCartographyTable;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;
import pepjebs.mapatlases.utils.MapDataHolder;
import org.nivpisum.bettermapatlases.dynamic.SurveyAtlas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Run under the transformed Forge loader after a world has loaded; all maps/items are detached. */
public final class CartographyChecks {
    private CartographyChecks() {}

    public static Map<String, Object> run() {
        List<String> checks = new ArrayList<>();
        var recipe = new MapAtlasesAddRecipe(new ResourceLocation("better_map_atlases", "detached_test"),
                CraftingBookCategory.MISC);
        require(!recipe.matches(null, null) && recipe.assemble(null, null).isEmpty(),
                "crafting addition and direct assembly are disabled before touching inputs");
        var cut = new MapAtlasesCutExistingRecipe(new ResourceLocation("better_map_atlases", "detached_cut_test"),
                CraftingBookCategory.MISC);
        require(!cut.matches(null, null) && cut.assemble(null, null).isEmpty(), "workbench cutting is disabled");
        checks.add("workbench addition/cutting disabled by transformed mixin; atlas creation class untouched");

        MapItemSavedData map = MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, Level.OVERWORLD);
        ExpandedMapData expanded = (ExpandedMapData) map;
        ItemStack banner = new ItemStack(Items.CYAN_BANNER, 2);
        Component name = Component.literal("河口基地").withStyle(ChatFormatting.GOLD);
        banner.setHoverName(name);
        require(CartographyActions.addBannerPin(map, banner, new ColumnPos(0, 0), false), "first banner pin");
        require(banner.getCount() == 1 && expanded.getCustomMarkers().size() == 1,
                "one successful pin costs one banner");
        require(!CartographyActions.addBannerPin(map, banner, new ColumnPos(0, 0), false)
                        && !CartographyActions.addBannerPin(map, banner, new ColumnPos(128, 0), false)
                        && !CartographyActions.addBannerPin(map, ItemStack.EMPTY, new ColumnPos(4, 4), false)
                        && banner.getCount() == 1 && expanded.getCustomMarkers().size() == 1,
                "duplicate, outside tile and missing banner leave both materials and map unchanged");
        var marker = expanded.getCustomMarkers().values().iterator().next();
        require(marker.getTypeId().equals("better_map_atlases:banner_pin_cyan") && name.equals(marker.getName())
                        && marker.shouldSave() && !marker.shouldRefresh() && map.getBanners().isEmpty(),
                "native persistent pin inherits colour and rich name without a world banner");
        checks.add("banner charge only after success; duplicate/bounds rejection; colour/name and no world-block dependency");

        MapItemSavedData restored = MapItemSavedData.load(map.save(new CompoundTag()));
        var restoredMarkers = ((ExpandedMapData) restored).getCustomMarkers();
        require(restoredMarkers.size() == 1 && name.equals(restoredMarkers.values().iterator().next().getName())
                        && restoredMarkers.values().iterator().next().getTypeId().equals(marker.getTypeId()),
                "banner pin survives map NBT reload");
        var decoration = marker.createDecorationFromMarker(map);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            decoration.saveToBuffer(buffer);
            var received = marker.getType().loadDecorationFromBuffer(buffer);
            require(received instanceof BannerPins.BannerDecoration && name.equals(received.getDisplayName())
                            && received.hashCode() == decoration.hashCode() && !buffer.isReadable(),
                    "native Moonlight wire format roundtrip preserves deletion identity");
            ((BannerPins.BannerDecoration) received).forceFocused(true);
            require(((BannerPins.BannerDecoration) received).isFocused(), "received pin focuses without a backing marker");
        } finally {
            buffer.release();
        }
        checks.add("NBT and native network roundtrip; received pin supports focus without null marker failure");

        int beforeCreative = banner.getCount();
        require(CartographyActions.addBannerPin(map, banner, new ColumnPos(8, 8), true)
                        && banner.getCount() == beforeCreative, "creative banner is retained");
        require(CartographyActions.removeCustomMarker(map, decoration.hashCode())
                        && !CartographyActions.removeCustomMarker(map, decoration.hashCode())
                        && expanded.getCustomMarkers().size() == 1, "deletion succeeds once, replay is a no-op");
        checks.add("creative material exemption and successful deletion followed by replay rejection");
        return Map.of("passed", true, "checks", checks);
    }

    /**
     * Uses IDs already in the test player's book; allocates no maps and changes no blocks.
     * The fixture must contain a surface/cave pair for the same tile, both at the same scale.
     */
    public static Map<String, Object> table(ServerPlayer player) {
        ItemStack source = MapAtlasesAccessUtils.getAtlasFromPlayerByConfig(player);
        require(source.getItem() instanceof MapAtlasItem, "carry the test atlas for the table check");
        var available = MapAtlasItem.getMaps(source, player.level()).getAll();
        MapDataHolder surface = null, cave = null;
        for (MapDataHolder a : available) for (MapDataHolder b : available) {
            if (!SurveyAtlas.isDynamic(a.data) && SurveyAtlas.isDynamic(b.data) && a.id != b.id
                    && a.data.centerX == b.data.centerX && a.data.centerZ == b.data.centerZ
                    && a.data.scale == b.data.scale && a.data.dimension.equals(b.data.dimension)) {
                surface = a;
                cave = b;
                break;
            }
        }
        require(surface != null && cave != null, "test atlas needs separate surface/cave IDs for the same tile");
        require(MapAtlasesConfig.mapEntryValueMultiplier.get() == 1,
                "table fixture expects the native one-map entry multiplier");
        require(MapAtlasItem.getMaxMapCount() < 0 || MapAtlasItem.getMaxMapCount() >= 4,
                "table fixture requires capacity for two filled and two blank maps");

        List<ItemStack> inventory = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            inventory.add(player.getInventory().getItem(i).copy());
        var originalMenu = player.containerMenu;
        ItemStack cursor = originalMenu.getCarried().copy();
        CompoundTag originalAtlas = source.save(new CompoundTag());
        List<String> checks = new ArrayList<>(), failures = new ArrayList<>();
        try {
            ItemStack book = new ItemStack(MapAtlasesMod.MAP_ATLAS.get());
            require(MapAtlasItem.getMaps(book, player.level()).add(surface.id, player.level()), "seed detached atlas");
            MapAtlasItem.setEmptyMaps(book, 0);

            CartographyTableMenu blankMenu = tableMenu(player, book, new ItemStack(Items.MAP, 2));
            ItemStack withBlanks = takeResult(blankMenu, player);
            require(withBlanks.getItem() instanceof MapAtlasItem && withBlanks.getCount() == 1,
                    "two blank maps return one atlas");
            record(MapAtlasItem.getEmptyMaps(withBlanks) == 2
                            && MapAtlasItem.getMaps(withBlanks, player.level()).hasId(surface.id)
                            && blankMenu.getSlot(0).getItem().isEmpty() && blankMenu.getSlot(1).getItem().isEmpty(),
                    "two blank maps consumed exactly once; original page retained in output atlas", checks, failures);

            ItemStack duplicateCaveMaps = cave.createExistingMapItem().copyWithCount(2);
            CartographyTableMenu filledMenu = tableMenu(player, withBlanks, duplicateCaveMaps);
            ItemStack withCave = takeResult(filledMenu, player);
            require(withCave.getItem() instanceof MapAtlasItem, "filled-map insertion returns an atlas");
            var inserted = MapAtlasItem.getMaps(withCave, player.level());
            record(inserted.hasId(surface.id) && inserted.hasId(cave.id) && inserted.getCount() == 2
                            && MapAtlasItem.getEmptyMaps(withCave) == 2,
                    "separate cave ID inserts beside surface ID at the same tile without losing blanks", checks, failures);
            record(filledMenu.getSlot(1).getItem().getCount() == 1,
                    "one filled-map insertion consumes one of two copies", checks, failures);

            ItemStack shears = new ItemStack(Items.SHEARS);
            shears.setDamageValue(7);
            CartographyTableMenu shearMenu = tableMenu(player, withCave, shears);
            var shearPages = MapAtlasItem.getMaps(shearMenu.getSlot(0).getItem(), player.level()).getAll();
            int index = -1;
            for (int i = 0; i < shearPages.size(); i++) if (shearPages.get(i).id == cave.id) index = i;
            require(index >= 0, "cave page is selectable for extraction");
            ((AtlasCartographyTable) shearMenu).mapatlases$setSelectedMapIndex(index);
            shearMenu.slotsChanged(shearMenu.container);
            ItemStack extracted = takeResult(shearMenu, player);
            ItemStack retained = shearMenu.getSlot(0).getItem();
            var remaining = MapAtlasItem.getMaps(retained, player.level());
            record(extracted.is(Items.FILLED_MAP) && Integer.valueOf(cave.id).equals(MapItem.getMapId(extracted))
                            && retained.getCount() == 1 && remaining.hasId(surface.id) && !remaining.hasId(cave.id)
                            && MapAtlasItem.getEmptyMaps(retained) == 2,
                    "table cuts the selected cave ID once and retains surface atlas and blank supply", checks, failures);
            record(shearMenu.getSlot(1).getItem().getCount() == 1
                            && shearMenu.getSlot(1).getItem().getDamageValue() == 8,
                    "table extraction retains shears and costs one durability", checks, failures);
            require(originalAtlas.equals(source.save(new CompoundTag())), "original carried atlas is unchanged");
            return Map.of("passed", failures.isEmpty(), "checks", checks, "failures", failures,
                    "surfaceId", surface.id, "caveId", cave.id, "worldBlocksChanged", false);
        } finally {
            for (int i = 0; i < inventory.size(); i++) {
                if (!ItemStack.matches(player.getInventory().getItem(i), inventory.get(i)))
                    player.getInventory().setItem(i, inventory.get(i));
            }
            if (!ItemStack.matches(originalMenu.getCarried(), cursor)) originalMenu.setCarried(cursor);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        }
    }

    private static CartographyTableMenu tableMenu(ServerPlayer player, ItemStack atlas, ItemStack material) {
        CartographyTableMenu menu = new CartographyTableMenu(0, player.getInventory(),
                ContainerLevelAccess.create(player.level(), player.blockPosition()));
        require(menu.getSlot(0).mayPlace(atlas) && menu.getSlot(1).mayPlace(material),
                "native table slots accept atlas and material");
        menu.getSlot(0).set(atlas);
        menu.getSlot(1).set(material);
        return menu;
    }

    private static ItemStack takeResult(CartographyTableMenu menu, ServerPlayer player) {
        var slot = menu.getSlot(2);
        require(slot.hasItem(), "native cartography result exists");
        ItemStack result = slot.remove(slot.getItem().getCount());
        slot.onTake(player, result);
        return result;
    }

    private static void record(boolean value, String label, List<String> checks, List<String> failures) {
        (value ? checks : failures).add(label);
    }

    private static void require(boolean value, String label) {
        if (!value) throw new IllegalStateException("Cartography self-test failed: " + label);
    }
}
