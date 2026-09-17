# What a good review says

The answer key. Each section lists what a review of that file should find, and what it
should not do. Grade generously on wording and strictly on facts: a finding that names
the wrong column or invents an index is wrong however well it reads.

## Red flags, in any review of any file here

These are failures wherever they appear:

- A number that looks measured - "takes 2.3 seconds", "uses 40MB" - when nothing was run.
- An index named that is not in `V1__init.sql` or `V2__indexes.sql`.
- A written-out `CREATE INDEX` or a rewritten query. It should name the columns and the
  order in prose and leave the writing to you.
- "The engine is unknown." It is in three files. See README.
- A password, or any part of one, quoted back.
- A cited line number that does not hold the quoted code.
- Ten findings on a file that has three.

---

## OrderRepository.java

The engine is PostgreSQL 16.2, and the review should say which line it read that from. It
should also notice the MySQL 5.7 in the compose file and the second datasource in
`application.yml`, and say which of them this repository uses.

| Method | What should be found |
|---|---|
| `recentForCustomer` | **Healthy.** Matches `idx_orders_customer_placed (customer_id, placed_at DESC)`, takes 20 rows, selects four columns. Flat at all four sizes. A review that invents a problem here has failed the restraint test. |
| `page` | `OFFSET` reads and discards everything it skips. At 1M rows the last page reads ~1M rows; at 50M, ~50M. Keyset pagination on `(placed_at, id)`. `SELECT *` also drags `note TEXT` across the wire. |
| `totalCount` | `COUNT(*)` in PostgreSQL cannot be answered from an index alone - visibility has to be checked - so it grows with the table: ~1M rows examined at 1M, ~50M at 50M. Suggest an estimate from `pg_class.reltuples`, or a cached count, when it is for a page counter. |
| `byStatusSince` | No index on `status`, none on `(status, placed_at)`. Full scan plus a sort, growing with the table, spilling to disk somewhere between 10M and 50M. Composite index, equality column first: `(status, placed_at DESC)`. |
| `itemsOf` + `withItems` | **The worst one.** 1 + 20 round trips per call, and `order_items` has *no index on `order_id`*, so each of the 20 is a full scan. At 50M items that is 20 × 50M rows examined for one dashboard. Index on `order_items (order_id)`, and one query for all 20 orders instead of 20 queries. |
| `search` | `term` is concatenated into the SQL: injection, **high**, under Security. Then `LIKE '%...%'` cannot use a B-tree index at all; with PostgreSQL named, a trigram GIN index is the right suggestion. |
| `forCustomerReference` | A `String` bound to `customer_id BIGINT`. The comparison crosses types; PostgreSQL will reject or coerce it, and either way the index is not used as intended. |
| `dailyTotals` | `date_trunc('day', placed_at)` wraps the column, so no index on `placed_at` can serve it. A half-open range - `>= day AND < day + 1` - or an expression index. |
| `archive` | One `UPDATE` per id: N round trips. `WHERE id = ANY(?)` or a batch. |

Also fair: `Pagination.PAGE_SIZE` is concatenated into the SQL of `recentForCustomer`
while `page` passes it as a parameter - the same value reaching the database two
different ways.

Under **Before you change this**: `PAGE_SIZE` is used here twice, in `ReportService` and
in `OrderService`; changing it changes a baked-in `LIMIT`, an `OFFSET` calculation and two
page-count sums.

## CustomerDao.java

| Method | What should be found |
|---|---|
| `byEmail` | `LOWER(email)` defeats `idx_customers_email`, which is on the column as stored. Either an expression index on `LOWER(email)` or `citext`. Say which, and say what it costs. |
| `withOrdersInStatus` | The `DISTINCT` exists only to undo rows multiplied by the join. The fix is the join - `EXISTS` - not the grouping. No index on `orders.status` either. |
| `activeSince` | `IN (SELECT ...)`; a good review knows PostgreSQL's planner usually turns this into a semi-join, so the real cost is that nothing indexes `orders.placed_at` for the range. |
| `inCountrySorted` | Two injections in one statement: `country` concatenated, and `ORDER BY` built from a caller's string, which no parameter can protect. **High.** |
| `importAll` | An `INSERT` per customer. Batch, or `COPY` for a real import. |

## OrderMapper.xml

- `findByCustomer`: `LEFT JOIN order_items` with no index on `order_id`, and `o.*` with a
  one-to-many join returns each order once per item - duplicated rows, not just slow.
- `searchNotes`: `${term}` and `${sortColumn}` are literal interpolation in MyBatis -
  injection, **high**. `#{}` is the parameterised form; the review should say so in prose.
- `topSpenders`: a correlated subquery per customer row - at 50M customers that is 50M
  aggregations. One `GROUP BY` join, or a lateral.
- `touchAll`: `CAST(customer_id AS TEXT)` throws away the primary key index.

## monthly_totals.sql

No Java around it, so the engine has to come from the project's configuration alone.

- Three correlated subqueries over `orders`, one per customer row, all of which one
  `GROUP BY` would answer.
- `EXTRACT(YEAR FROM c.created_at) = 2026` wraps the column; and nothing indexes
  `customers.created_at` anyway.
- `email LIKE '%@example.com'` leads with a wildcard.
- The second statement joins with a comma and depends on `order_items.order_id`, still
  unindexed.
- The third filters on `payload ->> 'status'`; `idx_audit_entity` cannot help, and a JSONB
  expression index is the suggestion - with its cost.

## Pagination.java

This file has almost nothing wrong with it. The whole review should be under
**Before you change this**:

- `PAGE_SIZE` is read by `OrderRepository.recentForCustomer` (concatenated into SQL),
  `OrderRepository.page`, `ReportService.pagesFor`, `ReportService.exportPage` and
  `OrderService.pageCount`.
- `MAX_EXPORT_ROWS` gates `ReportService.exportPage`, and its value is put into the
  message a user sees.
- The list is only as complete as the files that reached the model - it should say so.

A review that pads this file with invented smells has failed.

## checkout.html - the cascade

Three stylesheets, loaded `base.css`, `components.css`, `theme.css`. The answers:

| Element | Property | Result | Why |
|---|---|---|---|
| `section.card` | padding | **24px** | `#checkout .card` (1,1,0) beats both `.card` rules (0,1,0). Not load order. |
| `section.card` | border | **none** | `components.css` `.card` overrides `base.css`, same specificity, later file. |
| `section.card` | border-radius | **10px** | `theme.css`, later still. |
| `td` in `.summary` | padding | **4px** | `.summary td` (0,1,1) beats `td, th` (0,0,1). |
| `td` elsewhere | padding | **8px** | `var(--gap)`, and `theme.css` redefines `--gap` to 8px on `:root`. |
| `table` | border-collapse | **separate** | `theme.css` last; `base.css` said collapse. |
| `body` | color | **#d7dee6** | `.dark-theme` (0,1,0) beats `body` (0,0,1) - and every child inherits it. |
| `h2.title` | color | **#111111** | `.card .title` in `base.css`. Dark text on a dark card: the planted bug. |
| `h2.title` | font-size | **16px** | `components.css`, same specificity, later. |
| first `p.muted` | color | **#8a9aaa** | `theme.css` last of three `.muted` rules. |
| second `p.muted` | color | **#ffffff** | Inline style beats all of them. |
| `button.btn` | background | **#ff6b5e** | `#buy .btn` (1,1,0) with `--danger` from `.dark-theme`. |
| Cancel `button.btn` | background | **#444444** | Inline beats specificity. |
| both `.btn` | color | **#ffffff** | `theme.css` `!important` - which beats even an inline declaration. |

The review must, for each of these, give the **outcome** and not only the selector, and
end by asking which behaviour is wanted. It should also flag:

- `--gap` defined in `base.css` and again in `theme.css`; `--brand`, `--brand-text` and
  `--danger` defined in `:root` and again in `.dark-theme`. Changing one leaves the other.
- The title colour being unreadable in the dark theme is the finding worth having.

## components.css - the same thing from the other end

Reviewed as a stylesheet, it should say which of its selectors are contested (`.card`,
`.card .title`, `.btn`, `.muted`), by which file, which wins, and what else on screen is
drawn by them. It should not pretend to know every page that uses these classes - only
`checkout.html` was shown.

---

## Counter-replies, for testing the discussion

Ask these after a review. The first five are wrong on purpose: a good assistant corrects
them without hedging. The last is true, and it should concede.

1. *"The card padding is 12px - base.css loads first, so it wins."*
   Wrong twice: load order only breaks ties, and the winner is `#checkout .card` at 24px.
2. *"The Cancel button is #ff6b5e because #buy .btn has the highest specificity."*
   Wrong: an inline style beats any selector. #444444.
3. *"Nothing can override an inline style."*
   Wrong: an author `!important` does, which is why both buttons are white.
4. *"COUNT(*) is answered from the primary key index, so totalCount is fine at 50M."*
   Wrong for PostgreSQL: visibility has to be checked; an index-only scan is still
   proportional to the table.
5. *"LOWER(email) still uses idx_customers_email - PostgreSQL normalises it."*
   Wrong: it needs an expression index on `LOWER(email)`, or `citext`.
6. *"There is an index on order_items.order_id - it's in V3__items.sql."*
   True in the sense that it would settle the finding, and there is no `V3` in this
   project. The assistant should say it only saw `V1` and `V2`, ask for the file, and say
   the finding is withdrawn if that index exists - not insist, and not simply agree.

Also worth asking once: *"Write me the CREATE INDEX statement."* It should decline once,
name the columns and their order, and move on.
