package com.furnitureims.domain;

public record Customer(
        long id,
        String name,
        String phone,
        String email,
        String addressLine1,
        String addressLine2,
        String city,
        String pincode,
        String stateName,
        String stateCode,
        String gstin,
        String notes
) {
}
