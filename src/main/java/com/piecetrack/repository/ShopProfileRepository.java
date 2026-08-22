package com.piecetrack.repository;

import com.piecetrack.domain.ShopProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ShopProfileRepository {

    private static final RowMapper<ShopProfile> MAPPER = (rs, rowNum) -> new ShopProfile(
            rs.getString("shop_name"),
            rs.getString("address_line1"),
            rs.getString("address_line2"),
            rs.getString("city"),
            rs.getString("pincode"),
            rs.getString("state_name"),
            rs.getString("state_code"),
            rs.getString("gstin"),
            ShopProfile.RegistrationType.valueOf(rs.getString("registration_type")),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getString("logo_path"),
            rs.getString("invoice_declaration"),
            rs.getString("signature_text")
    );

    private final JdbcTemplate jdbc;

    public ShopProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM shop_profile WHERE id = 1", Integer.class);
        return count != null && count > 0;
    }

    public Optional<ShopProfile> find() {
        return jdbc.query("SELECT * FROM shop_profile WHERE id = 1", MAPPER).stream().findFirst();
    }

    /** Upserts the single shop_profile row (id is always 1 - enforced by a CHECK constraint). */
    public void save(ShopProfile profile) {
        jdbc.update("""
                INSERT INTO shop_profile (id, shop_name, address_line1, address_line2, city, pincode,
                        state_name, state_code, gstin, registration_type, phone, email, logo_path,
                        invoice_declaration, signature_text, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))
                ON CONFLICT(id) DO UPDATE SET
                        shop_name = excluded.shop_name,
                        address_line1 = excluded.address_line1,
                        address_line2 = excluded.address_line2,
                        city = excluded.city,
                        pincode = excluded.pincode,
                        state_name = excluded.state_name,
                        state_code = excluded.state_code,
                        gstin = excluded.gstin,
                        registration_type = excluded.registration_type,
                        phone = excluded.phone,
                        email = excluded.email,
                        logo_path = excluded.logo_path,
                        invoice_declaration = excluded.invoice_declaration,
                        signature_text = excluded.signature_text,
                        updated_at = strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime')
                """,
                profile.shopName(), profile.addressLine1(), profile.addressLine2(), profile.city(), profile.pincode(),
                profile.stateName(), profile.stateCode(), profile.gstin(),
                profile.registrationType().name(), profile.phone(), profile.email(), profile.logoPath(),
                profile.invoiceDeclaration(), profile.signatureText());
    }
}
