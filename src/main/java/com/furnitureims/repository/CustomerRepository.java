package com.furnitureims.repository;

import com.furnitureims.domain.Customer;
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
public class CustomerRepository {

    private static final RowMapper<Customer> MAPPER = (rs, rowNum) -> new Customer(
            rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("email"),
            rs.getString("address_line1"), rs.getString("address_line2"), rs.getString("city"),
            rs.getString("pincode"), rs.getString("state_name"), rs.getString("state_code"),
            rs.getString("gstin"), rs.getString("notes"));

    private final JdbcTemplate jdbc;

    public CustomerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Customer> findById(long id) {
        return jdbc.query("SELECT * FROM customer WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** FR-SAL-02: "typing a known number offers the existing customer" - most recently
     *  added first, in case the same phone was reused for a different household member. */
    public Optional<Customer> findByPhone(String phone) {
        return jdbc.query("SELECT * FROM customer WHERE phone = ? ORDER BY id DESC LIMIT 1", MAPPER, phone)
                .stream().findFirst();
    }

    public List<Customer> search(String text) {
        String like = "%" + text.trim() + "%";
        return jdbc.query("SELECT * FROM customer WHERE name LIKE ? OR phone LIKE ? ORDER BY name",
                MAPPER, like, like);
    }

    public long create(Customer c) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO customer (name, phone, email, address_line1, address_line2, city, pincode,
                            state_name, state_code, gstin, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.name());
            ps.setString(2, c.phone());
            ps.setString(3, c.email());
            ps.setString(4, c.addressLine1());
            ps.setString(5, c.addressLine2());
            ps.setString(6, c.city());
            ps.setString(7, c.pincode());
            ps.setString(8, c.stateName());
            ps.setString(9, c.stateCode());
            ps.setString(10, c.gstin());
            ps.setString(11, c.notes());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }
}
