/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.mixin;

import org.nivpisum.bettermapatlases.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MapItemSavedData.class)
public abstract class MapSavedDataMixin implements MapLayerAccess {
    @Unique private MapLayer accurateMaps$layer;

    @Override public synchronized MapLayer accurateMaps$getLayer(boolean create) {
        if (accurateMaps$layer == null && create) accurateMaps$layer = new MapLayer();
        return accurateMaps$layer;
    }
    @Override public synchronized void accurateMaps$setLayer(MapLayer layer) { accurateMaps$layer = layer; }

    @Inject(method = "save", at = @At("HEAD"))
    private void accurateMaps$flushSave(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) {
        DeferredMapSamples.flush((MapItemSavedData) (Object) this);
    }

    @Inject(method = "locked", at = @At("HEAD"))
    private void accurateMaps$flushLock(CallbackInfoReturnable<MapItemSavedData> callback) {
        DeferredMapSamples.flush((MapItemSavedData) (Object) this);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private static void accurateMaps$load(CompoundTag tag, CallbackInfoReturnable<MapItemSavedData> callback) {
        if (tag.contains(MapLayer.TAG, Tag.TAG_COMPOUND)) {
            ((MapLayerAccess) callback.getReturnValue()).accurateMaps$setLayer(MapLayer.load(tag.getCompound(MapLayer.TAG)));
        }
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void accurateMaps$save(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) {
        if (accurateMaps$layer != null) callback.getReturnValue().put(MapLayer.TAG, accurateMaps$layer.save());
    }

    @Inject(method = "locked", at = @At("RETURN"))
    private void accurateMaps$lock(CallbackInfoReturnable<MapItemSavedData> callback) {
        if (accurateMaps$layer != null) ((MapLayerAccess) callback.getReturnValue()).accurateMaps$setLayer(accurateMaps$layer.copy());
    }

    @Inject(method = "setColor", at = @At("HEAD"))
    private void accurateMaps$invalidate(int x, int z, byte color, CallbackInfo callback) {
        MapSampler.invalidate((MapItemSavedData) (Object) this, x, z);
    }

    @Inject(method = "getUpdatePacket", at = @At("RETURN"))
    private void accurateMaps$sync(int id, Player player, CallbackInfoReturnable<Packet<?>> callback) {
        if (player instanceof ServerPlayer serverPlayer) MapNetwork.sync(id, serverPlayer, (MapItemSavedData) (Object) this);
    }
}
