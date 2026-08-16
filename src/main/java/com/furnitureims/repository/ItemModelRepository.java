package com.furnitureims.repository;

import com.furnitureims.domain.ItemModel;
import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
            rs.getBigDecimal("length_cm"),
            rs.getBigDecimal("width_cm"),
            rs.getBigDecimal("height_cm"),
            rs.getString("material"),
            rs.getString("finish"),
            rs.getString("colour"),
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
                            length_cm, width_cm, height_cm, material, finish, colour, default_sale_price,
                            is_active, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                            gst_rate = ?, length_cm = ?, width_cm = ?, height_cm = ?, material = ?,
                            finish = ?, colour = ?, default_sale_price = ?, is_active = ?, notes = ?,
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
        ps.setBigDecimal(i++, m.lengthCm());
        ps.setBigDecimal(i++, m.widthCm());
        ps.setBigDecimal(i++, m.heightCm());
        ps.setString(i++, m.material());
        ps.setString(i++, m.finish());
        ps.setString(i++, m.colour());
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
            sql.append(" AND (im.model_name LIKE ? OR im.model_code LIKE ? OR im.material LIKE ? " +
                    "OR im.colour LIKE ? OR im.finish LIKE ?)");
            String like = "%" + criteria.searchText().trim() + "%";
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        appendRange(sql, params, "im.length_cm", criteria.minLengthCm(), criteria.maxLengthCm());
        appendRange(sql, params, "im.width_cm", criteria.minWidthCm(), criteria.maxWidthCm());
        appendRange(sql, params, "im.height_cm", criteria.minHeightCm(), criteria.maxHeightCm());

        sql.append(" ORDER BY im.model_name");

        return jdbc.query(sql.toString(), (rs, rowNum) -> new ItemModelSummary(
                MAPPER.mapRow(rs, rowNum),
                rs.getString("category_name"),
                rs.getLong("in_stock_count")
        ), params.toArray());
    }

    private void appendRange(StringBuilder sql, List<Object> params, String column,
                              BigDecimal min, BigDecimal max) {
        if (min != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            params.add(min);
        }
        if (max != null) {
            sql.append(" AND ").append(column).append(" <= ?");
            params.add(max);
        }
    }
}
