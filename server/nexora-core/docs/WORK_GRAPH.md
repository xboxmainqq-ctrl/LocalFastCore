# NEXORA Work Graph

The Work Graph describes execution dependencies without owning gameplay state.

A node declares an execution lane, criticality, and zero or more dependencies. A node becomes READY only when all dependencies are COMPLETED. Independent nodes can become ready together. Deferred work returns to PENDING at the next admission cycle.

The graph rejects dependency cycles. It is an ordering model, not permission to mutate world state concurrently. Parallel execution will only be introduced where the world ownership model explicitly permits it.
