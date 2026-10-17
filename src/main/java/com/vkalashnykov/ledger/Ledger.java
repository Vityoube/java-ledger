package com.vkalashnykov.ledger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Ledger {

    private final LedgerStore store;
    private final AtomicLong nextTransferId = new AtomicLong(1);
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    public Ledger(LedgerStore store) {
        this.store = store;
    }

    public Ledger() {
        this(new InMemoryLedgerStore());
    }

    public boolean createAccount(String id, long openingBalance) {
        requireText(id, "id");
        if (openingBalance < 0) {
            throw new IllegalArgumentException("openingBalance must not be negative");
        }
        return store.createAccount(id, openingBalance);
    }

    public Optional<Account> account(String id) {
        requireText(id, "id");
        return store.findAccount(id);
    }

    public TransferOutcome transfer(String correlationId, TransferRequest request) {
        requireText(correlationId, "correlationId");
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }

        Pending claim = new Pending(request);
        Pending existing = pending.putIfAbsent(correlationId, claim);
        if (existing != null) {
            return existing.await(correlationId, request);
        }
        try {
            TransferOutcome outcome = store.applyTransfer(nextTransferId.getAndIncrement(), request);
            claim.outcome.complete(outcome);
            return outcome;
        } catch (RuntimeException e) {
            claim.outcome.completeExceptionally(e);
            throw e;
        } finally {
            pending.remove(correlationId, claim);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static final class Pending {
        private final TransferRequest request;
        private final CompletableFuture<TransferOutcome> outcome = new CompletableFuture<>();

        Pending(TransferRequest request) {
            this.request = request;
        }

        TransferOutcome await(String correlationId, TransferRequest retry) {
            if (!request.equals(retry)) {
                throw new IdempotencyConflictException(
                        "correlation id %s was already used for a different transfer".formatted(correlationId));
            }
            try {
                return outcome.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw e;
            }
        }
    }
}
