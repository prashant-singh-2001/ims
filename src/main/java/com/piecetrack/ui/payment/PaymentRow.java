package com.piecetrack.ui.payment;

import com.piecetrack.domain.Payment;

/** Read-only row wrapper for the payment list (docs/03-screens.md section 7). */
public class PaymentRow {

    private final Payment payment;
    private final String partyName;

    public PaymentRow(Payment payment, String partyName) {
        this.payment = payment;
        this.partyName = partyName;
    }

    public Payment getPayment() {
        return payment;
    }

    public long getId() {
        return payment.id();
    }

    public String getDirection() {
        return payment.direction().name();
    }

    public String getDate() {
        return payment.paymentDate().toString();
    }

    public String getPartyName() {
        return partyName;
    }

    public String getAmount() {
        return payment.amount().toDisplayString();
    }

    public String getMode() {
        return payment.mode().name();
    }

    public String getReference() {
        return payment.referenceNo() == null ? "" : payment.referenceNo();
    }

    public String getStatus() {
        return payment.deleted() ? "Deleted - " + payment.deletedReason() : "Active";
    }
}
