/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SurveyAtlasMixinPlugin implements IMixinConfigPlugin {
    private boolean enabled;
    private boolean bnm;
    @Override public void onLoad(String mixinPackage) {
        var loading = FMLLoader.getLoadingModList();
        if (loading == null) return;
        Set<String> ids = loading.getMods().stream().map(m -> m.getModId()).collect(Collectors.toSet());
        enabled = ids.containsAll(Set.of("map_atlases", "moonlight"));
        bnm = ids.contains("betternethermap");
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        // The existing height-aware collection hook handles both revisions with BNM.
        return enabled && (!mixin.endsWith("AtlasDynamicCollectionMixin") || !bnm);
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
}
