/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.*;

/** Ranks already observed surfaces for a readable cave overview, separately from observation ownership. */
final class LegacySurveyProjection {
    /** Terrain outside this horizon is not advertised as a route by a remote connecting ramp. */
    public static final int DETACHED_CONTEXT_Y16 = 12 * 16;
    /** Rounded-up crawling body height; short collision slits cannot accommodate any player pose. */
    public static final int MIN_PASSAGE_Y16 = 10;
    private static final int ROUTE_FULL = 32 * 16, ROUTE_LIMIT = 64 * 16, BODY_Y16 = 29;
    private LegacySurveyProjection() {}
    /** A recorded support surface; independent of Minecraft state/texture classes. */
    public record Floor(int page, int column, int x, int z, int floor, int minAir, int maxAir,
                        boolean fluid, boolean upperKnown) {
        public Floor(int page, int column, int x, int z, int floor, int minAir, int maxAir) {
            this(page, column, x, z, floor, minAir, maxAir, false, true);
        }
        private Key key() { return new Key(page, column, floor, minAir, maxAir); }
    }
    private record Key(int page, int column, int floor, int minAir, int maxAir) {}
    /** Actual reader position, used only while following that reader in the current dimension. */
    public record Support(int x, int z, int feetY16) {}
    /** Display context depends on the chosen height, not on whether a HUD or history UI requested it. */
    public static Support readerSupport(int viewY16, int liveY16, Support reader) {
        return reader != null && viewY16 == liveY16 && Math.abs((long) reader.feetY16 - viewY16) <= 24
            ? reader : null;
    }

    /** Walkable floor continuity, not flood-fill of all air connected by a vertical shaft. */
    public static final class Index {
        private final List<List<Floor>> groups = new ArrayList<>();
        private final Map<Key, Integer> membership = new HashMap<>();
        private final List<Set<Integer>> conflicts = new ArrayList<>();
        private final Map<Long, List<Floor>> supports = new HashMap<>();
        private final List<Floor> nodes;
        private final Map<Key, Integer> nodeIds = new HashMap<>();
        private final List<List<Edge>> routes = new ArrayList<>();
        private final NavigableSet<Integer> solidHeights = new TreeSet<>();
        private final Map<SelectionKey, Selection> selections = new LinkedHashMap<>(16, .75f, true);
        private long selectionBytes;
        private record SelectionKey(int view, int above, int below, int supported) {}
        private record Edge(int node, int cost) {}

        public Index(Collection<Floor> observations) {
            nodes = new ArrayList<>(new LinkedHashSet<>(observations.stream()
                .filter(f -> f.fluid || !f.upperKnown || f.maxAir - f.floor >= MIN_PASSAGE_Y16).toList()));
            int[] parent = new int[nodes.size()];
            int[] size = new int[nodes.size()];
            List<Set<Integer>> forbidden = new ArrayList<>();
            List<int[]> overlapPairs = new ArrayList<>();
            Map<Long, List<Integer>> columns = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                parent[i] = i; size[i] = 1; forbidden.add(new HashSet<>()); Floor f = nodes.get(i);
                nodeIds.put(f.key(), i); routes.add(new ArrayList<>());
                if (!f.fluid) solidHeights.add(f.floor);
                columns.computeIfAbsent(xz(f.x, f.z), ignored -> new ArrayList<>()).add(i);
                supports.computeIfAbsent(xz(f.x, f.z), ignored -> new ArrayList<>()).add(f);
            }
            // These surfaces are stacked at the same XZ. Stairs elsewhere must
            // not merge them back into one sheet through an indirect path.
            for (List<Integer> at : columns.values()) for (int a = 0; a < at.size(); a++) {
                int i = at.get(a); Floor left = nodes.get(i);
                for (int b = a + 1; b < at.size(); b++) {
                    int j = at.get(b); Floor right = nodes.get(j);
                    Floor lower = left.floor < right.floor ? left : right;
                    Floor upper = left.floor < right.floor ? right : left;
                    // Height alone is not evidence of an upper storey. Require a recorded solid roof.
                    if (lower.floor != upper.floor && lower.upperKnown && lower.maxAir <= upper.floor) {
                        forbidden.get(i).add(j); forbidden.get(j).add(i); overlapPairs.add(new int[]{i,j});
                    }
                }
            }
            for (int i = 0; i < nodes.size(); i++) {
                Floor f = nodes.get(i);
                connect(i, f.x, f.z, nodes, columns, parent, size, forbidden);
                connect(i, f.x + 1, f.z, nodes, columns, parent, size, forbidden);
                connect(i, f.x, f.z + 1, nodes, columns, parent, size, forbidden);
                route(i, f.x + 1, f.z, columns);
                route(i, f.x, f.z + 1, columns);
            }
            Map<Integer, Integer> numbers = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                int root = root(parent, i);
                int group = numbers.computeIfAbsent(root, ignored -> {
                    groups.add(new ArrayList<>()); conflicts.add(new TreeSet<>()); return groups.size() - 1;
                });
                Floor floor = nodes.get(i); groups.get(group).add(floor); membership.put(floor.key(), group);
            }
            for (int[] pair : overlapPairs) {
                int a = numbers.get(root(parent, pair[0])), b = numbers.get(root(parent, pair[1]));
                if (a == b) throw new IllegalStateException("Stacked floor surfaces merged into one sheet");
                conflicts.get(a).add(b); conflicts.get(b).add(a);
            }
        }
        private static long xz(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
        private static int root(int[] parents, int n) {
            while (parents[n] != n) { parents[n] = parents[parents[n]]; n = parents[n]; }
            return n;
        }
        private static void connect(int i, int x, int z, List<Floor> nodes, Map<Long, List<Integer>> columns,
                                    int[] parent, int[] size, List<Set<Integer>> forbidden) {
            List<Integer> adjacent = columns.get(xz(x, z));
            if (adjacent == null) return;
            Floor a = nodes.get(i);
            for (int j : adjacent) {
                if (i == j) continue;
                Floor b = nodes.get(j);
                if (!a.fluid && !b.fluid && Math.abs((long) a.floor - b.floor) <= 24
                        && Math.min(a.maxAir, b.maxAir) > Math.max(a.floor, b.floor)) {
                    merge(parent, size, forbidden, i, j);
                }
            }
        }
        /** Plausible local steps only. Neither a cliff nor liquid joins the walking graph. */
        private void route(int i, int x, int z, Map<Long, List<Integer>> columns) {
            Floor a = nodes.get(i);
            if (a.fluid) return;
            for (int j : columns.getOrDefault(xz(x, z), List.of())) {
                Floor b = nodes.get(j); long rise = Math.abs((long) a.floor - b.floor);
                if (b.fluid || rise > 16 || Math.min(a.maxAir, b.maxAir) - Math.max(a.floor, b.floor) < BODY_Y16) continue;
                int cost = 16 + (int) rise * 2;
                routes.get(i).add(new Edge(j, cost)); routes.get(j).add(new Edge(i, cost));
            }
        }
        private static void merge(int[] parent, int[] size, List<Set<Integer>> forbidden, int i, int j) {
            int a = root(parent, i), b = root(parent, j);
            if (a == b) return;
            if (forbidden.get(a).size() > forbidden.get(b).size()) { int swap = a; a = b; b = swap; }
            for (int opponent : forbidden.get(a)) if (root(parent, opponent) == b) return;
            if (size[a] > size[b]) { int swap = a; a = b; b = swap; }
            parent[a] = b; size[b] += size[a];
            Set<Integer> small = forbidden.get(a), large = forbidden.get(b);
            if (small.size() > large.size()) { Set<Integer> swap = small; small = large; large = swap; }
            large.addAll(small); forbidden.set(b, large); forbidden.set(a, Set.of());
        }
        public Selection select(int view) {
            return select(view, 32 * 16, 32 * 16);
        }
        public Selection select(int view, int above, int below) {
            return select(view, above, below, null);
        }
        private int supportedNode(Support support) {
            if (support == null) return -1;
            Floor best = null; long distance = Long.MAX_VALUE;
            for (Floor floor : supports.getOrDefault(xz(support.x, support.z), List.of())) {
                if (floor.fluid) continue;
                long gap = (long) support.feetY16 - floor.floor;
                if (gap < -1 || gap > 24 || support.feetY16 < floor.minAir - 1L
                        || support.feetY16 >= floor.maxAir) continue;
                if (Math.abs(gap) < distance) { best = floor; distance = Math.abs(gap); }
            }
            return best == null ? -1 : nodeIds.get(best.key());
        }
        private boolean needsRoutes(int view) {
            Integer lower = solidHeights.floor(view - 25), upper = solidHeights.ceiling(view + 25);
            return lower != null && (long) view - lower < DETACHED_CONTEXT_Y16
                || upper != null && (long) upper - view < DETACHED_CONTEXT_Y16;
        }
        private int supportIdentity(int view, Support support) {
            int node = supportedNode(support);
            return node < 0 || needsRoutes(view) ? node : membership.get(nodes.get(node).key());
        }
        public Selection select(int view, int above, int below, Support support) {
            if (above < 0 || below < 0) throw new IllegalArgumentException("Negative cave context range");
            int supported = supportedNode(support);
            int supportedGroup = supported < 0 ? -1 : membership.get(nodes.get(supported).key());
            SelectionKey key = new SelectionKey(view, above, below, supportIdentity(view, support));
            Selection cached = selections.get(key);
            if (cached != null) return cached;
            boolean[] active = new boolean[groups.size()];
            boolean[] visited = new boolean[groups.size()];
            Score[] scores = new Score[groups.size()];
            int[] aboveRange = new int[groups.size()], belowRange = new int[groups.size()];
            for (int i = 0; i < groups.size(); i++) {
                Floor first = groups.get(i).get(0);
                // An open liquid surface is a hazard/background, never a promise of walkable ground.
                boolean openFluid = first.fluid && (!first.upperKnown || first.maxAir - first.floor >= DETACHED_CONTEXT_Y16);
                aboveRange[i] = openFluid ? above : Math.min(above, DETACHED_CONTEXT_Y16);
                belowRange[i] = openFluid ? below : Math.min(below, DETACHED_CONTEXT_Y16);
                scores[i] = score(groups.get(i), view, aboveRange[i], belowRange[i]);
            }
            for (int start = 0; start < groups.size(); start++) {
                if (visited[start]) continue;
                if (conflicts.get(start).isEmpty()) { visited[start] = true; active[start] = scores[start].count > 0; continue; }
                List<Integer> stack = new ArrayList<>(); ArrayDeque<Integer> pending = new ArrayDeque<>();
                pending.add(start); visited[start] = true;
                while (!pending.isEmpty()) {
                    int at = pending.removeFirst(); stack.add(at);
                    for (int next : conflicts.get(at)) if (!visited[next]) { visited[next] = true; pending.add(next); }
                }
                // A remote majority must not erase the recorded sheet under the reader's feet.
                stack.sort((a,b) -> {
                    int order = Boolean.compare(b == supportedGroup, a == supportedGroup);
                    if (order == 0) order = scores[a].compareTo(scores[b]);
                    return order != 0 ? order : Integer.compare(a,b);
                });
                int winner = stack.get(0);
                if (scores[winner].count == 0) continue;
                int anchor = anchor(groups.get(winner), view, aboveRange[winner], belowRange[winner]);
                for (int sheet : stack) {
                    if (scores[sheet].count == 0 || (sheet != winner
                            && Math.abs((long) anchor(groups.get(sheet), view, aboveRange[sheet], belowRange[sheet]) - anchor) > 24)) continue;
                    boolean blocked = false;
                    for (int opponent : conflicts.get(sheet)) if (active[opponent]) { blocked = true; break; }
                    if (!blocked) active[sheet] = true;
                }
            }
            Selection result = new Selection(this, active, view, above, below, supported, aboveRange, belowRange,
                needsRoutes(view) ? routeDistances(supported, view) : null);
            selections.put(key, result);
            selectionBytes += result.bytes();
            while (selections.size() > 1 && (selections.size() > 64 || selectionBytes > 8 * 1024 * 1024)) {
                var oldest = selections.entrySet().iterator(); selectionBytes -= oldest.next().getValue().bytes(); oldest.remove();
            }
            return result;
        }
        private RouteCosts routeDistances(int start, int view) {
            if (start < 0) return null;
            Map<Integer,Integer> costs = new HashMap<>(); costs.put(start, 0);
            PriorityQueue<Edge> pending = new PriorityQueue<>(Comparator.comparingInt(Edge::cost));
            pending.add(new Edge(start, 0));
            while (!pending.isEmpty()) {
                Edge at = pending.remove(); if (at.cost != costs.getOrDefault(at.node, Integer.MAX_VALUE)) continue;
                for (Edge next : routes.get(at.node)) {
                    int cost = at.cost + next.cost;
                    if (Math.abs((long) nodes.get(next.node).floor - view) < DETACHED_CONTEXT_Y16
                            && cost <= ROUTE_LIMIT && cost < costs.getOrDefault(next.node, Integer.MAX_VALUE)) {
                        costs.put(next.node, cost); pending.add(new Edge(next.node, cost));
                    }
                }
            }
            return new RouteCosts(costs);
        }
        private static boolean eligible(Floor f, int view, int above, int below) {
            long delta = (long) f.floor - view; return delta <= above && -delta <= below;
        }
        private static Score score(List<Floor> group, int view, int above, int below) {
            int count = 0, inside = 0, close = 0; long anyDistance = Long.MAX_VALUE, insideDistance = Long.MAX_VALUE;
            for (Floor f : group) if (eligible(f, view, above, below)) {
                count++; long distance = Math.abs((long) f.floor - view); anyDistance = Math.min(anyDistance, distance);
                if (f.minAir <= view && view < f.maxAir) {
                    inside++; insideDistance = Math.min(insideDistance, distance);
                    if (distance <= 24) close++;
                }
            }
            long distance = inside > 0 ? insideDistance : anyDistance;
            // Treat adjacent support heights together. A single stair tread must
            // not defeat the many supports of the floor the reader is using.
            return new Score(count, inside > 0 ? 0 : 1, distance <= 24 ? 0 : distance, close);
        }
        private record Score(int count, int air, long distance, int close) implements Comparable<Score> {
            @Override public int compareTo(Score other) {
                if (count == 0 || other.count == 0) return Boolean.compare(count == 0, other.count == 0);
                int result = Integer.compare(air, other.air);
                if (result == 0) result = Long.compare(distance, other.distance);
                if (result == 0) result = Long.compare((long) other.close * count, (long) close * other.count);
                if (result == 0) result = Integer.compare(other.close, close);
                return result;
            }
        }
        private static int anchor(List<Floor> group, int view, int above, int below) {
            TreeMap<Integer,Integer> counts = new TreeMap<>();
            for (Floor f : group) if (eligible(f, view, above, below)) counts.merge(f.floor, 1, Integer::sum);
            int best = counts.firstKey(), bestCount = -1; long bestBucket = Long.MAX_VALUE, bestDistance = Long.MAX_VALUE;
            for (int h : counts.keySet()) {
                long distance = Math.abs((long) h - view), bucket = distance / 32;
                int nearby = 0;
                for (int n : counts.subMap(h - 16, true, h + 16, true).values()) nearby += n;
                if (bucket < bestBucket || (bucket == bestBucket && (nearby > bestCount
                        || (nearby == bestCount && distance < bestDistance)))) {
                    best = h; bestBucket = bucket; bestCount = nearby; bestDistance = distance;
                }
            }
            return best;
        }
    }
    /** Compact reached-node cache; sparse books never allocate a distance array for every recorded height. */
    private static final class RouteCosts {
        private final int[] nodes, costs;
        private RouteCosts(Map<Integer,Integer> reached) {
            nodes = reached.keySet().stream().mapToInt(Integer::intValue).sorted().toArray(); costs = new int[nodes.length];
            for (int i = 0; i < nodes.length; i++) costs[i] = reached.get(nodes[i]);
        }
        int get(int node) { int at = Arrays.binarySearch(nodes, node); return at < 0 ? Integer.MAX_VALUE : costs[at]; }
        long bytes() { return 8L * nodes.length; }
    }
    public static final class Selection {
        private final Index index;
        private final boolean[] active;
        private final int view, above, below, supported;
        private final int[] aboveRange, belowRange;
        private final RouteCosts routeCosts;
        private Selection(Index index, boolean[] active, int view, int above, int below, int supported,
                          int[] aboveRange, int[] belowRange, RouteCosts routeCosts) {
            this.index = index; this.active = active; this.view = view;
            this.above = above; this.below = below; this.supported = supported;
            this.aboveRange = aboveRange; this.belowRange = belowRange;
            this.routeCosts = routeCosts;
        }
        /** Each actual support column has its own bounded route affinity, including at an unchanged Y. */
        public boolean matches(int view, int above, int below, Support support) {
            return this.view == view && this.above == above && this.below == below
                && (index.needsRoutes(view) ? supported == index.supportedNode(support)
                    : (supported < 0 ? -1 : index.membership.get(index.nodes.get(supported).key())) == index.supportIdentity(view, support));
        }
        private long bytes() { return 9L * active.length + (routeCosts == null ? 0 : routeCosts.bytes()); }
        public boolean allows(int page, int column, int floor, int minAir, int maxAir) {
            Integer group = index.membership.get(new Key(page, column, floor, minAir, maxAir));
            long delta = (long) floor - view;
            return group != null && active[group] && delta <= aboveRange[group] && -delta <= belowRange[group];
        }
        public boolean allows(Floor floor) { return allows(floor.page, floor.column, floor.floor, floor.minAir, floor.maxAir); }
        public int alpha(int page, int column, int floor, int minAir, int maxAir) {
            Integer group = index.membership.get(new Key(page, column, floor, minAir, maxAir));
            if (group == null || !active[group]) return 0;
            int heightAlpha = contextAlpha(floor, view, aboveRange[group], belowRange[group]);
            Integer node = index.nodeIds.get(new Key(page, column, floor, minAir, maxAir));
            if (node == null || index.nodes.get(node).fluid || Math.abs((long) floor - view) <= 24) return heightAlpha;
            // Remote/difficult terrain stays a subdued cue. A route does not reset its cost at a plateau.
            int route = routeCosts == null ? Integer.MAX_VALUE : routeCosts.get(node);
            int affinity = route <= ROUTE_FULL ? 255 : route >= ROUTE_LIMIT ? 160
                : 160 + (ROUTE_LIMIT - route) * 95 / (ROUTE_LIMIT - ROUTE_FULL);
            return heightAlpha * affinity / 255;
        }
        public int alpha(Floor floor) { return alpha(floor.page, floor.column, floor.floor, floor.minAir, floor.maxAir); }
    }
    public static long rank(int floor, int minAir, int maxAir, int view,
                            int above, int below, int observedMin, int observedMax) {
        long delta = (long) floor - view;
        if (delta > above || -delta > below) return Long.MAX_VALUE;
        // Prefer the space intersecting the chosen height. Nearby known slopes remain context instead of disappearing.
        long group = minAir <= view && view < maxAir ? 0 : 1;
        long witnessDistance = view < observedMin ? (long) observedMin - view
            : view >= observedMax ? (long) view - observedMax + 1 : 0;
        return group * 1_000_000_000L + Math.abs(delta) * 65536L + Math.min(65535, witnessDistance);
    }
    /** Fade only the outer quarter of the navigation context, avoiding whole
     * plateaus popping from opaque to absent after a one-block height change. */
    public static int contextAlpha(int floor, int view, int above, int below) {
        long delta = (long) floor - view, distance = Math.abs(delta);
        int range = delta >= 0 ? above : below;
        if (distance >= range) return distance == 0 ? 255 : 0;
        int tail = Math.max(16, Math.min(128, range / 4));
        if (distance <= range - tail) return 255;
        return (int) Math.max(0, Math.min(255, (range - distance) * 255 / tail));
    }
}
