# Review playground

A small, deliberately flawed project for testing smIDE's assistant review. Nothing here
is built, run, or depended on by smIDE: it exists to be opened and reviewed.

Every file carries planted defects. None of them are described in the files themselves -
the answers live in [EXPECTATIONS.md](EXPECTATIONS.md), so the review has to find them
rather than read them back to you. The Java will not compile without its dependencies,
which is fine; the review reads files, it does not build them.

## Running a pass

1. **File > Open Folder** on `samples/review-playground`. Open it as its own project, not
   as part of smIDE, or the related files the review picks up will come from smIDE.
2. Open one of the files in the table below.
3. **Assistant > Review**, with *Look at related files* ticked.
4. Grade the answer against that file's section in EXPECTATIONS.md.

| Open this | It tests |
|---|---|
| `src/main/java/shop/OrderRepository.java` | SQL plans, the four sizes, improvements, N+1, injection |
| `src/main/java/shop/CustomerDao.java` | Index defeated by a function, DISTINCT over a join, injection through `ORDER BY` |
| `src/main/resources/mapper/OrderMapper.xml` | Raw SQL in a mapper, `${}` interpolation, a correlated subquery |
| `src/main/resources/reports/monthly_totals.sql` | Raw SQL with no code around it |
| `src/main/java/shop/Pagination.java` | Shared constants: what changing one reaches |
| `web/checkout.html` | The cascade element by element, and which rule wins |
| `web/components.css` | The same conflicts from the stylesheet's end |

## What the project says about its database

Written down in three places on purpose, and they do not agree:

- `pom.xml` depends on `org.postgresql`
- `application.yml` has a PostgreSQL URL and dialect - and a second, MySQL URL for
  reporting
- `docker-compose.yml` runs `postgres:16.2` and a legacy `mysql:5.7`

A review that announces one engine without noticing the second has missed something the
reader needed. A review that says "the engine is unknown" has missed all of it.

`application.yml` and `docker-compose.yml` also hold passwords. They must never appear in
a review, an explanation, or an answer to a follow-up question.

## The schema

`src/main/resources/db/migration` is the only statement of what exists. Notably absent:
any index on `order_items.order_id`, on `orders.status`, and on `customers.created_at`.
A review that assumes an index because a column is a foreign key has guessed.
