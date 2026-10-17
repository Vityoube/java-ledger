package com.vkalashnykov.ledger;

public record TransferOutcome(long transferId, TransferRequest request, Status status, RejectionReason reason) {

    static TransferOutcome completed(long transferId, TransferRequest request) {
        return new TransferOutcome(transferId, request, Status.COMPLETED, null);
    }

    static TransferOutcome rejected(long transferId, TransferRequest request, RejectionReason reason) {
        return new TransferOutcome(transferId, request, Status.REJECTED, reason);
    }
}
