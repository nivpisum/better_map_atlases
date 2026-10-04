/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Immutable derived lists; order remains the authoritative map's iteration order. */
public final class AtlasCollectionSections<K, S, V> {
    private final Map<S, List<V>> sections = new HashMap<>();
    private final IdentityHashMap<V, Integer> order = new IdentityHashMap<>();
    private final List<V> all;

    public AtlasCollectionSections(Map<K, V> source, Function<K, S> section) {
        List<V> values = new ArrayList<>(source.size());
        for (var entry : source.entrySet()) {
            V value = entry.getValue();
            order.put(value, values.size());
            values.add(value);
            sections.computeIfAbsent(section.apply(entry.getKey()), ignored -> new ArrayList<>()).add(value);
        }
        all = List.copyOf(values);
        sections.replaceAll((ignored, valuesInSection) -> List.copyOf(valuesInSection));
    }

    public List<V> section(S section) { return sections.getOrDefault(section, List.of()); }
    public List<V> all() { return all; }
    public int size() { return all.size(); }

    /** Sort only the handful of coordinate hits, never the complete book. */
    public void order(List<V> hits) {
        hits.sort(Comparator.comparingInt(value -> order.getOrDefault(value, Integer.MAX_VALUE)));
    }
}
