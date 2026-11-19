package ru.nexora.network;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Selects a healthy destination node using measured load and admission policy. */
public final class GatewayRouter {
    private final ServerLoadPolicy policy;

    public GatewayRouter(ServerLoadPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public Optional<ServerNode> select(Collection<ServerNode> nodes, String requiredProfile) {
        Objects.requireNonNull(nodes, "nodes");
        if (requiredProfile == null || requiredProfile.isBlank())
            throw new IllegalArgumentException("requiredProfile is blank");

        return nodes.stream()
                .filter(Objects::nonNull)
                .filter(ServerNode::healthy)
                .filter(node -> requiredProfile.equals(node.profile()))
                .filter(node -> policy.decide(node.load()) != ServerLoadPolicy.Decision.REDIRECT)
                .min(Comparator
                        .comparingInt((ServerNode node) -> policyRank(policy.decide(node.load())))
                        .thenComparingDouble(node -> node.load().pressure())
                        .thenComparingInt(node -> node.load().players())
                        .thenComparing(ServerNode::id));
    }

    private static int policyRank(ServerLoadPolicy.Decision decision) {
        return decision == ServerLoadPolicy.Decision.ACCEPT ? 0 : 1;
    }
}
