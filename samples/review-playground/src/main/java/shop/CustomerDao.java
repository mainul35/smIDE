package shop;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;

/**
 * A fixture for reviewing. See EXPECTATIONS.md; nothing is given away here.
 */
public class CustomerDao {

    private final JdbcTemplate jdbc;

    public CustomerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Customer byEmail(String email) {
        return jdbc.queryForObject(
                "SELECT * FROM customers WHERE LOWER(email) = LOWER(?)", Customer::map, email);
    }

    public List<Customer> withOrdersInStatus(String status) {
        return jdbc.query(
                "SELECT DISTINCT c.* FROM customers c"
                        + " JOIN orders o ON o.customer_id = c.id"
                        + " WHERE o.status = ?",
                Customer::map, status);
    }

    public List<Customer> activeSince(Instant since) {
        return jdbc.query(
                "SELECT * FROM customers WHERE id IN"
                        + " (SELECT customer_id FROM orders WHERE placed_at > ?)",
                Customer::map, Timestamp.from(since));
    }

    public List<Customer> inCountrySorted(String country, String sortColumn) {
        return jdbc.query(
                "SELECT * FROM customers WHERE country = '" + country + "' ORDER BY " + sortColumn,
                Customer::map);
    }

    public void importAll(List<Customer> customers) {
        for (Customer customer : customers) {
            jdbc.update("INSERT INTO customers (email, display_name, country) VALUES (?, ?, ?)",
                    customer.email, customer.displayName, customer.country);
        }
    }

    public String welcomeMessage(Customer customer) {
        return Messages.WELCOME.replace("{name}", customer.displayName);
    }
}
