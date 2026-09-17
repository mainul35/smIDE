package shop;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** A fixture for reviewing. */
public class DailyTotal {

    public Instant day;
    public long cents;

    public static DailyTotal map(ResultSet rs, int row) throws SQLException {
        DailyTotal total = new DailyTotal();
        total.day = rs.getTimestamp("d").toInstant();
        total.cents = rs.getLong("cents");
        return total;
    }
}
