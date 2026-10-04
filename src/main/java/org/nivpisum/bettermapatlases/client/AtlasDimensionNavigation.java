/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Coordinate-only navigation; the caller supplies the currently viewed center. */
public final class AtlasDimensionNavigation {
    private static final List<Link> DEFAULT_LINKS = List.of(
            new Link("minecraft:overworld", "minecraft:the_nether", 0.125));
    private static final Center ORIGIN = new Center(0, 0);

    private AtlasDimensionNavigation() {}

    public record Center(double x, double z) {}
    public record Link(String from, String to, double scale) {}

    public static List<Link> defaultLinks() { return DEFAULT_LINKS; }

    /** Validation stays outside the record constructor so malformed configuration entries can be ignored. */
    public static boolean isValidLink(Link link) {
        return link != null && validId(link.from()) && validId(link.to())
                && Double.isFinite(link.scale()) && link.scale() > 0;
    }

    public static Center centerForSwitch(String fromDimension, String toDimension, double x, double z) {
        return centerForSwitch(DEFAULT_LINKS, fromDimension, toDimension, x, z);
    }

    /** Selecting the reader's own dimension returns to their live location, even after an unrelated tab. */
    public static Center centerForSwitch(Collection<Link> links, String fromDimension, String toDimension,
                                         double x, double z, String readerDimension, Center readerCenter) {
        if (validId(toDimension) && toDimension.equals(readerDimension) && readerCenter != null
                && Double.isFinite(readerCenter.x()) && Double.isFinite(readerCenter.z())) return readerCenter;
        return centerForSwitch(links, fromDimension, toDimension, x, z);
    }

    /**
     * Only declared links share coordinates. Links also work in reverse, and the shortest
     * valid path composes their scales; collection order breaks ties. No path means origin.
     */
    public static Center centerForSwitch(Collection<Link> links, String fromDimension, String toDimension,
                                         double x, double z) {
        if (!validId(fromDimension) || !validId(toDimension) || !Double.isFinite(x) || !Double.isFinite(z)) return ORIGIN;
        if (fromDimension.equals(toDimension)) return new Center(x, z);
        if (links == null || links.isEmpty()) return ORIGIN;

        Map<String, Double> scales = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        scales.put(fromDimension, 1.0);
        pending.add(fromDimension);
        while (!pending.isEmpty()) {
            String dimension = pending.removeFirst();
            double scale = scales.get(dimension);
            for (Link link : links) {
                if (!isValidLink(link)) continue;
                String next;
                double nextScale;
                if (link.from().equals(dimension)) {
                    next = link.to();
                    nextScale = scale * link.scale();
                } else if (link.to().equals(dimension)) {
                    next = link.from();
                    nextScale = scale / link.scale();
                } else continue;
                if (scales.containsKey(next) || !Double.isFinite(nextScale) || nextScale <= 0) continue;
                if (next.equals(toDimension)) {
                    double convertedX = x * nextScale;
                    double convertedZ = z * nextScale;
                    return Double.isFinite(convertedX) && Double.isFinite(convertedZ)
                            ? new Center(convertedX, convertedZ) : ORIGIN;
                }
                scales.put(next, nextScale);
                pending.addLast(next);
            }
        }
        return ORIGIN;
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank();
    }
}
