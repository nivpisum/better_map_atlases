/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.cartography;

import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class CartographyMixinPlugin implements IMixinConfigPlugin {
    private boolean enabled;
    @Override public void onLoad(String mixinPackage) {
        var loading = FMLLoader.getLoadingModList();
        if (loading != null) enabled = loading.getMods().stream().map(m -> m.getModId())
                .collect(Collectors.toSet()).contains("map_atlases");
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return enabled; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
}
