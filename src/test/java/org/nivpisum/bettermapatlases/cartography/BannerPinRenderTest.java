/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.cartography;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

final class BannerPinRenderTest {
    @Test void spriteTrianglesFaceTheMapViewerWithTextRenderTypeCulling() throws Exception {
        // Inspect actual emitted vertices without bootstrapping Minecraft or an OpenGL context.
        ClassNode renderer = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of("build/classes/java/main/org/nivpisum/"
                + "bettermapatlases/cartography/client/BannerPinsClient$BannerPinRenderer.class")))
                .accept(renderer, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var quad = renderer.methods.stream().filter(m -> m.name.equals("quad")).findFirst().orElseThrow();
        var vertices = new ArrayList<float[]>();
        for (var instruction : quad.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("vertex")) {
                var y = call.getPrevious().getPrevious(); // z is the final argument
                vertices.add(new float[]{constant(y.getPrevious()), constant(y)});
            }
        }
        assertEquals(4, vertices.size());
        // Map surfaces and Moonlight sprites face -Z before the atlas/held-map transform.
        // Both triangles must agree: reversed winding made all views and bookmarks invisible.
        for (int i = 1; i < 3; i++) {
            float[] a = vertices.get(0), b = vertices.get(i), c = vertices.get(i + 1);
            float normalZ = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
            assertTrue(normalZ < 0, "Banner triangle is back-facing or degenerate: " + normalZ);
        }
    }

    private static float constant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Number number)
            return number.floatValue();
        return switch (instruction.getOpcode()) {
            case Opcodes.FCONST_0 -> 0;
            case Opcodes.FCONST_1 -> 1;
            case Opcodes.FCONST_2 -> 2;
            default -> throw new AssertionError("Expected sprite coordinate constant");
        };
    }
}
