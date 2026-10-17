package com.vkalashnykov.ledger;

import com.vkalashnykov.ledger.InMemoryLedgerStore.JournalEntry;
import com.vkalashnykov.ledger.TransferOutcome.RejectionReason;
import com.vkalashnykov.ledger.TransferOutcome.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {

    private final InMemoryLedgerStore store = new InMemoryLedgerStore();
    private final Ledger ledger = new Ledger(store);

    private long balance(String id) {
        return ledger.account(id).orElseThrow().balance();
    }

    @Test
    void createsAccountWithOpeningBalanceAndRejectsDuplicates() {
        assertTrue(ledger.createAccount("alice", 500));
        assertFalse(ledger.createAccount("alice", 900));
        assertEquals(500, balance("alice"));
        assertTrue(ledger.account("nobody").isEmpty());
    }

    @Test
    void rejectsInvalidAccountInput() {
        assertThrows(IllegalArgumentException.class, () -> ledger.createAccount(" ", 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.createAccount("bob", -1));
    }

    @Test
    void completedTransferMovesMoneyAndKeepsTotal() {
        ledger.createAccount("alice", 500);
        ledger.createAccount("bob", 0);

        TransferOutcome outcome = ledger.transfer("k1", new TransferRequest("alice", "bob", 200));

        assertEquals(Status.COMPLETED, outcome.status());
        assertNull(outcome.reason());
        assertEquals(300, balance("alice"));
        assertEquals(200, balance("bob"));
    }

    @Test
    void transferCanDrainAccountToExactlyZero() {
        ledger.createAccount("alice", 100);
        ledger.createAccount("bob", 0);

        assertEquals(Status.COMPLETED, ledger.transfer("k1", new TransferRequest("alice", "bob", 100)).status());
        assertEquals(0, balance("alice"));
    }

    @Test
    void insufficientFundsRejectsAndChangesNothing() {
        ledger.createAccount("alice", 50);
        ledger.createAccount("bob", 0);

        TransferOutcome outcome = ledger.transfer("k1", new TransferRequest("alice", "bob", 51));

        assertEquals(Status.REJECTED, outcome.status());
        assertEquals(RejectionReason.INSUFFICIENT_FUNDS, outcome.reason());
        assertEquals(50, balance("alice"));
        assertEquals(0, balance("bob"));
    }

    @Test
    void unknownAccountRejectsAndChangesNothing() {
        ledger.createAccount("alice", 100);

        TransferOutcome outcome = ledger.transfer("k1", new TransferRequest("alice", "ghost", 10));

        assertEquals(Status.REJECTED, outcome.status());
        assertEquals(RejectionReason.UNKNOWN_ACCOUNT, outcome.reason());
        assertEquals(100, balance("alice"));
    }

    @Test
    void rejectsMalformedTransferRequests() {
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest("alice", "alice", 10));
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest("alice", "bob", 0));
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest("alice", "bob", -5));
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(null, "bob", 5));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer(" ", new TransferRequest("a", "b", 1)));
    }

    @Test
    void journalRecordsBothLegsOfEachTransfer() {
        ledger.createAccount("alice", 100);
        ledger.createAccount("bob", 0);

        TransferOutcome outcome = ledger.transfer("k1", new TransferRequest("alice", "bob", 30));

        assertEquals(new JournalEntry(outcome.transferId(), -30, 70), last(store.journal("alice")));
        assertEquals(new JournalEntry(outcome.transferId(), 30, 30), last(store.journal("bob")));
    }

    @Test
    void retryWithSameKeyReturnsOriginalOutcomeWithoutApplyingAgain() {
        ledger.createAccount("alice", 100);
        ledger.createAccount("bob", 0);
        TransferRequest request = new TransferRequest("alice", "bob", 30);

        TransferOutcome first = ledger.transfer("k1", request);
        TransferOutcome retry = ledger.transfer("k1", request);

        assertEquals(first, retry);
        assertEquals(70, balance("alice"));
        assertEquals(30, balance("bob"));
    }

    @Test
    void retryReplaysRejectionEvenIfFundsArriveLater() {
        ledger.createAccount("alice", 0);
        ledger.createAccount("bob", 50);
        TransferRequest request = new TransferRequest("alice", "bob", 10);
        TransferOutcome rejected = ledger.transfer("k1", request);

        ledger.transfer("k2", new TransferRequest("bob", "alice", 50));
        TransferOutcome retry = ledger.transfer("k1", request);

        assertEquals(RejectionReason.INSUFFICIENT_FUNDS, rejected.reason());
        assertEquals(rejected, retry);
        assertEquals(50, balance("alice"));
        assertEquals(0, balance("bob"));
    }

    @Test
    void reusingKeyForDifferentRequestIsRejectedAndAppliesNothing() {
        ledger.createAccount("alice", 100);
        ledger.createAccount("bob", 0);
        ledger.createAccount("carol", 0);
        ledger.transfer("k1", new TransferRequest("alice", "bob", 10));

        assertThrows(IdempotencyConflictException.class,
                () -> ledger.transfer("k1", new TransferRequest("alice", "carol", 10)));

        assertEquals(90, balance("alice"));
        assertEquals(10, balance("bob"));
        assertEquals(0, balance("carol"));
    }

    private static JournalEntry last(java.util.List<JournalEntry> entries) {
        return entries.get(entries.size() - 1);
    }
}
