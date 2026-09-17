package shop;

import java.sql.ResultSet;
import java.sql.SQLException;

/** A fixture for reviewing. */
public class Customer {

    public long id;
    public String email;
    public String displayName;
    public String country;

    public static Customer map(ResultSet rs, int row) throws SQLException {
        Customer customer = new Customer();
        customer.id = rs.getLong("id");
        customer.email = rs.getString("email");
        customer.displayName = rs.getString("display_name");
        customer.country = rs.getString("country");
        return customer;
    }
}
