package com.piecetrack.repository;

import com.piecetrack.domain.ItemModel;
import com.piecetrack.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class ItemModelRepository {

    private static final RowMapper<ItemModel> MAPPER = (rs, rowNum) -> new ItemModel(
            rs.getLong("id"),
            rs.getString("model_code"),
            rs.getString("model_name"),
            rs.getLong("category_id"),
            rs.getString("hsn_code"),
            rs.getBigDecimal("gst_rate"),
            rs.getObject("default_sale_price") == null ? null : Money.ofPaisa(rs.getLong("default_sale_price")),
            rs.getInt("is_active") == 1,
            rs.getString("notes")
    );

    private final JdbcTemplate jdbc;

    public ItemModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ItemModel> findById(long id) {
        return jdbc.query("SELECT * FROM item_model WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public boolean existsByModelCode(String modelCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM item_model WHERE model_code = ?", Integer.class, modelCode);
        return count != null && count > 0;
    }

    public boolean hasAnyPieces(long itemModelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM piece WHERE item_model_id = ?", Integer.class, itemModelId);
        return count != null && count > 0;
    }

    public long create(ItemModel m) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO item_model (model_code, model_name, category_id, hsn_code, gst_rate,
                            default_sale_price, is_active, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindFields(ps, m);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void update(ItemModel m) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    UPDATE item_model SET model_code = ?, model_name = ?, category_id = ?, hsn_code = ?,
                            gst_rate = ?, default_sale_price = ?, is_active = ?, notes = ?,
                            updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')
                    WHERE id = ?
                    """);
            int nextParam = bindFields(ps, m);
            ps.setLong(nextParam, m.id());
            return ps;
        });
    }

    private int bindFields(PreparedStatement ps, ItemModel m) throws java.sql.SQLException {
        int i = 1;
        ps.setString(i++, m.modelCode());
        ps.setString(i++, m.modelName());
        ps.setLong(i++, m.categoryId());
        ps.setString(i++, m.hsnCode());
        ps.setBigDecimal(i++, m.gstRate());
        if (m.defaultSalePrice() == null) {
            ps.setNull(i++, java.sql.Types.INTEGER);
        } else {
            ps.setLong(i++, m.defaultSalePrice().paisa());
        }
        ps.setInt(i++, m.active() ? 1 : 0);
        ps.setString(i++, m.notes());
        return i;
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE item_model SET is_active = ?, " +
                "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?", active ? 1 : 0, id);
    }

    /** Only legal when {@link #hasAnyPieces} is false - enforced by the service, not here. */
    public void delete(long id) {
        jdbc.update("DELETE FROM item_photo WHERE item_model_id = ?", id);
        jdbc.update("DELETE FROM item_model WHERE id = ?", id);
    }

    public List<ItemModelSummary> search(ItemModelSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder("""
                SELECT im.*, c.name AS category_name,
                       (SELECT COUNT(*) FROM piece p WHERE p.item_model_id = im.id AND p.state = 'IN_STOCK')
                           AS in_stock_count
                FROM item_model im
                JOIN category c ON c.id = im.category_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (criteria.activeOnly()) {
            sql.append(" AND im.is_active = 1");
        }
        if (criteria.categoryId() != null) {
            sql.append(" AND im.category_id = ?");
            params.add(criteria.categoryId());
        }
        if (criteria.searchText() != null && !criteria.searchText().isBlank()) {
            // M15: material/colour/finish were dedicated columns before the generic rename;
            // the equivalent search now has to look inside the free-form attribute values
            // instead, since there is no longer a fixed set of columns to name here.
            sql.append(" AND (im.model_name LIKE ? OR im.model_code LIKE ? "
                    + "OR EXISTS (SELECT 1 FROM item_model_attribute ima "
                    + "WHERE ima.item_model_id = im.id AND ima.value LIKE ?))");
            String like = "%" + criteria.searchText().trim() + "%";
            for (int i = 0; i < 3; i++) {
                params.add(like);
            }
        }

        sql.append(" ORDER BY im.model_name");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new ItemModelSummary(
                MAPPER.mapRow(rs, rowNum),
                rs.getString("category_name"),
                rs.getLong("in_stock_count")
        ), params.toArray());
    }
}
