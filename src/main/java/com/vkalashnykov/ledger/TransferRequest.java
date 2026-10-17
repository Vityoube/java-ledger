package com.vkalashnykov.ledger;

public record TransferRequest(String from, String to, long amount) {

    public TransferRequest {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("from is required");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("to is required");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("from and to must differ");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
