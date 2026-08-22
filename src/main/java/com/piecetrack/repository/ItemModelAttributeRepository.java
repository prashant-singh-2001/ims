package com.piecetrack.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Values for a single {@code item_model}'s {@link com.piecetrack.domain.AttributeDefinition}
 * fields (M15) - keyed by {@code attribute_definition_id}, not by name, so renaming a
 * definition never orphans the values already entered under it.
 */
@Repository
public class ItemModelAttributeRepository {

    private final JdbcTemplate jdbc;

    public ItemModelAttributeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, String> findValuesByItemModelId(long itemModelId) {
        return jdbc.query(
                "SELECT attribute_definition_id, value FROM item_model_attribute WHERE item_model_id = ?",
                rs -> {
                    Map<Long, String> values = new HashMap<>();
                    while (rs.next()) {
                        values.put(rs.getLong("attribute_definition_id"), rs.getString("value"));
                    }
                    return values;
                },
                itemModelId);
    }

    /** Full replace, the same shape {@link ItemModelRepository#update} already uses for the
     *  item model row itself: simpler and less error-prone than diffing which attributes
     *  changed, and this only ever runs from a single "Save" button that submits the whole
     *  form at once. Blank values are dropped rather than stored as empty strings. */
    @Transactional
    public void saveValues(long itemModelId, Map<Long, String> valuesByDefinitionId) {
        jdbc.update("DELETE FROM item_model_attribute WHERE item_model_id = ?", itemModelId);
        for (Map.Entry<Long, String> entry : valuesByDefinitionId.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            jdbc.update("INSERT INTO item_model_attribute (item_model_id, attribute_definition_id, value) "
                            + "VALUES (?, ?, ?)",
                    itemModelId, entry.getKey(), value.trim());
        }
    }
}
