package com.piecetrack.domain;

/**
 * The single row in {@code shop_profile} (docs/02-data-model.md 4.1). Everything here
 * prints on generated documents (FR-DOC-01) and decides the CGST/SGST vs IGST split on
 * every sale (FR-SAL-05).
 */
public record ShopProfile(
        String shopName,
        String addressLine1,
        String addressLine2,
        String city,
        String pincode,
        String stateName,
        String stateCode,
        String gstin,
        RegistrationType registrationType,
        String phone,
        String email,
        String logoPath,
        String invoiceDeclaration,
        String signatureText
) {
    /** SRS section 7.1: which one applies is an open point pending your confirmation -
     *  it decides whether documents are tax invoices or bills of supply. */
    public enum RegistrationType { REGULAR, COMPOSITION }
}
