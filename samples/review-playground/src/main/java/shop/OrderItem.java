package shop;

import java.sql.ResultSet;
import java.sql.SQLException;

/** A fixture for reviewing. */
public class OrderItem {

    public long id;
    public long orderId;
    public String sku;
    public int quantity;

    public static OrderItem map(ResultSet rs, int row) throws SQLException {
        OrderItem item = new OrderItem();
        item.id = rs.getLong("id");
        item.orderId = rs.getLong("order_id");
        item.sku = rs.getString("sku");
        item.quantity = rs.getInt("quantity");
        return item;
    }
}
