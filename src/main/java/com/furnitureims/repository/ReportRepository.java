package com.furnitureims.repository;

import com.furnitureims.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** The one query FR-RPT-03 needs that no existing repository provides: sales lines joined
 *  to their model/category, restricted to ACTIVE invoices in a date range, with any line
 *  that was later returned excluded via {@code NOT EXISTS} - a returned piece was not
 *  really "sold" for period-profit purposes, even though its {@code sales_line} row (and
 *  the {@code sales_return_line} crediting it back) are both kept forever. */
@Repository
public class ReportRepository {

    private static final RowMapper<SalesProfitLineRow> MAPPER = (rs, rowNum) -> new SalesProfitLineRow(
            rs.getLong("sales_invoice_id"),
            LocalDate.parse(rs.getString("invoice_date")),
            rs.getLong("item_model_id"),
            rs.getString("model_name"),
            rs.getLong("category_id"),
            rs.getString("category_name"),
            Money.ofPaisa(rs.getLong("taxable_value")),
            Money.ofPaisa(rs.getLong("cgst_amount")),
            Money.ofPaisa(rs.getLong("sgst_amount")),
            Money.ofPaisa(rs.getLong("igst_amount")),
            Money.ofPaisa(rs.getLong("cost_at_sale"))
    );

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SalesProfitLineRow> salesLinesInPeriod(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT sl.sales_invoice_id, si.invoice_date, sl.item_model_id, im.model_name,
                       im.category_id, c.name AS category_name, sl.taxable_value, sl.cgst_amount,
                       sl.sgst_amount, sl.igst_amount, sl.cost_at_sale
                FROM sales_line sl
                JOIN sales_invoice si ON si.id = sl.sales_invoice_id
                JOIN item_model im ON im.id = sl.item_model_id
                JOIN category c ON c.id = im.category_id
                WHERE si.status = 'ACTIVE'
                  AND si.invoice_date >= ?
                  AND si.invoice_date <= ?
                  AND NOT EXISTS (SELECT 1 FROM sales_return_line srl WHERE srl.sales_line_id = sl.id)
                ORDER BY si.invoice_date
                """, MAPPER, from.toString(), to.toString());
    }
}
