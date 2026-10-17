package com.vkalashnykov.ledger;

import com.vkalashnykov.ledger.InMemoryLedgerStore.JournalEntry;
import com.vkalashnykov.ledger.TransferOutcome.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    private static final int THREADS = 32;

    @Test
    void neverSpendsMoreThanAvailableUnderContention() throws Exception {
        Ledger ledger = new Ledger();
        ledger.createAccount("alice", 100);
        ledger.createAccount("bob", 0);

        List<Callable<TransferOutcome>> attempts = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            int n = i;
            attempts.add(() -> ledger.transfer("k" + n, new TransferRequest("alice", "bob", 1)));
        }
        List<TransferOutcome> outcomes = runConcurrently(THREADS, attempts);

        long completed = outcomes.stream().filter(o -> o.status() == Status.COMPLETED).count();
        assertEquals(100, completed);
        assertEquals(0, balance(ledger, "alice"));
        assertEquals(100, balance(ledger, "bob"));
    }

    @Test
    void randomConcurrentTransfersConserveMoneyAndNeverGoNegative() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        Ledger ledger = new Ledger(store);
        int accounts = 10;
        long opening = 1_000;
        for (int i = 0; i < accounts; i++) {
            ledger.createAccount("acc" + i, opening);
        }

        List<Callable<Integer>> workers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int seed = t;
            workers.add(() -> {
                Random rnd = new Random(seed);
                int completed = 0;
                for (int j = 0; j < 2_000; j++) {
                    int from = rnd.nextInt(accounts);
                    int to = (from + 1 + rnd.nextInt(accounts - 1)) % accounts;
                    long amount = 1 + rnd.nextInt(300);
                    TransferOutcome outcome = ledger.transfer("t" + seed + "-" + j,
                            new TransferRequest("acc" + from, "acc" + to, amount));
                    if (outcome.status() == Status.COMPLETED) {
                        completed++;
                    }
                }
                return completed;
            });
        }
        int completedTotal = runConcurrently(THREADS, workers).stream().mapToInt(Integer::intValue).sum();

        long total = 0;
        Map<Long, Long> deltaByTransfer = new HashMap<>();
        int postings = 0;
        for (int i = 0; i < accounts; i++) {
            String id = "acc" + i;
            long balance = balance(ledger, id);
            assertTrue(balance >= 0, id + " went negative: " + balance);
            total += balance;

            List<JournalEntry> journal = store.journal(id);
            long journalSum = journal.stream().mapToLong(JournalEntry::delta).sum();
            assertEquals(balance, journalSum, "lost update on " + id);
            for (JournalEntry e : journal) {
                if (e.transferId() != InMemoryLedgerStore.OPENING_TRANSFER_ID) {
                    deltaByTransfer.merge(e.transferId(), e.delta(), Long::sum);
                    postings++;
                }
            }
        }

        assertEquals(accounts * opening, total, "money was created or destroyed");
        // Every applied transfer has a debit and a credit that cancel out (double-entry).
        assertTrue(deltaByTransfer.values().stream().allMatch(sum -> sum == 0));
        assertEquals(completedTotal * 2, postings);
    }

    @Test
    void concurrentRetriesWithSameKeyApplyOnceAndShareOutcome() throws Exception {
        Ledger ledger = new Ledger();
        ledger.createAccount("alice", 100);
        ledger.createAccount("bob", 0);
        TransferRequest request = new TransferRequest("alice", "bob", 40);

        List<Callable<TransferOutcome>> retries = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            retries.add(() -> ledger.transfer("same-key", request));
        }
        List<TransferOutcome> outcomes = runConcurrently(THREADS, retries);

        assertEquals(1, outcomes.stream().map(TransferOutcome::transferId).distinct().count());
        assertTrue(outcomes.stream().allMatch(o -> o.status() == Status.COMPLETED));
        assertEquals(60, balance(ledger, "alice"));
        assertEquals(40, balance(ledger, "bob"));
    }

    @Test
    void retryArrivingWhileOriginalIsInFlightWaitsForItsOutcome() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Ledger ledger = new Ledger(new InMemoryLedgerStore(id -> {
            if (id.equals("alice")) {
                entered.countDown();
                awaitQuietly(release);
            }
        }));
        ledger.createAccount("alice", 100);
        ledger.createAccount("bob", 0);
        TransferRequest request = new TransferRequest("alice", "bob", 40);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TransferOutcome> original = pool.submit(() -> ledger.transfer("k", request));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            Future<TransferOutcome> retry = pool.submit(() -> ledger.transfer("k", request));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> retry.get(200, TimeUnit.MILLISECONDS));

            release.countDown();
            assertEquals(original.get(5, TimeUnit.SECONDS), retry.get(5, TimeUnit.SECONDS));
            assertEquals(60, balance(ledger, "alice"));
            assertEquals(40, balance(ledger, "bob"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void transfersOnUnrelatedAccountsDoNotWaitForABlockedTransfer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Ledger ledger = new Ledger(new InMemoryLedgerStore(id -> {
            if (id.equals("alice")) {
                entered.countDown();
                awaitQuietly(release);
            }
        }));
        for (String id : List.of("alice", "bob", "carol", "dave")) {
            ledger.createAccount(id, 100);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TransferOutcome> blocked = pool.submit(
                    () -> ledger.transfer("k1", new TransferRequest("alice", "bob", 10)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // alice's lock is held by the blocked transfer; this one must still finish.
            TransferOutcome other = pool.submit(
                    () -> ledger.transfer("k2", new TransferRequest("carol", "dave", 10))).get(5, TimeUnit.SECONDS);
            assertEquals(Status.COMPLETED, other.status());

            release.countDown();
            assertEquals(Status.COMPLETED, blocked.get(5, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static long balance(Ledger ledger, String id) {
        return ledger.account(id).orElseThrow().balance();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs all tasks on a pool of {@code threads}, released together, and returns their results in order. */
    private static <T> List<T> runConcurrently(int threads, List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
