/*
 * The dashboard. One rule above the rest: nothing from a report is ever put on the page as
 * markup. Every value goes in through textContent, because a report is whatever a client
 * chose to send and this page is opened by whoever triages them.
 */
"use strict";

const TOKEN_KEY = "smide-crash-token";
const $ = (id) => document.getElementById(id);

let token = readToken();
let selected = null;        // the signature on show
let selectedReport = null;  // the occurrence on show
let searchTimer = null;

// ------------------------------------------------------------------ token

function readToken() {
    try {
        return sessionStorage.getItem(TOKEN_KEY) || "";
    } catch (e) {
        return "";
    }
}

function keepToken(value) {
    token = value;
    try {
        if (value) {
            sessionStorage.setItem(TOKEN_KEY, value);
        } else {
            sessionStorage.removeItem(TOKEN_KEY);
        }
    } catch (e) {
        // A private window may refuse; the token then lasts as long as the page.
    }
}

function showSignIn(message) {
    $("app").hidden = true;
    $("signOut").hidden = true;
    $("signIn").hidden = false;
    $("tokenError").textContent = message || "";
    $("token").focus();
}

function showApp() {
    $("signIn").hidden = true;
    $("app").hidden = false;
    $("signOut").hidden = false;
}

// ------------------------------------------------------------------ requests

class Unauthorised extends Error {}

async function api(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: {"Authorization": "Bearer " + token, "Content-Type": "application/json", ...(options.headers || {})},
    });
    if (response.status === 401) {
        throw new Unauthorised();
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(body.error || ("The server answered " + response.status));
    }
    return body;
}

async function guarded(work) {
    try {
        await work();
    } catch (e) {
        if (e instanceof Unauthorised) {
            keepToken("");
            showSignIn("That token was not accepted.");
        } else {
            $("sub").textContent = "Could not load: " + e.message;
        }
    }
}

// ------------------------------------------------------------------ list

async function load() {
    await guarded(async () => {
        const status = $("status").value;
        const q = $("search").value.trim();
        const params = new URLSearchParams({status});
        if (q) {
            params.set("q", q);
        }
        const [summary, failures] = await Promise.all([
            api("/api/summary"),
            api("/api/failures?" + params.toString()),
        ]);
        showApp();
        renderSummary(summary);
        renderFailures(failures, status, q);
        $("sub").textContent = "Failures people chose to send, grouped by what went wrong. Updated "
            + new Date().toLocaleTimeString() + ".";
    });
}

function renderSummary(summary) {
    $("tReports").textContent = number(summary.reports);
    $("tDay").textContent = number(summary.lastDay);
    $("tOpen").textContent = number(summary.failures.open);
    $("tInvestigating").textContent = number(summary.failures.investigating);
    $("tFixed").textContent = number(summary.failures.fixed);
    $("tIgnored").textContent = number(summary.failures.ignored);
}

function renderFailures(failures, status, q) {
    const list = $("failures");
    list.replaceChildren();
    const empty = $("empty");
    if (failures.length === 0) {
        empty.hidden = false;
        empty.textContent = q ? "Nothing matches that search."
            : status === "open" ? "Nothing is open. Either all is well, or nobody has sent anything yet."
            : "Nothing here.";
        return;
    }
    empty.hidden = true;
    for (const failure of failures) {
        const item = document.createElement("li");
        item.tabIndex = 0;
        item.dataset.signature = failure.signature;
        if (failure.signature === selected) {
            item.classList.add("selected");
        }

        const headline = element("span", "headline", headlineOf(failure));
        const count = element("span", "count", number(failure.occurrences) + "×");
        count.title = failure.occurrences + " reports";

        const meta = element("span", "meta");
        meta.append(pill(failure.status, failure.status));
        if (failure.kind && failure.kind !== "exception") {
            meta.append(pill(kindName(failure.kind), "kind"));
        }
        meta.append(element("span", "", failure.where || "unknown place"));
        meta.append(element("span", "", "last " + ago(failure.lastSeen)));
        if (failure.versions) {
            meta.append(element("span", "", "v " + failure.versions));
        }
        if (failure.notes > 0) {
            meta.append(element("span", "", failure.notes + (failure.notes === 1 ? " note" : " notes")));
        }

        item.append(headline, count, meta);
        item.addEventListener("click", () => select(failure));
        item.addEventListener("keydown", (e) => {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                select(failure);
            }
        });
        list.append(item);
    }
}

// ------------------------------------------------------------------ detail

async function select(failure) {
    selected = failure.signature;
    selectedReport = null;
    for (const item of $("failures").children) {
        item.classList.toggle("selected", item.dataset.signature === selected);
    }
    $("detail").hidden = false;
    $("report").hidden = true;
    $("dKind").replaceChildren(pill(failure.status, failure.status));
    if (failure.kind && failure.kind !== "exception") {
        $("dKind").append(" ", pill(kindName(failure.kind), "kind"));
    }
    $("dTitle").textContent = headlineOf(failure);
    $("dWhere").textContent = "In " + (failure.where || "an unknown place");
    $("dSeen").textContent = number(failure.occurrences) + " times on " + number(failure.systems)
        + (failure.systems === 1 ? " system" : " systems") + ", first " + ago(failure.firstSeen)
        + ", last " + ago(failure.lastSeen);
    $("dVersions").textContent = failure.versions || "-";
    $("dSignature").textContent = failure.signature;
    $("dStatus").value = failure.status;
    $("dComment").value = failure.comment || "";
    $("dSaved").textContent = "";

    await guarded(async () => {
        const reports = await api("/api/failures/" + encodeURIComponent(failure.signature));
        const list = $("occurrences");
        list.replaceChildren();
        for (const report of reports) {
            const item = document.createElement("li");
            item.tabIndex = 0;
            item.append(element("span", "when", new Date(report.received).toLocaleString()));
            item.append(element("span", "sys", [report.version, report.os].filter(Boolean).join(" · ")));
            if (report.note) {
                item.append(element("span", "note", "“" + report.note + "”"));
            }
            const open = () => showReport(report.id, item);
            item.addEventListener("click", open);
            item.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    open();
                }
            });
            list.append(item);
        }
        if (reports.length > 0) {
            showReport(reports[0].id, list.firstElementChild);
        }
    });
}

async function showReport(id, item) {
    for (const other of $("occurrences").children) {
        other.classList.toggle("selected", other === item);
    }
    await guarded(async () => {
        const report = await api("/api/reports/" + encodeURIComponent(id));
        selectedReport = report;
        $("rTitle").textContent = "Report of " + new Date(report.received).toLocaleString();
        $("rText").textContent = textOf(report);
        $("report").hidden = false;
    });
}

/** The report as smIDE's dialog showed it to the person who sent it. */
function textOf(r) {
    const lines = [];
    lines.push("smIDE " + (r.version || "?") + " - " + headlineOf(r));
    lines.push("When:      " + (r.time || r.received));
    lines.push("Where:     " + (r.where || "?"));
    if (r.thread) {
        lines.push("Thread:    " + r.thread);
    }
    lines.push("System:    " + (r.os || "?"));
    lines.push("Java:      " + (r.java || "?"));
    lines.push("Signature: " + r.signature);
    if (r.note) {
        lines.push("", "What they were doing:", r.note);
    }
    if (r.stack) {
        lines.push("", r.stack.trimEnd());
    }
    if (r.extra) {
        lines.push("", "--- from the Java runtime's crash log ---", r.extra.trimEnd());
    }
    return lines.join("\n");
}

async function saveTriage(event) {
    event.preventDefault();
    if (!selected) {
        return;
    }
    const status = $("dStatus").value;
    const comment = $("dComment").value;
    await guarded(async () => {
        await api("/api/failures/" + encodeURIComponent(selected) + "/status", {
            method: "POST",
            body: JSON.stringify({status, comment}),
        });
        $("dSaved").textContent = "Saved.";
        $("dKind").replaceChildren(pill(status, status));
        await load();
    });
}

// ------------------------------------------------------------------ pieces

function headlineOf(r) {
    if (!r.exception) {
        return r.message ? firstLine(r.message) : "Stopped unexpectedly";
    }
    const simple = r.exception.substring(r.exception.lastIndexOf(".") + 1);
    return r.message ? simple + ": " + firstLine(r.message) : simple;
}

function kindName(kind) {
    return {"previous-session": "runtime crash", "startup": "could not start", "test": "test"}[kind] || kind;
}

function firstLine(text) {
    const line = String(text).split("\n")[0].trim();
    return line.length > 160 ? line.substring(0, 157) + "..." : line;
}

function element(tag, className, text) {
    const e = document.createElement(tag);
    if (className) {
        e.className = className;
    }
    if (text !== undefined) {
        e.textContent = text;
    }
    return e;
}

function pill(text, kind) {
    return element("span", "pill " + kind, text);
}

function number(n) {
    return new Intl.NumberFormat().format(n || 0);
}

function ago(iso) {
    if (!iso) {
        return "never";
    }
    const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
    if (seconds < 60) {
        return "just now";
    }
    const units = [["day", 86400], ["hour", 3600], ["minute", 60]];
    for (const [name, size] of units) {
        if (seconds >= size) {
            const n = Math.floor(seconds / size);
            return n + " " + name + (n === 1 ? "" : "s") + " ago";
        }
    }
    return "just now";
}

// ------------------------------------------------------------------ wiring

document.addEventListener("DOMContentLoaded", () => {
    $("tokenForm").addEventListener("submit", (e) => {
        e.preventDefault();
        keepToken($("token").value.trim());
        $("token").value = "";
        load();
    });
    $("signOut").addEventListener("click", () => {
        keepToken("");
        showSignIn("");
    });
    $("status").addEventListener("change", load);
    $("refresh").addEventListener("click", load);
    $("search").addEventListener("input", () => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(load, 250);
    });
    $("triage").addEventListener("submit", saveTriage);
    $("copy").addEventListener("click", async () => {
        if (!selectedReport) {
            return;
        }
        try {
            await navigator.clipboard.writeText(textOf(selectedReport));
            $("copy").textContent = "Copied";
            setTimeout(() => { $("copy").textContent = "Copy"; }, 1500);
        } catch (e) {
            $("copy").textContent = "Select and copy";
        }
    });

    if (token) {
        load();
    } else {
        showSignIn("");
    }
});
