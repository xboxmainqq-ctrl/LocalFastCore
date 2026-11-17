package ru.nexora.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, process-local store for advisory repair plans.
 *
 * Approval only marks a plan as approved for manual review. This class has no
 * filesystem, process, command, or world mutation capabilities.
 */
public final class NexoraAiRepairProposals {
    private static final int MAX_PROPOSALS = 100;
    private static final int MAX_ISSUE_LENGTH = 600;
    private static final int MAX_PLAN_LENGTH = 6_000;
    private static final Duration TTL = Duration.ofHours(24);

    private final AtomicLong sequence = new AtomicLong();
    private final LinkedHashMap<String, Proposal> proposals = new LinkedHashMap<>();
    private final Clock clock;

    public NexoraAiRepairProposals() {
        this(Clock.systemUTC());
    }

    NexoraAiRepairProposals(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Proposal create(String requesterXuid, String issue, String plan) {
        String requester = requireText(requesterXuid, "requesterXuid", 256);
        String problem = requireText(issue, "issue", MAX_ISSUE_LENGTH);
        String proposalPlan = requireText(plan, "plan", MAX_PLAN_LENGTH);
        purgeExpired();
        long next = sequence.incrementAndGet();
        if (next < 1) throw new IllegalStateException("Repair proposal ID space exhausted");
        String id = String.format(Locale.ROOT, "R-%06d", next);
        Proposal proposal = new Proposal(id, requester, problem, proposalPlan,
                Status.PENDING_REVIEW, Instant.now(clock), null, null);
        proposals.put(id, proposal);
        while (proposals.size() > MAX_PROPOSALS) {
            String oldest = proposals.keySet().iterator().next();
            proposals.remove(oldest);
        }
        return proposal;
    }

    public synchronized Optional<Proposal> find(String id) {
        purgeExpired();
        if (id == null) return Optional.empty();
        return Optional.ofNullable(proposals.get(id.trim().toUpperCase(Locale.ROOT)));
    }

    public synchronized List<Proposal> list() {
        purgeExpired();
        return proposals.values().stream()
                .sorted(Comparator.comparing(Proposal::createdAt).reversed())
                .toList();
    }

    public synchronized Optional<Proposal> approveForManualReview(String id, String reviewerXuid) {
        return transition(id, reviewerXuid, Status.PENDING_REVIEW, Status.APPROVED_FOR_MANUAL_REVIEW);
    }

    public synchronized Optional<Proposal> reject(String id, String reviewerXuid) {
        return transition(id, reviewerXuid, Status.PENDING_REVIEW, Status.REJECTED);
    }

    private Optional<Proposal> transition(
            String id, String reviewerXuid, Status expected, Status target
    ) {
        String reviewer = requireText(reviewerXuid, "reviewerXuid", 256);
        purgeExpired();
        if (id == null) return Optional.empty();
        String key = id.trim().toUpperCase(Locale.ROOT);
        Proposal current = proposals.get(key);
        if (current == null || current.status() != expected) return Optional.empty();
        Proposal updated = new Proposal(current.id(), current.requesterXuid(), current.issue(),
                current.plan(), target, current.createdAt(), reviewer, Instant.now(clock));
        proposals.put(key, updated);
        return Optional.of(updated);
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now(clock).minus(TTL);
        proposals.values().removeIf(proposal -> proposal.createdAt().isBefore(cutoff));
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maxLength + " characters");
        }
        return normalized;
    }

    public enum Status {
        PENDING_REVIEW,
        APPROVED_FOR_MANUAL_REVIEW,
        REJECTED
    }

    public record Proposal(
            String id,
            String requesterXuid,
            String issue,
            String plan,
            Status status,
            Instant createdAt,
            String reviewedByXuid,
            Instant reviewedAt
    ) {
        public Proposal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requesterXuid, "requesterXuid");
            Objects.requireNonNull(issue, "issue");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            if ((reviewedByXuid == null) != (reviewedAt == null)) {
                throw new IllegalArgumentException("Review identity and timestamp must be set together");
            }
            if (status == Status.PENDING_REVIEW && reviewedByXuid != null
                    || status != Status.PENDING_REVIEW && reviewedByXuid == null) {
                throw new IllegalArgumentException("Review metadata does not match proposal status");
            }
        }
    }
}
