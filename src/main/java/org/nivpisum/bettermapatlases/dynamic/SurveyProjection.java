/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/** Ranks already observed surfaces for a readable cave overview, separately from observation ownership. */
public final class SurveyProjection {
    /** Terrain outside this horizon is not advertised as a route by a remote connecting ramp. */
    public static final int DETACHED_CONTEXT_Y16 = 12 * 16;
    /** Rounded-up crawling body height; short collision slits cannot accommodate any player pose. */
    public static final int MIN_PASSAGE_Y16 = 10;
    private static final int ROUTE_FULL = 32 * 16, ROUTE_LIMIT = 64 * 16, BODY_Y16 = 29;
    private SurveyProjection() {}
    /** A recorded support surface; independent of Minecraft state/texture classes. */
    public record Floor(int page, int column, int x, int z, int floor, int minAir, int maxAir,
                        boolean fluid, boolean upperKnown) {
        public Floor(int page, int column, int x, int z, int floor, int minAir, int maxAir) {
            this(page, column, x, z, floor, minAir, maxAir, false, true);
        }
        @Override public int hashCode() {
            return mix(hash(page, column, floor, minAir, maxAir) ^ mix(x)
                ^ Integer.rotateLeft(mix(z), 16) ^ (fluid ? 0x27d4eb2d : 0) ^ (upperKnown ? 0x165667b1 : 0));
        }
    }
    // Independent avalanche per coordinate avoids the page/column and packed-XZ
    // linear aliases of record/Long hashCode on contiguous survey grids.
    private static int mix(int value) {
        value ^= value >>> 16; value *= 0x7feb352d;
        value ^= value >>> 15; value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }
    private static int hash(int page, int column, int floor, int minAir, int maxAir) {
        return mix(mix(page) ^ Integer.rotateLeft(mix(column), 5) ^ Integer.rotateLeft(mix(floor), 11)
            ^ Integer.rotateLeft(mix(minAir), 17) ^ Integer.rotateLeft(mix(maxAir), 23));
    }
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
        private final int[] membership;
        private final List<Set<Integer>> conflicts = new ArrayList<>();
        private final Long2IntOpenHashMap supports;
        private final int[] nextInWorldColumn, nextInPageColumn;
        private final List<Floor> nodes;
        private final Int2ObjectOpenHashMap<PageNodes> pageNodes = new Int2ObjectOpenHashMap<>();
        private boolean aliasedKeys;
        private final int[] solidHeights;
        private final Map<SelectionKey, Selection> selections = new LinkedHashMap<>(16, .75f, true);
        private long selectionBytes;
        private record SelectionKey(int view, int above, int below, int supported) {}
        private record Edge(int node, int cost) {}

        public Index(Collection<Floor> observations) { this(observations, () -> false); }
        /** Background callers may abandon an obsolete connection's detached work. */
        public Index(Collection<Floor> observations, BooleanSupplier cancelled) {
            nodes = new ArrayList<>(observations.size());
            Set<Floor> distinct = new ObjectOpenHashSet<>(observations.size());
            int checked = 0;
            for (Floor f : observations) {
                if ((checked++ & 1023) == 0) check(cancelled);
                if ((f.fluid || !f.upperKnown || f.maxAir - f.floor >= MIN_PASSAGE_Y16) && distinct.add(f)) nodes.add(f);
            }
            membership = new int[nodes.size()];
            supports = new Long2IntOpenHashMap(Math.max(16, nodes.size() / 2)); supports.defaultReturnValue(-1);
            nextInWorldColumn = new int[nodes.size()]; nextInPageColumn = new int[nodes.size()];
            IntOpenHashSet heights = new IntOpenHashSet();
            int[] parent = new int[nodes.size()];
            int[] size = new int[nodes.size()];
            RootConflicts forbidden = new RootConflicts(nodes.size());
            for (int i = 0; i < nodes.size(); i++) {
                if ((i & 1023) == 0) check(cancelled);
                parent[i] = i; size[i] = 1; Floor f = nodes.get(i);
                PageNodes page = pageNodes.computeIfAbsent(f.page, ignored -> new PageNodes());
                nextInPageColumn[i] = page.heads.put(f.column, i); page.nodes.add(i);
                for (int prior = nextInPageColumn[i]; prior >= 0; prior = nextInPageColumn[prior])
                    if (sameKey(nodes.get(prior), f.floor, f.minAir, f.maxAir)) { aliasedKeys = true; break; }
                if (!f.fluid) heights.add(f.floor);
                nextInWorldColumn[i] = supports.put(xz(f.x, f.z), i);
            }
            solidHeights = heights.toIntArray(); Arrays.sort(solidHeights);
            // Restore original input order within each world column. Edge order
            // matters for constrained unions, so a reverse-linked list is insufficient.
            for (var iterator = supports.long2IntEntrySet().fastIterator(); iterator.hasNext();) {
                var entry = iterator.next(); int at = entry.getIntValue(), previous = -1;
                while (at >= 0) { int next = nextInWorldColumn[at]; nextInWorldColumn[at] = previous; previous = at; at = next; }
                entry.setValue(previous);
            }
            // These surfaces are stacked at the same XZ. Stairs elsewhere must
            // not merge them back into one sheet through an indirect path.
            for (var columns = supports.values().iterator(); columns.hasNext();) for (int i = columns.nextInt(); i >= 0; i = nextInWorldColumn[i]) {
                if ((checked++ & 1023) == 0) check(cancelled);
                Floor left = nodes.get(i);
                for (int j = nextInWorldColumn[i]; j >= 0; j = nextInWorldColumn[j]) {
                    Floor right = nodes.get(j);
                    Floor lower = left.floor < right.floor ? left : right;
                    Floor upper = left.floor < right.floor ? right : left;
                    // Height alone is not evidence of an upper storey. Require a recorded solid roof.
                    if (lower.floor != upper.floor && lower.upperKnown && lower.maxAir <= upper.floor) {
                        forbidden.add(i, j); forbidden.add(j, i);
                    }
                }
            }
            for (int i = 0; i < nodes.size(); i++) {
                if ((i & 1023) == 0) check(cancelled);
                Floor f = nodes.get(i);
                connect(i, f.x, f.z, parent, size, forbidden);
                connect(i, f.x + 1, f.z, parent, size, forbidden);
                connect(i, f.x, f.z + 1, parent, size, forbidden);
            }
            Map<Integer, Integer> numbers = new HashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                if ((i & 1023) == 0) check(cancelled);
                int root = root(parent, i);
                int group = numbers.computeIfAbsent(root, ignored -> {
                    groups.add(new ArrayList<>()); conflicts.add(new TreeSet<>()); return groups.size() - 1;
                });
                Floor floor = nodes.get(i); groups.get(group).add(floor); membership[i] = group;
            }
            for (var entry : numbers.entrySet()) {
                int a = entry.getValue();
                for (int other : forbidden.values(entry.getKey())) {
                    int b = numbers.get(other);
                    if (a == b) throw new IllegalStateException("Stacked floor surfaces merged into one sheet");
                    conflicts.get(a).add(b);
                }
            }
        }
        private static void check(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException(); }
        private static long xz(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
        private static int root(int[] parents, int n) {
            while (parents[n] != n) { parents[n] = parents[parents[n]]; n = parents[n]; }
            return n;
        }
        private void connect(int i, int x, int z, int[] parent, int[] size, RootConflicts forbidden) {
            Floor a = nodes.get(i);
            for (int j = supports.get(xz(x, z)); j >= 0; j = nextInWorldColumn[j]) {
                if (i == j) continue;
                Floor b = nodes.get(j);
                if (!a.fluid && !b.fluid && Math.abs((long) a.floor - b.floor) <= 24
                        && Math.min(a.maxAir, b.maxAir) > Math.max(a.floor, b.floor)) {
                    merge(parent, size, forbidden, i, j);
                }
            }
        }
        private static final class PageNodes {
            final IntArrayList nodes = new IntArrayList();
            final Int2IntOpenHashMap heads = new Int2IntOpenHashMap();
            PageNodes() { heads.defaultReturnValue(-1); }
        }
        private static boolean sameKey(Floor candidate, int floor, int minAir, int maxAir) {
            return candidate.floor == floor && candidate.minAir == minAir && candidate.maxAir == maxAir;
        }
        private int node(int page, int column, int floor, int minAir, int maxAir) {
            PageNodes found = pageNodes.get(page);
            if (found != null) for (int at = found.heads.get(column); at >= 0; at = nextInPageColumn[at])
                if (sameKey(nodes.get(at), floor, minAir, maxAir)) return at;
            return -1;
        }
        private static void merge(int[] parent, int[] size, RootConflicts forbidden, int i, int j) {
            int a = root(parent, i), b = root(parent, j);
            if (a == b || forbidden.contains(a, b)) return;
            if (size[a] > size[b]) { int swap = a; a = b; b = swap; }
            parent[a] = b; size[b] += size[a];
            forbidden.absorb(a, b);
        }
        /** Symmetric constraints between current roots, not every original node.
         * Two large stacked sheets retain one conflict instead of all their cells. */
        private static final class RootConflicts {
            private static final int[] EMPTY = {};
            private final int[] one;
            private final IntOpenHashSet[] many;
            RootConflicts(int nodes) { one = new int[nodes]; Arrays.fill(one, -1); many = new IntOpenHashSet[nodes]; }
            boolean contains(int root, int other) { return many[root] == null ? one[root] == other : many[root].contains(other); }
            void add(int root, int other) {
                if (many[root] != null) { many[root].add(other); return; }
                if (one[root] == other) return;
                if (one[root] < 0) { one[root] = other; return; }
                IntOpenHashSet set = new IntOpenHashSet(4); set.add(one[root]); set.add(other);
                many[root] = set; one[root] = -1;
            }
            void remove(int root, int other) {
                IntOpenHashSet set = many[root];
                if (set == null) { if (one[root] == other) one[root] = -1; return; }
                set.remove(other);
                if (set.size() <= 1) { one[root] = set.isEmpty() ? -1 : set.iterator().nextInt(); many[root] = null; }
            }
            int[] values(int root) { return many[root] != null ? many[root].toIntArray() : one[root] < 0 ? EMPTY : new int[]{one[root]}; }
            void absorb(int from, int into) {
                // Renaming both ends keeps every stored ID a live root. Neither
                // this set nor the destination is modified while iterating it.
                for (int other : values(from)) {
                    remove(other, from); add(other, into); add(into, other);
                }
                one[from] = -1; many[from] = null;
            }
        }
        public Selection select(int view) {
            return select(view, 32 * 16, 32 * 16);
        }
        public Selection select(int view, int above, int below) {
            return select(view, above, below, null);
        }
        private int supportedNode(Support support) {
            if (support == null) return -1;
            int best = -1; long distance = Long.MAX_VALUE;
            for (int node = supports.get(xz(support.x, support.z)); node >= 0; node = nextInWorldColumn[node]) {
                Floor floor = nodes.get(node);
                if (floor.fluid) continue;
                long gap = (long) support.feetY16 - floor.floor;
                if (gap < -1 || gap > 24 || support.feetY16 < floor.minAir - 1L
                        || support.feetY16 >= floor.maxAir) continue;
                if (Math.abs(gap) < distance) { best = node; distance = Math.abs(gap); }
            }
            if (best < 0) return -1;
            Floor found = nodes.get(best); return node(found.page, found.column, found.floor, found.minAir, found.maxAir);
        }
        private boolean needsRoutes(int view) {
            int lower = Arrays.binarySearch(solidHeights, view - 25), upper = Arrays.binarySearch(solidHeights, view + 25);
            if (lower < 0) lower = -lower - 2;
            if (upper < 0) upper = -upper - 1;
            return lower >= 0 && (long) view - solidHeights[lower] < DETACHED_CONTEXT_Y16
                || upper < solidHeights.length && (long) solidHeights[upper] - view < DETACHED_CONTEXT_Y16;
        }
        private int supportIdentity(int view, Support support) {
            int node = supportedNode(support);
            return node < 0 || needsRoutes(view) ? node : membership[node];
        }
        public Selection select(int view, int above, int below, Support support) {
            if (above < 0 || below < 0) throw new IllegalArgumentException("Negative cave context range");
            int supported = supportedNode(support);
            int supportedGroup = supported < 0 ? -1 : membership[supported];
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
                Floor floor = nodes.get(at.node);
                routeNeighbour(floor, floor.x - 1, floor.z, at.cost, view, costs, pending);
                routeNeighbour(floor, floor.x + 1, floor.z, at.cost, view, costs, pending);
                routeNeighbour(floor, floor.x, floor.z - 1, at.cost, view, costs, pending);
                routeNeighbour(floor, floor.x, floor.z + 1, at.cost, view, costs, pending);
            }
            return new RouteCosts(costs);
        }
        /** The same local walking edges, generated only inside the bounded search.
         * Flat books need no route graph and never allocate millions of unused edges. */
        private void routeNeighbour(Floor from, int x, int z, int previousCost, int view,
                Map<Integer, Integer> costs, PriorityQueue<Edge> pending) {
            if (from.fluid) return;
            for (int node = supports.get(xz(x, z)); node >= 0; node = nextInWorldColumn[node]) {
                Floor to = nodes.get(node); long rise = Math.abs((long) from.floor - to.floor);
                if (to.fluid || rise > 16 || Math.min(from.maxAir, to.maxAir) - Math.max(from.floor, to.floor) < BODY_Y16) continue;
                int cost = previousCost + 16 + (int) rise * 2;
                if (Math.abs((long) to.floor - view) < DETACHED_CONTEXT_Y16
                        && cost <= ROUTE_LIMIT && cost < costs.getOrDefault(node, Integer.MAX_VALUE)) {
                    costs.put(node, cost); pending.add(new Edge(node, cost));
                }
            }
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
                    : (supported < 0 ? -1 : index.membership[supported]) == index.supportIdentity(view, support));
        }
        private long bytes() { return 9L * active.length + (routeCosts == null ? 0 : routeCosts.bytes()); }
        public boolean allows(int page, int column, int floor, int minAir, int maxAir) {
            int node = index.node(page, column, floor, minAir, maxAir);
            int group = node < 0 ? -1 : index.membership[node];
            long delta = (long) floor - view;
            return group >= 0 && active[group] && delta <= aboveRange[group] && -delta <= belowRange[group];
        }
        public boolean allows(Floor floor) { return allows(floor.page, floor.column, floor.floor, floor.minAir, floor.maxAir); }
        public int alpha(int page, int column, int floor, int minAir, int maxAir) {
            int node = index.node(page, column, floor, minAir, maxAir);
            return node < 0 ? 0 : alpha(node, floor);
        }
        private int alpha(int node, int floor) {
            int group = index.membership[node];
            if (!active[group]) return 0;
            int heightAlpha = contextAlpha(floor, view, aboveRange[group], belowRange[group]);
            if (index.nodes.get(node).fluid || Math.abs((long) floor - view) <= 24) return heightAlpha;
            // Remote/difficult terrain stays a subdued cue. A route does not reset its cost at a plateau.
            int route = routeCosts == null ? Integer.MAX_VALUE : routeCosts.get(node);
            int affinity = route <= ROUTE_FULL ? 255 : route >= ROUTE_LIMIT ? 160
                : 160 + (ROUTE_LIMIT - route) * 95 / (ROUTE_LIMIT - ROUTE_FULL);
            return heightAlpha * affinity / 255;
        }
        public int alpha(Floor floor) { return alpha(floor.page, floor.column, floor.floor, floor.minAir, floor.maxAir); }
        /** Exact per-page selection result. Contains no reference to this graph. */
        public short[] pageVisibility(int page) {
            Index.PageNodes found = index.pageNodes.get(page);
            if (found == null) return new short[0];
            IntArrayList pageIds = found.nodes;
            short[] result = new short[pageIds.size()];
            for (int i = 0; i < result.length; i++) {
                int node = pageIds.getInt(i); Floor floor = index.nodes.get(node);
                // The public lookup has last-key semantics even for unusual
                // inputs whose fluid/roof/coordinate fields share one Key.
                if (index.aliasedKeys) node = index.node(floor.page, floor.column, floor.floor, floor.minAir, floor.maxAir);
                int group = index.membership[node]; long delta = (long) floor.floor - view;
                boolean allowed = active[group] && delta <= aboveRange[group] && -delta <= belowRange[group];
                result[i] = (short) ((allowed ? 256 : 0) | alpha(node, floor.floor));
            }
            return result;
        }
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
