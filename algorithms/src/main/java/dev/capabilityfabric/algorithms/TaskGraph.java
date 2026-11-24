package dev.capabilityfabric.algorithms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dependency graph over task ids. An edge {@code a -> b} means "a depends on b" (b must finish first).
 * Every method is deterministic: ties break by insertion order of the input map.
 */
public final class TaskGraph {

    private final Map<String, List<String>> dependsOn;
    private final Map<String, Integer> order;

    /** @param dependsOn for each node, the nodes it depends on; targets that are not keys become nodes too */
    public TaskGraph(Map<String, List<String>> dependsOn) {
        this.dependsOn = new LinkedHashMap<>();
        dependsOn.forEach((k, v) -> this.dependsOn.put(k, v == null ? List.of() : List.copyOf(v)));
        // Unknown dependency targets become nodes so that callers can detect dangling edges.
        for (List<String> targets : List.copyOf(this.dependsOn.values())) {
            for (String t : targets) {
                this.dependsOn.putIfAbsent(t, List.of());
            }
        }
        this.order = new HashMap<>();
        int i = 0;
        for (String node : this.dependsOn.keySet()) {
            order.put(node, i++);
        }
    }

    /** Every node, in insertion order, including dependency targets that were not keys. */
    public Set<String> nodes() {
        return Collections.unmodifiableSet(dependsOn.keySet());
    }

    public List<String> dependenciesOf(String node) {
        return dependsOn.getOrDefault(node, List.of());
    }

    /** Returns one cycle (as a list of nodes, first node repeated at the end) if the graph has any. */
    public Optional<List<String>> findCycle() {
        Map<String, Integer> state = new HashMap<>(); // 0 new, 1 on stack, 2 done
        Deque<String> stack = new ArrayDeque<>();
        for (String start : dependsOn.keySet()) {
            Optional<List<String>> cycle = dfs(start, state, stack);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private Optional<List<String>> dfs(String node, Map<String, Integer> state, Deque<String> stack) {
        int s = state.getOrDefault(node, 0);
        if (s == 2) {
            return Optional.empty();
        }
        if (s == 1) {
            List<String> cycle = new ArrayList<>();
            boolean collecting = false;
            for (String n : (Iterable<String>) stack::descendingIterator) {
                if (n.equals(node)) {
                    collecting = true;
                }
                if (collecting) {
                    cycle.add(n);
                }
            }
            cycle.add(node);
            return Optional.of(cycle);
        }
        state.put(node, 1);
        stack.push(node);
        for (String dep : dependenciesOf(node)) {
            Optional<List<String>> cycle = dfs(dep, state, stack);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        stack.pop();
        state.put(node, 2);
        return Optional.empty();
    }

    public boolean isAcyclic() {
        return findCycle().isEmpty();
    }

    /** Dependencies first. Throws if the graph has a cycle. */
    public List<String> topologicalOrder() {
        Map<String, Integer> remaining = new HashMap<>();
        Map<String, List<String>> dependents = dependents();
        dependsOn.forEach((node, deps) -> remaining.put(node, new HashSet<>(deps).size()));
        TreeSet<String> ready = new TreeSet<>((a, b) -> Integer.compare(order.get(a), order.get(b)));
        remaining.forEach((node, count) -> {
            if (count == 0) {
                ready.add(node);
            }
        });
        List<String> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            String next = ready.pollFirst();
            result.add(next);
            for (String dependent : dependents.getOrDefault(next, List.of())) {
                int left = remaining.merge(dependent, -1, Integer::sum);
                if (left == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (result.size() != dependsOn.size()) {
            throw new IllegalStateException("dependency cycle: " + findCycle().orElse(List.of()));
        }
        return result;
    }

    /** Reverse edges: for each node, the nodes that depend on it directly. */
    public Map<String, List<String>> dependents() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        dependsOn.keySet().forEach(n -> result.put(n, new ArrayList<>()));
        dependsOn.forEach((node, deps) -> new LinkedHashSet<>(deps).forEach(d -> result.get(d).add(node)));
        return result;
    }

    /** Everything {@code node} waits on, directly or through other nodes. */
    public Set<String> transitiveDependencies(String node) {
        return reach(node, dependsOn);
    }

    /** Everything that waits on {@code node}, directly or through other nodes. */
    public Set<String> transitiveDependents(String node) {
        return reach(node, dependents());
    }

    private static Set<String> reach(String start, Map<String, List<String>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>(edges.getOrDefault(start, List.of()));
        while (!frontier.isEmpty()) {
            String n = frontier.pop();
            if (seen.add(n)) {
                frontier.addAll(edges.getOrDefault(n, List.of()));
            }
        }
        return seen;
    }

    /** Length of the longest dependency chain below each node (0 for nodes without dependencies). */
    public Map<String, Integer> depth() {
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (String node : topologicalOrder()) {
            int d = 0;
            for (String dep : dependenciesOf(node)) {
                d = Math.max(d, depth.get(dep) + 1);
            }
            depth.put(node, d);
        }
        return depth;
    }

    /**
     * Longest dependency chain by total {@code weight} (the caller's weights, for example effort hours of open tasks
     * and 0 for done ones); nodes missing from {@code weight} count as 0. Throws if the graph has a cycle.
     */
    public CriticalPath criticalPath(Map<String, Double> weight) {
        Map<String, Double> finish = new HashMap<>();
        Map<String, String> via = new HashMap<>();
        for (String node : topologicalOrder()) {
            double best = 0;
            String bestDep = null;
            for (String dep : dependenciesOf(node)) {
                double f = finish.get(dep);
                if (bestDep == null || f > best) {
                    best = f;
                    bestDep = dep;
                }
            }
            finish.put(node, best + weight.getOrDefault(node, 0.0));
            via.put(node, bestDep);
        }
        String end = null;
        double total = 0;
        for (String node : dependsOn.keySet()) {
            double f = finish.getOrDefault(node, 0.0);
            if (end == null || f > total) {
                end = node;
                total = f;
            }
        }
        List<String> path = new ArrayList<>();
        for (String n = end; n != null; n = via.get(n)) {
            path.add(n);
        }
        Collections.reverse(path);
        return new CriticalPath(path, total);
    }

    /** The chain's nodes, first dependency first, and its total weight. */
    public record CriticalPath(List<String> nodes, double totalHours) {
    }
}
