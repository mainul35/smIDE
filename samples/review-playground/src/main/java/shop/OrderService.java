package shop;

import java.util.List;

/**
 * A fixture for reviewing.
 */
public class OrderService {

    private final OrderRepository orders;
    private final CustomerDao customers;

    public OrderService(OrderRepository orders, CustomerDao customers) {
        this.orders = orders;
        this.customers = customers;
    }

    public List<Order> dashboard(long customerId) {
        return orders.withItems(customerId);
    }

    public String greet(String email) {
        Customer customer = customers.byEmail(email);
        return Messages.WELCOME.replace("{name}", customer.displayName);
    }

    public int pageCount() {
        return (int) Math.ceil((double) orders.totalCount() / Pagination.PAGE_SIZE);
    }
}
