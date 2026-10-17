package com.vkalashnykov.ledger;

import com.vkalashnykov.ledger.TransferOutcome.RejectionReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class InMemoryLedgerStore implements LedgerStore {

    static final long OPENING_TRANSFER_ID = 0;

    private final ConcurrentMap<String, AccountCell> accounts = new ConcurrentHashMap<>();
    private final Consumer<String> onFirstLock;

    public InMemoryLedgerStore() {
        this(id -> { });
    }

    InMemoryLedgerStore(Consumer<String> onFirstLock) {
        this.onFirstLock = onFirstLock;
    }

    @Override
    public boolean createAccount(String id, long openingBalance) {
        AccountCell cell = new AccountCell(id);
        cell.post(OPENING_TRANSFER_ID, openingBalance);
        return accounts.putIfAbsent(id, cell) == null;
    }

    @Override
    public Optional<Account> findAccount(String id) {
        AccountCell cell = accounts.get(id);
        return cell == null ? Optional.empty() : Optional.of(cell.snapshot());
    }

    @Override
    public TransferOutcome applyTransfer(long transferId, TransferRequest request) {
        AccountCell from = accounts.get(request.from());
        AccountCell to = accounts.get(request.to());
        if (from == null || to == null) {
            return TransferOutcome.rejected(transferId, request, RejectionReason.UNKNOWN_ACCOUNT);
        }

        AccountCell first = from.id.compareTo(to.id) < 0 ? from : to;
        AccountCell second = first == from ? to : from;
        synchronized (first) {
            onFirstLock.accept(first.id);
            synchronized (second) {
                if (from.balance < request.amount()) {
                    return TransferOutcome.rejected(transferId, request, RejectionReason.INSUFFICIENT_FUNDS);
                }
                from.post(transferId, -request.amount());
                to.post(transferId, request.amount());
                return TransferOutcome.completed(transferId, request);
            }
        }
    }

    List<JournalEntry> journal(String id) {
        return accounts.get(id).journalSnapshot();
    }

    record JournalEntry(long transferId, long delta, long balanceAfter) {
    }

    private static final class AccountCell {
        private final String id;
        private long balance;
        private final List<JournalEntry> journal = new ArrayList<>();

        AccountCell(String id) {
            this.id = id;
        }

        synchronized void post(long transferId, long delta) {
            balance = Math.addExact(balance, delta);
            journal.add(new JournalEntry(transferId, delta, balance));
        }

        synchronized Account snapshot() {
            return new Account(id, balance);
        }

        synchronized List<JournalEntry> journalSnapshot() {
            return List.copyOf(journal);
        }
    }
}
