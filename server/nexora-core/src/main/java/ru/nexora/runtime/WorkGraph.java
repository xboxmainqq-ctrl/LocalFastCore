package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dependency-aware ordering graph for NEXORA runtime work. */
public final class WorkGraph {
    public enum State { PENDING, READY, RUNNING, COMPLETED, DEFERRED, FAILED }
    private final Map<String, Node> nodes = new HashMap<>();

    public synchronized WorkGraph add(String id, AdaptiveRuntime.Lane lane, boolean critical) {
        requireId(id); Objects.requireNonNull(lane, "lane");
        if (nodes.containsKey(id)) throw new IllegalArgumentException("Duplicate work node: " + id);
        nodes.put(id, new Node(id, lane, critical)); return this;
    }
    public synchronized WorkGraph dependsOn(String id, String dependencyId) {
        Node node=requireNode(id), dep=requireNode(dependencyId);
        if (id.equals(dependencyId)) throw new IllegalArgumentException("Self dependency: " + id);
        node.dependencies.add(dep.id); return this;
    }
    public synchronized List<NodeView> ready() {
        List<NodeView> result=new ArrayList<>();
        for (Node n:nodes.values()) if(n.state==State.PENDING && dependenciesCompleted(n)){n.state=State.READY; result.add(view(n));}
        return List.copyOf(result);
    }
    public synchronized void start(String id){Node n=requireNode(id); if(n.state!=State.READY)throw new IllegalStateException("Node is not ready: "+id); n.state=State.RUNNING;}
    public synchronized void complete(String id){Node n=requireNode(id); if(n.state!=State.RUNNING)throw new IllegalStateException("Node is not running: "+id); n.state=State.COMPLETED;}
    public synchronized void defer(String id){Node n=requireNode(id); if(n.state!=State.READY&&n.state!=State.PENDING)throw new IllegalStateException("Node cannot be deferred: "+id); n.state=State.DEFERRED;}
    public synchronized void reopenDeferred(){for(Node n:nodes.values())if(n.state==State.DEFERRED)n.state=State.PENDING;}
    public synchronized void fail(String id){Node n=requireNode(id); if(n.state!=State.RUNNING)throw new IllegalStateException("Node is not running: "+id); n.state=State.FAILED;}
    public synchronized List<NodeView> snapshot(){return nodes.values().stream().map(this::view).toList();}
    public synchronized void validate(){Set<String> visiting=new HashSet<>(),visited=new HashSet<>(); for(Node n:nodes.values())detectCycle(n.id,visiting,visited);}
    private void detectCycle(String id,Set<String> visiting,Set<String> visited){if(visited.contains(id))return; if(!visiting.add(id))throw new IllegalStateException("Work graph cycle at: "+id); for(String d:requireNode(id).dependencies)detectCycle(d,visiting,visited); visiting.remove(id); visited.add(id);}
    private boolean dependenciesCompleted(Node n){for(String d:n.dependencies)if(requireNode(d).state!=State.COMPLETED)return false;return true;}
    private Node requireNode(String id){requireId(id);Node n=nodes.get(id);if(n==null)throw new IllegalArgumentException("Unknown work node: "+id);return n;}
    private static void requireId(String id){if(id==null||id.isBlank())throw new IllegalArgumentException("Work node id is blank");}
    private NodeView view(Node n){return new NodeView(n.id,n.lane,n.critical,n.state,List.copyOf(n.dependencies));}
    private static final class Node{final String id;final AdaptiveRuntime.Lane lane;final boolean critical;final Set<String> dependencies=new HashSet<>();State state=State.PENDING;Node(String id,AdaptiveRuntime.Lane lane,boolean critical){this.id=id;this.lane=lane;this.critical=critical;}}
    public record NodeView(String id,AdaptiveRuntime.Lane lane,boolean critical,State state,List<String> dependencies){}
}