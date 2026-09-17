package shop;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A fixture for reviewing. It compiles nowhere and runs nowhere: it is here to be read by
 * the assistant's review. What is wrong with it is written down in EXPECTATIONS.md, not
 * here, so that the review has to find it rather than quote it back.
 */
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Order> recentForCustomer(long customerId) {
        return jdbc.query(
                "SELECT id, status, total_cents, placed_at FROM orders"
                        + " WHERE customer_id = ? ORDER BY placed_at DESC LIMIT " + Pagination.PAGE_SIZE,
                Order::map, customerId);
    }

    public List<Order> page(int pageNumber) {
        return jdbc.query(
                "SELECT * FROM orders ORDER BY placed_at DESC LIMIT ? OFFSET ?",
                Order::map, Pagination.PAGE_SIZE, pageNumber * Pagination.PAGE_SIZE);
    }

    public long totalCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
    }

    public List<Order> byStatusSince(String status, Instant since) {
        return jdbc.query(
                "SELECT * FROM orders WHERE status = ? AND placed_at >= ? ORDER BY placed_at DESC",
                Order::map, status, Timestamp.from(since));
    }

    public List<OrderItem> itemsOf(long orderId) {
        return jdbc.query("SELECT * FROM order_items WHERE order_id = ?", OrderItem::map, orderId);
    }

    public List<Order> withItems(long customerId) {
        List<Order> orders = recentForCustomer(customerId);
        List<Order> full = new ArrayList<>();
        for (Order order : orders) {
            order.items = itemsOf(order.id);
            full.add(order);
        }
        return full;
    }

    public List<Order> search(String term) {
        String sql = "SELECT * FROM orders WHERE note LIKE '%" + term + "%' ORDER BY placed_at DESC";
        return jdbc.query(sql, Order::map);
    }

    public List<Order> forCustomerReference(String customerReference) {
        return jdbc.query("SELECT * FROM orders WHERE customer_id = ?", Order::map, customerReference);
    }

    public List<DailyTotal> dailyTotals(Instant day) {
        return jdbc.query(
                "SELECT date_trunc('day', placed_at) AS d, SUM(total_cents) AS cents"
                        + " FROM orders WHERE date_trunc('day', placed_at) = ?"
                        + " GROUP BY 1 ORDER BY 1 DESC",
                DailyTotal::map, Timestamp.from(day));
    }

    public void archive(List<Long> orderIds) {
        for (Long id : orderIds) {
            jdbc.update("UPDATE orders SET status = 'ARCHIVED' WHERE id = ?", id);
        }
    }
}
