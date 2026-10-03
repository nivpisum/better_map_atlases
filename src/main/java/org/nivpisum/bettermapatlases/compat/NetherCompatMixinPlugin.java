/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.compat;

import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Gates optional classes before ModList is available and preserves BNM's fallback. */
public final class NetherCompatMixinPlugin implements IMixinConfigPlugin {
    private boolean enabled;
    private static final String HEIGHT_DESCRIPTOR =
            "(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/levelgen/Heightmap$Types;IILnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;)I";

    @Override public void onLoad(String mixinPackage) {
        var loading = FMLLoader.getLoadingModList();
        if (loading == null) return;
        Set<String> ids = loading.getMods().stream().map(m -> m.getModId()).collect(Collectors.toSet());
        enabled = ids.containsAll(Set.of("betternethermap", "map_atlases", "moonlight"));
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return enabled; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!mixinClassName.endsWith(".mixin.BnmMapItemMixin")) return;
        int matched = 0;
        for (MethodNode method : targetClass.methods) {
            if (!method.desc.equals(HEIGHT_DESCRIPTOR) || !isBnmHandler(method.visibleAnnotations)) continue;
            // BNM's redirected call has already been merged at priority 1000.
            // Keep its original instructions for maps with no persisted cave height.
            LabelNode fallback = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 5)); // Level
            guard.add(new VarInsnNode(Opcodes.ALOAD, 7)); // MapItemSavedData
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/nivpisum/bettermapatlases/compat/NetherMapCompat", "sampleHeight",
                    "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;)Ljava/lang/Integer;", false));
            guard.add(new InsnNode(Opcodes.DUP));
            guard.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
            guard.add(new InsnNode(Opcodes.IRETURN));
            guard.add(fallback);
            guard.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Integer"}));
            guard.add(new InsnNode(Opcodes.POP));
            method.instructions.insert(guard);
            method.maxStack = Math.max(method.maxStack, 2);
            matched++;
        }
        if (matched != 1) throw new IllegalStateException(
                "Accurate Maps expected one Better Nether Map 1.2 height handler; found " + matched);
    }

    private static boolean isBnmHandler(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) continue;
            if (annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("mixin".equals(annotation.values.get(i))
                        && "com.syric.betternethermap.mixin.MixinMapItem".equals(annotation.values.get(i + 1))) return true;
            }
        }
        return false;
    }
}
