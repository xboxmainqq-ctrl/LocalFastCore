package ru.nexora.runtime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WorkGraphTest {
 @Test void releasesDependentWorkOnlyAfterDependencyCompletes(){
  WorkGraph g=new WorkGraph().add("target",AdaptiveRuntime.Lane.AI,true).add("move",AdaptiveRuntime.Lane.AI,false).dependsOn("move","target");
  assertEquals(1,g.ready().size()); g.start("target"); assertTrue(g.ready().stream().noneMatch(n->n.id().equals("move"))); g.complete("target"); assertTrue(g.ready().stream().anyMatch(n->n.id().equals("move")));
 }
 @Test void rejectsCycles(){WorkGraph g=new WorkGraph().add("a",AdaptiveRuntime.Lane.AI,false).add("b",AdaptiveRuntime.Lane.AI,false).dependsOn("b","a").dependsOn("a","b");assertThrows(IllegalStateException.class,g::validate);}
 @Test void deferredWorkCanReturnToPending(){WorkGraph g=new WorkGraph().add("x",AdaptiveRuntime.Lane.BACKGROUND,false);assertEquals(WorkGraph.State.READY,g.ready().get(0).state());g.defer("x");g.reopenDeferred();assertEquals(WorkGraph.State.READY,g.ready().get(0).state());}
 @Test void runtimeCanDeferExpensiveBackgroundWork(){
  WorkGraph g=new WorkGraph().add("cleanup",AdaptiveRuntime.Lane.BACKGROUND,false).estimate("cleanup",9_000_000L);
  AdaptiveRuntime runtime=new AdaptiveRuntime(10_000_000L,2_000_000L);
  runtime.admit(AdaptiveRuntime.Lane.PLAYER,8_000_000L,true);
  assertTrue(g.readyFor(runtime).isEmpty());
 }
 @Test void observedCostBecomesFutureAdmissionEstimate(){
  WorkGraph g=new WorkGraph().add("ai",AdaptiveRuntime.Lane.AI,false);
  g.observe("ai",200_000L).observe("ai",100_000L);
  assertTrue(g.snapshot().get(0).observedP95Nanos() >= 100_000L);
  assertEquals(g.snapshot().get(0).observedP95Nanos(), g.snapshot().get(0).estimatedNanos());
 }
}