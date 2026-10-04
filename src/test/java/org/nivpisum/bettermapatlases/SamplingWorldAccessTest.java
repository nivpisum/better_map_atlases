/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class SamplingWorldAccessTest {
    @Test void allWorldAccessorsAvoidLoadingAndWorldMutationEvenInColourAndShapeCallbacks() throws Exception {
        for (String name : new String[] {"MapSampler$LoadedWorld", "dynamic/SurveyRecorder$LoadedWorld"}) {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(Path.of("build/classes/java/main/org/nivpisum/bettermapatlases", name + ".class")))
                .accept(node, 0);
            boolean loadedLookup = false;
            for (var method : node.methods) for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                String at = name + "." + method.name + " calls " + call.owner + "." + call.name;
                assertFalse(Set.of("getChunk", "getChunkAt", "getChunkFuture", "getChunkFutureMainThread",
                    "setBlock", "setBlockAndUpdate", "addRegionTicket", "forceChunk").contains(call.name), at);
                if (call.owner.equals("net/minecraft/world/level/Level")
                    || call.owner.equals("net/minecraft/server/level/ServerLevel"))
                    assertFalse(Set.of("getBlockState", "getFluidState", "getBlockEntity").contains(call.name), at);
                if (call.owner.equals("net/minecraft/world/level/chunk/LevelChunk"))
                    assertNotEquals("getBlockEntity", call.name, "Lookup must not create block entities: " + at);
                loadedLookup |= call.name.equals("getChunkNow");
            }
            assertTrue(loadedLookup, name + " must use only already present chunks");
        }
    }
}
