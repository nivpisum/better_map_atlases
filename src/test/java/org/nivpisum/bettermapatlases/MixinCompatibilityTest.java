/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MixinCompatibilityTest {
    @Test void mixinsAvoidArrayMethodOwnersThatBreakMixin085ClassMetadataResolution() throws Exception {
        try (var paths = Files.walk(Path.of("build/classes/java/main/org/nivpisum/bettermapatlases"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith("Mixin.class")).toList()) {
                ClassNode node = new ClassNode(); new ClassReader(Files.readAllBytes(path)).accept(node, 0);
                for (var method : node.methods) for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call)
                        assertFalse(call.owner.startsWith("["), node.name + "." + method.name + ": use Arrays.copyOf instead of an array-owned clone call");
                }
            }
        }
    }
}
