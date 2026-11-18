package ru.nexora.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Allowlist of bounded in-process recovery actions.
 *
 * <p>Actions are registered by trusted runtime code. The registry cannot execute shell
 * commands, arbitrary scripts, filesystem patches, or generated AI output. Manual
 * execution requires an explicit confirmation token; automatic execution is limited
 * to actions explicitly registered as auto-safe.</p>
 */
public final class NexoraAiSafeRepairRegistry {
    private static final int MAX_HISTORY = 100;
    private final ConcurrentMap<String, SafeAction> actions = new ConcurrentHashMap<>();
    private final java.util.Set<String> executing = ConcurrentHashMap.newKeySet();
    private final Deque<Execution> history = new ArrayDeque<>();
    private final Clock clock;
    private final Consumer<Execution> observer;

    public NexoraAiSafeRepairRegistry(Consumer<Execution> observer) {
        this(Clock.systemUTC(), observer);
    }

    NexoraAiSafeRepairRegistry(Clock clock, Consumer<Execution> observer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public void register(
            String id,
            String description,
            boolean autoSafe,
            BooleanSupplier precondition,
            Runnable action,
            BooleanSupplier verification
    ) {
        String key = requireId(id);
        String detail = requireText(description, "description", 240);
        SafeAction safeAction = new SafeAction(key, detail, autoSafe,
                Objects.requireNonNull(precondition, "precondition"),
                Objects.requireNonNull(action, "action"),
                Objects.requireNonNull(verification, "verification"));
        if (actions.putIfAbsent(key, safeAction) != null) {
            throw new IllegalArgumentException("Repair action is already registered: " + key);
        }
    }

    public List<ActionDescriptor> actions() {
        return actions.values().stream()
                .map(action -> new ActionDescriptor(action.id(), action.description(), action.autoSafe()))
                .sorted(Comparator.comparing(ActionDescriptor::id))
                .toList();
    }

    /** Read-only eligibility check used by the monitor before attempting an auto-safe action. */
    public boolean canExecuteAutomatically(String id) {
        String key = requireId(id);
        SafeAction action = actions.get(key);
        if (action == null || !action.autoSafe() || executing.contains(key)) return false;
        try {
            return action.precondition().getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public Execution execute(String id, String actor, boolean explicitlyConfirmed) {
        return executeInternal(id, actor, explicitlyConfirmed, false);
    }

    /** Executes only actions that trusted code explicitly marked as safe for automatic recovery. */
    public Execution executeAutomatically(String id) {
        return executeInternal(id, "nexora-ai-auto", true, true);
    }

    public synchronized List<Execution> history() {
        return List.copyOf(history);
    }

    private Execution executeInternal(String id, String actor, boolean confirmed, boolean automatic) {
        String key = requireId(id);
        String requester = requireText(actor, "actor", 128);
        SafeAction action = actions.get(key);
        if (action == null) return record(key, requester, Status.NOT_FOUND, "Действие не зарегистрировано");
        if (automatic && !action.autoSafe()) {
            return record(key, requester, Status.AUTOMATIC_NOT_ALLOWED,
                    "Действие не разрешено для автоматического запуска");
        }
        if (!confirmed) {
            return record(key, requester, Status.CONFIRMATION_REQUIRED,
                    "Для запуска добавь явное подтверждение");
        }
        if (!executing.add(key)) {
            return record(key, requester, Status.ALREADY_RUNNING, "Действие уже выполняется");
        }

        try {
            if (!action.precondition().getAsBoolean()) {
                return record(key, requester, Status.PRECONDITION_FAILED,
                        "Предварительное условие не выполнено; состояние не менялось");
            }
            action.action().run();
            if (!action.verification().getAsBoolean()) {
                return record(key, requester, Status.VERIFICATION_FAILED,
                        "Действие выполнено, но проверка результата не пройдена");
            }
            return record(key, requester, Status.APPLIED, "Действие выполнено и проверено");
        } catch (RuntimeException failure) {
            return record(key, requester, Status.FAILED,
                    failure.getClass().getSimpleName() + ": " + safeMessage(failure.getMessage()));
        } finally {
            executing.remove(key);
        }
    }

    private Execution record(String id, String actor, Status status, String detail) {
        Execution execution = new Execution(id, actor, status,
                requireText(detail, "detail", 400), Instant.now(clock));
        synchronized (this) {
            history.addFirst(execution);
            while (history.size() > MAX_HISTORY) history.removeLast();
        }
        try {
            observer.accept(execution);
        } catch (RuntimeException ignored) {
            // Diagnostics must not turn a completed repair into a failed operation.
        }
        return execution;
    }

    private static String requireId(String id) {
        String value = requireText(id, "id", 64);
        if (!value.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("Repair action ID has an invalid format");
        }
        return value;
    }

    private static String requireText(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1.." + max + " characters");
        }
        return normalized;
    }

    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) return "без описания";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    private record SafeAction(
            String id,
            String description,
            boolean autoSafe,
            BooleanSupplier precondition,
            Runnable action,
            BooleanSupplier verification
    ) {
    }

    public enum Status {
        APPLIED,
        CONFIRMATION_REQUIRED,
        AUTOMATIC_NOT_ALLOWED,
        NOT_FOUND,
        PRECONDITION_FAILED,
        VERIFICATION_FAILED,
        ALREADY_RUNNING,
        FAILED
    }

    public record ActionDescriptor(String id, String description, boolean autoSafe) {
    }

    public record Execution(String actionId, String actor, Status status, String detail, Instant at) {
        public Execution {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(at, "at");
        }
    }
}
