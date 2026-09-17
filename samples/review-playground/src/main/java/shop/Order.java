package shop;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** A fixture for reviewing. */
public class Order {

    public long id;
    public long customerId;
    public String status;
    public long totalCents;
    public Instant placedAt;
    public List<OrderItem> items;

    public static Order map(ResultSet rs, int row) throws SQLException {
        Order order = new Order();
        order.id = rs.getLong("id");
        order.status = rs.getString("status");
        order.totalCents = rs.getLong("total_cents");
        order.placedAt = rs.getTimestamp("placed_at").toInstant();
        return order;
    }
}
