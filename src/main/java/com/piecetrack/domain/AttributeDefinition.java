package com.piecetrack.domain;

/**
 * A user-defined item-model field (M15) - e.g. "Material", "Warranty", "Voltage", "Carat" -
 * replacing the hardcoded material/finish/colour/dimension columns {@link ItemModel} carried
 * before the generic rename. {@code displayOrder} controls the order fields appear in on the
 * item model editor, the same role {@code sort_order} plays for {@link ItemPhoto}.
 */
public record AttributeDefinition(
        long id,
        String name,
        int displayOrder,
        boolean active
) {
}
