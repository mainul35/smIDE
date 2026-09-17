package shop;

/**
 * A fixture for reviewing. Shared on purpose: several callers read these, and the review
 * is meant to say what changing one of them reaches.
 */
public final class Pagination {

    public static final int PAGE_SIZE = 20;

    public static final int MAX_EXPORT_ROWS = 50_000;

    private Pagination() {
    }

    public static int offsetFor(int pageNumber) {
        return pageNumber * PAGE_SIZE;
    }
}
