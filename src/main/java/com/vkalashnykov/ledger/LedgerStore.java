package com.vkalashnykov.ledger;

import java.util.Optional;

public interface LedgerStore {

    boolean createAccount(String id, long openingBalance);

    Optional<Account> findAccount(String id);

    TransferOutcome applyTransfer(long transferId, TransferRequest request);
}
