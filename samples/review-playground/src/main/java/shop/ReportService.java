package shop;

import java.util.List;

/**
 * A fixture for reviewing.
 */
public class ReportService {

    private final OrderRepository orders;

    public ReportService(OrderRepository orders) {
        this.orders = orders;
    }

    public List<Order> exportPage(int pageNumber) {
        if (pageNumber * Pagination.PAGE_SIZE > Pagination.MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException(
                    Messages.EXPORT_TRUNCATED.replace("{rows}", String.valueOf(Pagination.MAX_EXPORT_ROWS)));
        }
        return orders.page(pageNumber);
    }

    public int pagesFor(long total) {
        return (int) Math.ceil((double) total / Pagination.PAGE_SIZE);
    }
}
