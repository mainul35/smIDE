# smIDE crash report server

Receives the crash reports smIDE users choose to send, and serves a dashboard for reading
and triaging them. One jar, one data folder, no database or framework to install.

## Run it

```bash
mvn -pl smide-crash-server -am package
java -jar smide-crash-server/target/smide-crash-server-0.1.0-SNAPSHOT.jar --port 8787 --data ./crash-data
```

| Option | Default | |
|---|---|---|
| `--port` | `8787` | |
| `--host` | `0.0.0.0` | `127.0.0.1` to accept reports from this machine only |
| `--data` | `./crash-data` | where the reports and the token are kept |
| `--token` | made on first start | also read from `SMIDE_CRASH_TOKEN` |

Without `--token`, the first start makes one, prints it, and keeps it in `<data>/token` so a
restart does not lock out every IDE that was given it.

## Point smIDE at it

**Settings > Tools > Crash Reports**: the server's address and its token. *Send a test
report* checks both before you rely on them; the test shows up in the dashboard.

Nothing is ever sent on its own. When something fails, smIDE saves a report under
`~/.smide/logs/crashes` and shows a dialog; the report goes to this server only when the
person in front of it presses **Send report**, and what goes is exactly what the dialog's
*Details* showed them.

## The dashboard

Open the server's address in a browser and give it the token. Reports of the same failure
are grouped into one row by their signature - the exception and the first frames it went
through - with how often, since when, on which versions and how many systems. Each failure
can be marked *open*, *investigating*, *fixed* or *ignored*, with a note for whoever looks
next. A failure marked fixed that is reported again reopens itself.

## What it will and will not do

- Everything under `/api` needs the token as `Authorization: Bearer <token>`.
- A report over 1 MB is refused; longer fields are trimmed.
- The same report sent twice is kept once.
- Report text is shown in the dashboard as text, never as markup, and the page's content
  policy allows no script but its own file. Reports are whatever a client sent; the person
  reading them should not be able to be attacked through one.
- It speaks plain HTTP. Anything beyond your own network should reach it through a reverse
  proxy that terminates TLS, or the token travels in the clear.
