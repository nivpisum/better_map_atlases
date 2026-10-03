/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.mixin;

import org.nivpisum.bettermapatlases.dynamic.SurveyAccess;
import org.nivpisum.bettermapatlases.dynamic.SurveyLayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Kept separate from the existing surface layer so neither storage format changes the other. */
@Mixin(MapItemSavedData.class)
public abstract class SurveySavedDataMixin implements SurveyAccess {
    @Unique private static final Logger accurateMaps$surveyLogger = LoggerFactory.getLogger("accurate_maps/survey");
    @Unique private SurveyLayer accurateMaps$survey;

    @Override public synchronized SurveyLayer accurateMaps$getSurvey(boolean create) {
        if (accurateMaps$survey == null && create) {
            MapItemSavedData self = (MapItemSavedData) (Object) this;
            accurateMaps$survey = new SurveyLayer(accurateMaps$metadata(self), self::setDirty);
            self.setDirty();
        }
        return accurateMaps$survey;
    }

    @Override public synchronized void accurateMaps$setSurvey(SurveyLayer layer) {
        MapItemSavedData self = (MapItemSavedData) (Object) this;
        accurateMaps$survey = layer == null ? null : layer.copy(self::setDirty);
        self.setDirty();
    }

    @Inject(method = "load", at = @At("RETURN"))
    private static void accurateMaps$loadSurvey(CompoundTag tag, CallbackInfoReturnable<MapItemSavedData> callback) {
        if (!tag.contains(SurveyLayer.TAG, Tag.TAG_COMPOUND)) return;
        MapItemSavedData self = callback.getReturnValue();
        SurveyLayer layer = SurveyLayer.loadPreserving(tag.getCompound(SurveyLayer.TAG), accurateMaps$metadata(self), self::setDirty);
        ((SurveyAccess) self).accurateMaps$setSurvey(layer);
        if (!layer.writable()) accurateMaps$surveyLogger.warn("Preserving unreadable dynamic map history at {}, {} in {}: {}",
            self.centerX, self.centerZ, self.dimension.location(), layer.preservedReason());
    }

    @Inject(method = "save", at = @At("RETURN"))
    private synchronized void accurateMaps$saveSurvey(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) {
        if (accurateMaps$survey != null) callback.getReturnValue().put(SurveyLayer.TAG, accurateMaps$survey.save());
    }

    @Inject(method = "locked", at = @At("RETURN"))
    private synchronized void accurateMaps$lockSurvey(CallbackInfoReturnable<MapItemSavedData> callback) {
        if (accurateMaps$survey != null) ((SurveyAccess) callback.getReturnValue()).accurateMaps$setSurvey(accurateMaps$survey);
    }

    @Inject(method = "scaled", at = @At("RETURN"))
    private synchronized void accurateMaps$scaleSurvey(int amount, CallbackInfoReturnable<MapItemSavedData> callback) {
        if (accurateMaps$survey == null) return;
        MapItemSavedData scaled = callback.getReturnValue();
        ((SurveyAccess) scaled).accurateMaps$setSurvey(accurateMaps$survey.emptyScaled(accurateMaps$metadata(scaled), scaled::setDirty));
    }

    @Unique private static SurveyLayer.Metadata accurateMaps$metadata(MapItemSavedData data) {
        return new SurveyLayer.Metadata(data.scale, data.centerX, data.centerZ, data.dimension.location());
    }
}
