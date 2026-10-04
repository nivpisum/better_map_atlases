/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtlasCollectionSectionsTest {
    private record Key(int x, String slice) {}

    @Test void sectionAndExactHitsRetainNativeHashTraversalOrder() {
        Map<Key, Object> source = new HashMap<>();
        for (int i = 0; i < 256; i++) source.put(new Key(i * 128, i % 2 == 0 ? "surface" : "cave"), new Object());
        var index = new AtlasCollectionSections<>(source, Key::slice);
        var expected = source.entrySet().stream().filter(e -> e.getKey().slice().equals("cave"))
            .map(Map.Entry::getValue).toList();
        assertEquals(expected, index.section("cave"));
        List<Object> reverse = new ArrayList<>(expected);
        java.util.Collections.reverse(reverse);
        index.order(reverse);
        assertEquals(expected, reverse);
    }

    @Test void snapshotIsImmutableAndSourceMutationRequiresRebuild() {
        Map<Key, String> source = new HashMap<>();
        source.put(new Key(0, "surface"), "first");
        var index = new AtlasCollectionSections<>(source, Key::slice);
        assertThrows(UnsupportedOperationException.class, () -> index.section("surface").add("other"));
        assertThrows(UnsupportedOperationException.class, () -> index.all().clear());
        source.clear();
        source.put(new Key(0, "cave"), "changed");
        assertEquals(List.of("first"), index.section("surface"));
        var refreshed = new AtlasCollectionSections<>(source, Key::slice);
        assertTrue(refreshed.section("surface").isEmpty());
        assertEquals(List.of("changed"), refreshed.section("cave"));
    }

    @Test void equalValuesUseTheirActualHolderIdentityForOrdering() {
        String first = new String("same");
        String second = new String("same");
        Map<Key, String> source = new java.util.LinkedHashMap<>();
        source.put(new Key(0, "surface"), first);
        source.put(new Key(128, "surface"), second);
        var index = new AtlasCollectionSections<>(source, Key::slice);
        List<String> hits = new ArrayList<>(List.of(second, first));
        index.order(hits);
        assertSame(first, hits.get(0));
        assertSame(second, hits.get(1));
    }
}
