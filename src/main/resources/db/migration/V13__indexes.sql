CREATE INDEX idx_piece_purchase_line
    ON piece(purchase_line_id);

CREATE INDEX idx_sales_return_line_sales_line
    ON sales_return_line(sales_line_id);

CREATE INDEX idx_audit_log_logged_at
    ON audit_log(logged_at, id);