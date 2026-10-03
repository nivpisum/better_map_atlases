/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.nivpisum.bettermapatlases.compat.NetherMapCompat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;
import pepjebs.mapatlases.utils.MapDataHolder;

@Mixin(value = MapAtlasesAccessUtils.class, remap = false)
public abstract class AtlasAccessMixin {
    @Inject(method = "findMapFromItemStack", at = @At("HEAD"))
    private static void accurateMaps$importBeforeAtlas(Level level, ItemStack stack,
                                                      CallbackInfoReturnable<MapDataHolder> callback) {
        NetherMapCompat.importItem(level, stack);
    }

    @WrapOperation(method = "syncMapDataToClient", remap = false, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;getUpdatePacket(ILnet/minecraft/world/entity/player/Player;)Lnet/minecraft/network/protocol/Packet;"))
    private static Packet<?> accurateMaps$includeAtlasHeight(MapItemSavedData data, int id, Player player,
                                                            Operation<Packet<?>> original) {
        Packet<?> packet = original.call(data, id, player);
        NetherMapCompat.includeHeightInPacket(data, packet);
        return packet;
    }
}
