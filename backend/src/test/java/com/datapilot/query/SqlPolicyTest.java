package com.datapilot.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlPolicyTest {
    private final SqlPolicy policy = new SqlPolicy();

    @Test
    void acceptsCommonAnalyticsSelects() {
        List<String> allowed = List.of(
                "SELECT o.order_id, o.total_amount FROM sales_order o WHERE o.status = 'PAID' LIMIT 10",
                "SELECT c.region_name, SUM(p.amount) AS sales FROM sales_order o JOIN customer c ON c.customer_id = o.customer_id JOIN payment p ON p.order_id = o.order_id WHERE p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') GROUP BY c.region_name HAVING SUM(p.amount) > 0 ORDER BY sales DESC",
                "SELECT (SELECT COUNT(*) FROM payment p WHERE p.status = 'SUCCEEDED') AS paid_count FROM sales_order o LIMIT 1",
                "WITH paid AS (SELECT p.order_id, p.amount AS paid_amount FROM payment p WHERE p.status = 'SUCCEEDED') SELECT paid.order_id, SUM(paid.paid_amount) AS total_paid FROM paid GROUP BY paid.order_id",
                "SELECT m.paid_month, m.paid_amount FROM v_monthly_paid_sales m ORDER BY m.paid_month",
                "SELECT COUNT(*), AVG(o.total_amount) FROM sales_order o");
        allowed.forEach(sql -> assertTrue(policy.validate(sql).allowed(), () -> sql + " => " + policy.validate(sql)));
    }

    @Test
    void rejectsWritesMultipleStatementsUnauthorizedObjectsAndFields() {
        List<String> denied = List.of(
                "SELECT 1; SELECT 2",
                "UPDATE sales_order SET status = 'PAID'",
                "DELETE FROM sales_order",
                "DROP TABLE sales_order",
                "ALTER TABLE sales_order ADD x INT",
                "TRUNCATE TABLE payment",
                "SELECT * FROM mysql.user",
                "WITH secret AS (SELECT user FROM mysql.user) SELECT secret.user FROM secret",
                "SELECT o.internal_secret FROM sales_order o",
                "SELECT c.email FROM customer c",
                "SELECT p.payment_reference FROM payment p",
                "SELECT r.refund_reference FROM refund r",
                "SELECT o.order_id FROM datapilot.sales_order o",
                "SELECT SLEEP(10) FROM sales_order");
        denied.forEach(sql -> assertFalse(policy.validate(sql).allowed(), sql));
    }

    @Test
    void rejectsDangerousSyntaxAndUnknownFunctionsEvenWithComments() {
        List<String> denied = List.of(
                "SELECT LOAD_FILE('/etc/passwd') FROM sales_order",
                "SELECT BENCHMARK(1000000, MD5('x')) FROM sales_order",
                "SELECT GET_LOCK('x', 5) FROM sales_order",
                "SELECT @x := order_id FROM sales_order",
                "SELECT order_id FROM sales_order INTO OUTFILE '/tmp/a'",
                "SELECT order_id FROM sales_order FOR UPDATE",
                "SELECT order_id FROM sales_order UNION SELECT order_id FROM payment",
                "SELECT ROW_NUMBER() OVER (ORDER BY order_id) AS row_num FROM sales_order",
                "SELECT order_id FROM sales_order /*+ MAX_EXECUTION_TIME(999999) */",
                "SELECT order_id FROM sales_order; --\n DROP TABLE sales_order",
                "SELECT * FROM sales_order WHERE status = 'PAID' #\n UNION SELECT user, host FROM mysql.user");
        denied.forEach(sql -> assertFalse(policy.validate(sql).allowed(), sql));
    }
}
