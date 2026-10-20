package dev.forge.lab.concurrency;

import dev.forge.lab.concurrency.ControlledExecution.DecisionPoint;
import dev.forge.lab.concurrency.ControlledExecution.Execution;
import dev.forge.lab.concurrency.ControlledExecution.NamedTask;
import dev.forge.lab.concurrency.ControlledExecution.Outcome;
import dev.forge.lab.concurrency.ControlledExecution.Step;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Explicit-state liveness checking of a small concurrent program whose threads loop forever.
 *
 * <p>{@link InterleavingExplorer} checks <em>safety</em> on terminating programs: it enumerates
 * schedules and looks at final states. Livelock and starvation are properties of <em>infinite</em>
 * executions, which a step bound cannot classify: a schedule that runs out of steps may be a true
 * livelock, or merely an unfair scheduler that never ran the thread that would have made progress.
 * This explorer instead builds the program's reachable <b>state graph</b> and searches it for
 * cycles.
 *
 * <h2>State</h2>
 * A state is the scenario's snapshot of shared memory (plus any thread-local values that influence
 * a thread's future, which scenarios store in their state object) together with each thread's next
 * action and the lock holders. Edges are scheduling steps. Each execution replays a known path from
 * the initial state (stateless replay on fresh threads, as in {@link InterleavingExplorer}) and then
 * extends the graph with untried steps until it reaches a state whose steps are all known. If
 * replaying a known path ever reaches a different state, the program is not a function of its
 * schedule and the run reports an error instead of a verdict.
 *
 * <h2>Liveness</h2>
 * Threads mark progress with {@link Probe#progress(String)}. A <b>livelock</b> is a reachable cycle
 * in which nobody makes progress; <b>starvation</b> of thread T is a reachable cycle in which T makes
 * no progress. Only <b>weakly fair</b> cycles count: every thread either takes a step somewhere in the
 * cycle or is blocked somewhere in it — otherwise the "livelock" is just a scheduler that refused to
 * run someone. Witnesses are found as strongly connected components of the graph restricted to the
 * relevant non-progress edges; any cycle found is real (its edges were executed), so witnesses are
 * sound even when the budget ran out, while the absence of one is only claimed for a complete graph.
 *
 * <p>Same granularity and memory-model limits as {@link ControlledExecution}.
 */
public final class LivenessExplorer {

    private LivenessExplorer() {
    }

    /** A path from the initial state to a cycle, and the cycle itself. */
    public record Witness(List<Step> prefix, List<Step> cycle) {
    }

    public record Report(
            List<String> threads,
            int states,
            int edges,
            int executions,
            boolean complete,
            int maxStates,
            int safetyViolations,
            String firstSafetyViolation,
            List<Step> safetyTrace,
            int deadlocks,
            List<Step> deadlockTrace,
            Witness livelock,
            Map<String, Witness> starvation,
            String error,
            long elapsedNanos) {

        public boolean livelockFree() {
            return livelock == null;
        }

        public boolean starvationFree() {
            return starvation.isEmpty();
        }
    }

    private static final class Node {
        final int id;
        final String key;
        final List<Integer> enabled;
        final boolean allFinished;
        final List<Boolean> finished;
        /** thread index → edge */
        final Map<Integer, Edge> out = new LinkedHashMap<>();
        /** shortest known path from the root: parent edge */
        Edge parent;
        int depth;
        String violation;

        Node(int id, String key, DecisionPoint p) {
            this.id = id;
            this.key = key;
            this.enabled = p.enabled();
            this.allFinished = p.pending().stream().allMatch(x -> x == null);
            List<Boolean> f = new ArrayList<>();
            p.pending().forEach(x -> f.add(x == null));
            this.finished = List.copyOf(f);
        }

        Integer unexplored() {
            for (Integer t : enabled) {
                if (!out.containsKey(t)) {
                    return t;
                }
            }
            return null;
        }
    }

    private static final class Edge {
        private final Node from;
        private final int thread;
        private final Node to;
        private final boolean[] progressed;
        private final int decision;
        private Step step;   // attached once the execution that discovered the edge has finished

        Edge(Node from, int thread, Node to, boolean[] progressed, int decision) {
            this.from = from;
            this.thread = thread;
            this.to = to;
            this.progressed = progressed;
            this.decision = decision;
        }

        Node from() { return from; }
        int thread() { return thread; }
        Node to() { return to; }
        boolean[] progressed() { return progressed; }
        Step step() { return step; }
    }

    /**
     * @param freshState creates fresh shared state for each execution
     * @param tasks      the looping worker tasks over that state
     * @param snapshot   canonical text of everything in the state that affects the future
     * @param safety     {@code null} when the state is acceptable, else a description of the violation
     * @param maxStates  budget: stop extending the graph after this many states
     */
    public static <S> Report explore(Supplier<S> freshState,
                                     Function<S, List<NamedTask>> tasks,
                                     Function<S, String> snapshot,
                                     Function<S, String> safety,
                                     int maxStates,
                                     Runnable cancellationCheck) {
        long start = System.nanoTime();
        Map<String, Node> byKey = new HashMap<>();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<String> threadNames = new ArrayList<>();
        int executions = 0;
        int safetyViolations = 0;
        String firstSafety = null;
        List<Step> safetyTrace = null;
        int deadlocks = 0;
        List<Step> deadlockTrace = null;
        String error = null;
        boolean complete = false;
        Node root = null;
        ArrayDeque<Node> frontier = new ArrayDeque<>();   // nodes that may still have unexplored steps, oldest first

        while (true) {
            cancellationCheck.run();
            List<Integer> route = new ArrayList<>();
            List<Node> expected = new ArrayList<>();
            if (root != null) {
                while (!frontier.isEmpty() && frontier.peek().unexplored() == null) {
                    frontier.poll();
                }
                Node target = frontier.peek();
                if (target == null) {
                    complete = true;
                    break;
                }
                if (nodes.size() >= maxStates) {
                    break;
                }
                for (Node n = target; n.parent != null; n = n.parent.from()) {
                    route.addFirst(n.parent.thread());
                    expected.addFirst(n);
                }
            }

            S state = freshState.get();
            List<NamedTask> named = tasks.apply(state);
            if (threadNames.isEmpty()) {
                named.forEach(t -> threadNames.add(t.name()));
            }
            final Node knownRoot = root;
            final int budget = maxStates;
            // per-execution bookkeeping, filled by the chooser
            List<Edge> newEdges = new ArrayList<>();
            List<Node> newNodes = new ArrayList<>();
            List<String> problems = new ArrayList<>();

            ControlledExecution.Chooser chooser = new ControlledExecution.Chooser() {
                Node current;
                List<Integer> progressBefore;
                int chosenThread;
                int choices;

                @Override
                public void observe(DecisionPoint p) {
                    String key = key(snapshot.apply(state), p);
                    Node node = byKey.get(key);
                    if (node == null) {
                        node = new Node(nodes.size(), key, p);
                        node.violation = safety == null ? null : safety.apply(state);   // the state is current now
                        nodes.add(node);
                        byKey.put(key, node);
                        newNodes.add(node);
                        frontier.add(node);
                    }
                    if (current == null) {
                        if (knownRoot != null && node != knownRoot) {
                            problems.add("non-deterministic replay: the initial state differs between executions");
                        }
                    } else {
                        // record the edge now, so the rest of this execution already knows it
                        Edge known = current.out.get(chosenThread);
                        if (known != null) {
                            if (known.to() != node) {
                                problems.add("non-deterministic program: the same step from the same state led to different states");
                            }
                        } else {
                            boolean[] progressed = new boolean[p.progress().size()];
                            for (int i = 0; i < progressed.length; i++) {
                                progressed[i] = p.progress().get(i) > progressBefore.get(i);
                            }
                            Edge e = new Edge(current, chosenThread, node, progressed, p.depth() - 1);
                            current.out.put(chosenThread, e);
                            edges.add(e);
                            newEdges.add(e);
                            if (node.parent == null && node.id != 0) {
                                node.parent = e;
                                node.depth = current.depth + 1;
                            }
                        }
                        int k = choices - 1;
                        if (k < expected.size() && node != expected.get(k)) {
                            problems.add("non-deterministic replay at step " + p.depth() + ": reached a different state than before");
                        }
                    }
                    current = node;
                    progressBefore = p.progress();
                }

                @Override
                public int choose(List<Integer> enabled) {
                    throw new UnsupportedOperationException("decision points only");
                }

                @Override
                public int choose(DecisionPoint p) {
                    if (!problems.isEmpty()) {
                        return ControlledExecution.STOP;
                    }
                    Integer thread;
                    if (choices < route.size()) {
                        thread = route.get(choices);
                        if (!p.enabled().contains(thread)) {
                            problems.add("non-deterministic replay at step " + p.depth() + ": thread not enabled");
                            return ControlledExecution.STOP;
                        }
                    } else {
                        thread = nodes.size() >= budget ? null : current.unexplored();
                        if (thread == null) {
                            return ControlledExecution.STOP;
                        }
                    }
                    choices++;
                    chosenThread = thread;
                    return p.enabled().indexOf(thread);
                }
            };

            Execution exec = ControlledExecution.run(named, chooser, Integer.MAX_VALUE);
            executions++;
            if (root == null && !nodes.isEmpty()) {
                root = nodes.getFirst();
                root.depth = 0;
            }
            if (exec.outcome() == Outcome.ERROR) {
                error = exec.error();
                break;
            }
            if (!problems.isEmpty()) {
                error = problems.getFirst();
                break;
            }
            List<Step> steps = exec.steps();
            for (Edge e : newEdges) {
                e.step = steps.get(e.decision);
            }
            for (Node n : newNodes) {
                if (n.violation != null) {
                    safetyViolations++;
                    if (firstSafety == null) {
                        firstSafety = n.violation;
                        safetyTrace = pathTo(n);
                    }
                }
                if (n.enabled.isEmpty() && !n.allFinished) {
                    deadlocks++;
                    if (deadlockTrace == null) {
                        deadlockTrace = pathTo(n);
                    }
                }
            }
        }

        Witness livelock = null;
        Map<String, Witness> starvation = new LinkedHashMap<>();
        if (error == null && root != null) {
            int threads = threadNames.size();
            livelock = findFairCycle(root, nodes, threads, e -> !anyTrue(e.progressed()), -1);
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                Witness w = findFairCycle(root, nodes, threads, e -> !e.progressed()[thread], thread);
                if (w != null) {
                    starvation.put(threadNames.get(t), w);
                }
            }
        }
        return new Report(List.copyOf(threadNames), nodes.size(), edges.size(), executions, complete, maxStates,
                safetyViolations, firstSafety, safetyTrace, deadlocks, deadlockTrace, livelock, starvation, error,
                System.nanoTime() - start);
    }

    private static String key(String snapshot, DecisionPoint p) {
        StringBuilder sb = new StringBuilder(snapshot.length() + 64);
        sb.append(snapshot).append(" | ");
        for (int i = 0; i < p.pending().size(); i++) {
            String a = p.pending().get(i);
            sb.append(i).append(':').append(a == null ? "done" : a).append(p.enabled().contains(i) ? "" : " (blocked)").append("; ");
        }
        sb.append(p.lockHolders());
        return sb.toString();
    }

    private static List<Step> pathTo(Node n) {
        List<Step> out = new ArrayList<>();
        for (Node x = n; x.parent != null; x = x.parent.from()) {
            out.addFirst(x.parent.step());
        }
        return out;
    }

    private static boolean anyTrue(boolean[] b) {
        for (boolean x : b) {
            if (x) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a weakly fair cycle using only edges accepted by {@code keep}. With {@code victim ≥ 0},
     * the victim thread must still be running (not finished) in the cycle.
     */
    private static Witness findFairCycle(Node root, List<Node> nodes, int threads,
                                         java.util.function.Predicate<Edge> keep, int victim) {
        List<List<Node>> sccs = tarjan(nodes, keep);
        for (List<Node> scc : sccs) {
            java.util.Set<Node> members = new java.util.HashSet<>(scc);
            List<Edge> inner = new ArrayList<>();
            for (Node n : scc) {
                for (Edge e : n.out.values()) {
                    if (keep.test(e) && members.contains(e.to())) {
                        inner.add(e);
                    }
                }
            }
            if (inner.isEmpty()) {
                continue;
            }
            if (victim >= 0 && scc.getFirst().finished.get(victim)) {
                continue;
            }
            // Weak fairness: each running thread either takes a step in the cycle, or the cycle passes
            // through a state where it is blocked. Waypoints: an edge to take, or a node to visit.
            boolean fair = true;
            List<Object> waypoints = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                if (scc.getFirst().finished.get(t)) {
                    continue;   // a finished thread stays finished
                }
                Edge e = inner.stream().filter(x -> x.thread() == thread).findFirst().orElse(null);
                Node blocked = scc.stream().filter(n -> !n.enabled.contains(thread)).findFirst().orElse(null);
                if (e != null) {
                    waypoints.add(e);
                } else if (blocked != null) {
                    waypoints.add(blocked);
                } else {
                    fair = false;   // continuously enabled but never scheduled: an unfair cycle
                    break;
                }
            }
            if (!fair) {
                continue;
            }
            Edge first = waypoints.stream().filter(Edge.class::isInstance).map(Edge.class::cast).findFirst().orElse(inner.getFirst());
            if (!waypoints.contains(first)) {
                waypoints.addFirst(first);
            }
            Node anchor = first.from();
            List<Step> cycle = new ArrayList<>();
            Node at = anchor;
            for (Object w : waypoints) {
                if (w instanceof Edge e) {
                    cycle.addAll(walk(at, e.from(), members, keep));
                    cycle.add(e.step());
                    at = e.to();
                } else {
                    Node n = (Node) w;
                    cycle.addAll(walk(at, n, members, keep));
                    at = n;
                }
            }
            cycle.addAll(walk(at, anchor, members, keep));
            return new Witness(pathTo(anchor), cycle);
        }
        return null;
    }

    /** Shortest path of steps from {@code a} to {@code b} inside the component. */
    private static List<Step> walk(Node a, Node b, java.util.Set<Node> members, java.util.function.Predicate<Edge> keep) {
        if (a == b) {
            return List.of();
        }
        Map<Node, Edge> via = new HashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(a);
        via.put(a, null);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n == b) {
                break;
            }
            for (Edge e : n.out.values()) {
                if (keep.test(e) && members.contains(e.to()) && !via.containsKey(e.to())) {
                    via.put(e.to(), e);
                    queue.add(e.to());
                }
            }
        }
        List<Step> out = new ArrayList<>();
        for (Node x = b; x != a; x = via.get(x).from()) {
            out.addFirst(via.get(x).step());
        }
        return out;
    }

    /** Iterative Tarjan SCC over edges accepted by {@code keep}. */
    private static List<List<Node>> tarjan(List<Node> nodes, java.util.function.Predicate<Edge> keep) {
        int n = nodes.size();
        int[] index = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        java.util.Arrays.fill(index, -1);
        ArrayDeque<Node> stack = new ArrayDeque<>();
        List<List<Node>> out = new ArrayList<>();
        int counter = 0;
        for (Node start : nodes) {
            if (index[start.id] >= 0) {
                continue;
            }
            ArrayDeque<Object[]> work = new ArrayDeque<>();   // {node, iterator}
            work.push(new Object[] {start, start.out.values().iterator()});
            index[start.id] = low[start.id] = counter++;
            stack.push(start);
            onStack[start.id] = true;
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                Node v = (Node) frame[0];
                @SuppressWarnings("unchecked") java.util.Iterator<Edge> it = (java.util.Iterator<Edge>) frame[1];
                boolean pushed = false;
                while (it.hasNext()) {
                    Edge e = it.next();
                    if (!keep.test(e)) {
                        continue;
                    }
                    Node w = e.to();
                    if (index[w.id] < 0) {
                        index[w.id] = low[w.id] = counter++;
                        stack.push(w);
                        onStack[w.id] = true;
                        work.push(new Object[] {w, w.out.values().iterator()});
                        pushed = true;
                        break;
                    } else if (onStack[w.id]) {
                        low[v.id] = Math.min(low[v.id], index[w.id]);
                    }
                }
                if (pushed) {
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    Node parent = (Node) work.peek()[0];
                    low[parent.id] = Math.min(low[parent.id], low[v.id]);
                }
                if (low[v.id] == index[v.id]) {
                    List<Node> comp = new ArrayList<>();
                    Node w;
                    do {
                        w = stack.pop();
                        onStack[w.id] = false;
                        comp.add(w);
                    } while (w != v);
                    out.add(comp);
                }
            }
        }
        return out;
    }
}
