package com.furnitureims.domain;

import com.furnitureims.money.Money;

public record Supplier(
        long id,
        String name,
        String gstin,
        String addressLine1,
        String addressLine2,
        String city,
        String pincode,
        String stateName,
        String stateCode,
        String phone,
        String email,
        String contactPerson,
        Money openingBalance,
        boolean active,
        String notes
) {
}
