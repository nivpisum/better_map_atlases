/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases;

import java.util.List;
import org.nivpisum.bettermapatlases.compat.NetherCompatMixinPlugin;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.jupiter.api.Assertions.*;

class NetherCompatBytecodeTest {
    private static final String MIXIN = "org.nivpisum.bettermapatlases.compat.mixin.BnmMapItemMixin";
    private static final String DESCRIPTOR =
        "(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/levelgen/Heightmap$Types;IILnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;)I";

    @Test void optionalPluginStartsDisabled() {
        assertFalse(new NetherCompatMixinPlugin().shouldApplyMixin("net.minecraft.world.item.MapItem", MIXIN));
    }

    @Test void cachedHeightGuardHasValidStackAndRetainsFallback() throws Exception {
        ClassNode target = targetWithBnmHandler();
        MethodNode handler = target.methods.get(0);
        AbstractInsnNode fallbackStart = handler.instructions.getFirst();
        new NetherCompatMixinPlugin().postApply("net.minecraft.world.item.MapItem", target, MIXIN, null);
        assertSame(fallbackStart, handler.instructions.getLast().getPrevious());
        new Analyzer<>(new BasicVerifier()).analyze(target.name, handler);
        int providerCalls = 0;
        for (AbstractInsnNode instruction : handler.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("sampleHeight")) providerCalls++;
        }
        assertEquals(1, providerCalls);
    }

    @Test void unexpectedTargetCannotSilentlyPatchAnotherHandler() {
        ClassNode target = targetWithBnmHandler();
        target.methods.get(0).visibleAnnotations.clear();
        assertThrows(IllegalStateException.class,
            () -> new NetherCompatMixinPlugin().postApply("net.minecraft.world.item.MapItem", target, MIXIN, null));
    }

    private static ClassNode targetWithBnmHandler() {
        ClassNode target = new ClassNode();
        target.version = Opcodes.V17;
        target.access = Opcodes.ACC_PUBLIC;
        target.name = "net/minecraft/world/item/MapItem";
        target.superName = "java/lang/Object";
        MethodNode handler = new MethodNode(Opcodes.ACC_PUBLIC, "bnmHeight", DESCRIPTOR, null, null);
        AnnotationNode merged = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        merged.values = List.of("mixin", "com.syric.betternethermap.mixin.MixinMapItem");
        handler.visibleAnnotations = new java.util.ArrayList<>(List.of(merged));
        handler.instructions.add(new InsnNode(Opcodes.ICONST_5));
        handler.instructions.add(new InsnNode(Opcodes.IRETURN));
        handler.maxLocals = 8;
        handler.maxStack = 1;
        target.methods.add(handler);
        return target;
    }
}
