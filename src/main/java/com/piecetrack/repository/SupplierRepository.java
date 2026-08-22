package com.piecetrack.repository;

import com.piecetrack.domain.Supplier;
import com.piecetrack.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class SupplierRepository {

    private static final RowMapper<Supplier> MAPPER = (rs, rowNum) -> new Supplier(
            rs.getLong("id"), rs.getString("name"), rs.getString("gstin"),
            rs.getString("address_line1"), rs.getString("address_line2"), rs.getString("city"),
            rs.getString("pincode"), rs.getString("state_name"), rs.getString("state_code"),
            rs.getString("phone"), rs.getString("email"), rs.getString("contact_person"),
            Money.ofPaisa(rs.getLong("opening_balance")), rs.getInt("is_active") == 1, rs.getString("notes"));

    private final JdbcTemplate jdbc;

    public SupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Supplier> findAllActive() {
        return jdbc.query("SELECT * FROM supplier WHERE is_active = 1 ORDER BY name", MAPPER);
    }

    public List<Supplier> findAll() {
        return jdbc.query("SELECT * FROM supplier ORDER BY name", MAPPER);
    }

    public Optional<Supplier> findById(long id) {
        return jdbc.query("SELECT * FROM supplier WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public long create(Supplier s) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO supplier (name, gstin, address_line1, address_line2, city, pincode,
                            state_name, state_code, phone, email, contact_person, opening_balance,
                            is_active, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bind(ps, s);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void update(Supplier s) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    UPDATE supplier SET name = ?, gstin = ?, address_line1 = ?, address_line2 = ?,
                            city = ?, pincode = ?, state_name = ?, state_code = ?, phone = ?, email = ?,
                            contact_person = ?, opening_balance = ?, is_active = ?, notes = ?,
                            updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')
                    WHERE id = ?
                    """);
            int next = bind(ps, s);
            ps.setLong(next, s.id());
            return ps;
        });
    }

    private int bind(PreparedStatement ps, Supplier s) throws java.sql.SQLException {
        int i = 1;
        ps.setString(i++, s.name());
        ps.setString(i++, s.gstin());
        ps.setString(i++, s.addressLine1());
        ps.setString(i++, s.addressLine2());
        ps.setString(i++, s.city());
        ps.setString(i++, s.pincode());
        ps.setString(i++, s.stateName());
        ps.setString(i++, s.stateCode());
        ps.setString(i++, s.phone());
        ps.setString(i++, s.email());
        ps.setString(i++, s.contactPerson());
        ps.setLong(i++, s.openingBalance().paisa());
        ps.setInt(i++, s.active() ? 1 : 0);
        ps.setString(i++, s.notes());
        return i;
    }

    public void setActive(long id, boolean active) {
        jdbc.update("UPDATE supplier SET is_active = ?, " +
                "updated_at = strftime('%Y-%m-%dT%H:%M:%S','now','localtime') WHERE id = ?", active ? 1 : 0, id);
    }

}
