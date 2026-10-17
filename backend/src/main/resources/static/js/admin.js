// Administrator and auditor views. The backend enforces every permission; the UI only hides
// actions the role cannot perform (ADMIN acts, AUDITOR reads).
import * as api from "./api.js";
import { h, mount, pageHead, panel, stat, table, kv, badge, kwh, perKwh, money, when, shortHash, button,
    errorBox, resultBox, spinner, toast, busy } from "./ui.js";
import { tradeTable, isInFlight } from "./trades.js";

const role = () => api.currentSession().profile.role;
const isAdmin = () => role() === "ROLE_ADMIN";

function pager(page, onPage) {
    if (!page || page.totalPages <= 1) return null;
    return h("div", { class: "d-flex justify-content-between align-items-center mt-3 small" },
        h("span", { class: "muted", text: `Page ${page.page + 1} of ${page.totalPages} · ${page.totalElements} items` }),
        h("div", { class: "btn-group" },
            button("Previous", () => onPage(page.page - 1), "outline-secondary", { disabled: page.page <= 0 }),
            button("Next", () => onPage(page.page + 1), "outline-secondary", { disabled: page.page + 1 >= page.totalPages })));
}

const ATTENTION = ["FLAGGED", "NEEDS_REVIEW", "PENDING", "SUBMITTED"];

// ================================================================== overview

export async function adminOverview(target, ctx) {
    mount(target, pageHead("Operations overview", "Market activity, review queues and blockchain health."), spinner());
    try {
        const [households, offers, trades, anomalies, alerts, chain] = await Promise.all([
            isAdmin() ? api.get("/api/households") : Promise.resolve(null),
            api.get("/api/offers?size=1"), api.get("/api/trades?size=100"), api.get("/api/anomalies?size=100"),
            api.get("/api/blockchain/alerts?size=20"), api.get("/api/blockchain/status")]);
        const count = s => trades.items.filter(t => t.status === s).length;
        const attention = trades.items.filter(t => ATTENTION.includes(t.status));
        const openAnomalies = anomalies.items.filter(a => ["DETECTED", "UNDER_REVIEW"].includes(a.reviewStatus)).length;
        mount(target,
            pageHead("Operations overview", "Market activity, review queues and blockchain health.",
                button("Refresh", () => ctx.rerender(), "outline-secondary")),
            h("div", { class: "row g-3 mb-4" },
                h("div", { class: "col-6 col-lg-3" }, stat("Households", households ? String(households.filter(u => u.role === "ROLE_HOUSEHOLD").length) : "—",
                    households ? `${households.length} accounts` : "administrators only", "accent")),
                h("div", { class: "col-6 col-lg-3" }, stat("Open offers", String(offers.totalElements), "in the marketplace", "sun")),
                h("div", { class: "col-6 col-lg-3" }, stat("Confirmed trades", String(count("CONFIRMED")), `of the latest ${trades.items.length}`)),
                h("div", { class: "col-6 col-lg-3" }, stat("Needs attention", String(attention.length + openAnomalies + alerts.totalElements),
                    `${attention.length} trades · ${openAnomalies} anomalies · ${alerts.totalElements} alerts`))),
            h("div", { class: "row g-3" },
                h("div", { class: "col-xl-8" }, panel("Trades needing attention", "Flagged, held for review, or not yet confirmed on-chain.",
                    tradeTable(attention, "staff", () => ctx.rerender(), "Nothing needs attention: every trade is settled."))),
                h("div", { class: "col-xl-4" }, panel("Blockchain", null, chainSummary(chain),
                    h("div", { class: "mt-3" }, h("a", { href: "#/admin/blockchain", text: "Blockchain details and alerts →" }))))));
        if (attention.some(isInFlight)) ctx.poll(6000);
    } catch (err) {
        mount(target, errorBox(err));
    }
}

function chainSummary(s) {
    if (!s.enabled) {
        return resultBox("warn", "Blockchain connector disabled", h("div", { class: "small mt-1",
            text: "Trades are reserved but cannot be confirmed. Start Hardhat and the backend with BLOCKCHAIN_ENABLED=true (see README)." }));
    }
    return h("div", {},
        s.ok ? resultBox("ok", "Connected and verified", null) : resultBox("bad", "Blockchain not usable", h("div", { class: "small mt-1", text: s.problem || "" })),
        h("div", { class: "mt-3" }, kv([["Chain ID", s.actualChainId ?? s.expectedChainId], ["Latest block", s.latestBlock],
            ["Contract", s.contractAddress ? h("span", { class: "hash", text: s.contractAddress }) : null],
            ["Version", s.contractVersion], ["Signer", s.signerAddress ? h("span", { class: "hash", text: s.signerAddress }) : null],
            ["Signer authorized", s.signerAuthorized === null || s.signerAuthorized === undefined ? "—" : (s.signerAuthorized ? "yes" : "no")]])));
}

// ================================================================== trades

export async function adminTrades(target, ctx, pageNo = 0) {
    mount(target, pageHead("All trades", "Every trade with its blockchain state. Open a trade to act on it."), spinner());
    try {
        const page = await api.get(`/api/trades?size=20&page=${pageNo}`);
        mount(target,
            pageHead("All trades", isAdmin()
                ? "Open a trade to approve, reject, reconcile, resolve or re-anchor it."
                : "Read-only auditor view.", button("Refresh", () => ctx.rerender(), "outline-secondary")),
            panel(null, null, tradeTable(page.items, "staff", () => ctx.rerender()), pager(page, p => adminTrades(target, ctx, p))));
        if (page.items.some(isInFlight)) ctx.poll(5000);
    } catch (err) {
        mount(target, errorBox(err));
    }
}

// ================================================================== households

export async function adminHouseholds(target) {
    mount(target, pageHead("Households", "Private profiles (administrators only). Passwords are never returned."), spinner());
    try {
        const list = await api.get("/api/households");
        mount(target, pageHead("Households", "Private profiles (administrators only). Passwords are never returned."),
            panel(null, null, table([
                { label: "Code", render: u => h("strong", { text: u.householdCode }) },
                { label: "Pseudonym", render: u => h("span", { class: "mono", text: u.householdPseudonym }) },
                { label: "Alias", render: u => u.displayAlias || "—" },
                { label: "Name", render: u => u.fullName || "—" },
                { label: "Role", render: u => badge(u.role.replace("ROLE_", "")) },
                { label: "Balance", render: u => money(u.balance) },
                { label: "Surplus", render: u => kwh(u.currentSurplusKwh) },
                { label: "Deficit", render: u => kwh(u.currentDeficitKwh) },
            ], list)));
    } catch (err) {
        mount(target, errorBox(err));
    }
}

// ================================================================== offers

export async function adminOffers(target, ctx) {
    mount(target, pageHead("Open offers", "Sellers appear as pseudonyms even here."), spinner());
    try {
        const page = await api.get("/api/offers?size=100");
        mount(target, pageHead("Open offers", isAdmin() ? "Administrators can cancel an offer; unsubmitted reservations on it are released." : "Read-only.",
            button("Refresh", () => ctx.rerender(), "outline-secondary")),
            panel(null, null, table([
                { label: "Offer", render: o => h("span", { class: "mono", title: o.offerId, text: shortHash(o.offerId, 6) }) },
                { label: "Seller", render: o => h("span", { class: "mono", text: o.sellerPseudonym }) },
                { label: "Remaining", render: o => kwh(o.remainingKwh) },
                { label: "Price", render: o => perKwh(o.pricePerKwh) },
                { label: "Expires", render: o => when(o.expiresAt) },
                { label: "", class: "text-end", render: o => isAdmin() ? button("Cancel", async e => {
                    try {
                        await busy(e.currentTarget, () => api.post(`/api/offers/${encodeURIComponent(o.offerId)}/cancel`));
                        toast("Offer cancelled");
                        ctx.rerender();
                    } catch (err) {
                        toast(err.message, "danger");
                    }
                }, "outline-danger") : "" },
            ], page.items, "No open offers.")));
    } catch (err) {
        mount(target, errorBox(err));
    }
}

// ================================================================== anomalies

export async function adminAnomalies(target, ctx, pageNo = 0) {
    mount(target, pageHead("Anomaly detection", "Findings from the meter, offer and trade rules. A flag is a prompt for review, not an accusation."), spinner());
    try {
        const page = await api.get(`/api/anomalies?size=20&page=${pageNo}`);
        mount(target,
            pageHead("Anomaly detection", "Findings from the meter, offer and trade rules. A flag is a prompt for review, not an accusation.",
                button("Refresh", () => ctx.rerender(), "outline-secondary")),
            panel(null, null, table([
                { label: "When", render: a => when(a.createdAt) },
                { label: "Rule", render: a => h("div", {}, h("code", { text: a.ruleCode }), h("div", { class: "small muted", text: a.eventType })) },
                { label: "Outcome", render: a => badge(a.outcome) },
                { label: "Severity", render: a => badge(a.severity) },
                { label: "Household", render: a => h("span", { class: "mono small", text: a.subjectPseudonym || "—" }) },
                { label: "Explanation", render: a => h("span", { class: "small", text: a.description }) },
                { label: "Review", render: a => isAdmin() ? reviewControl(a) : badge(a.reviewStatus) },
            ], page.items, "No anomalies recorded."), pager(page, p => adminAnomalies(target, ctx, p))));
    } catch (err) {
        mount(target, errorBox(err));
    }
}

function reviewControl(a) {
    const select = h("select", { class: "form-select form-select-sm", "aria-label": "Review status" },
        ["DETECTED", "UNDER_REVIEW", "RESOLVED", "DISMISSED"].map(s => h("option", { value: s, text: s, selected: s === a.reviewStatus })));
    select.addEventListener("change", async () => {
        if (select.value === "DETECTED") { select.value = a.reviewStatus; return; }
        try {
            const res = await api.patch(`/api/anomalies/${a.id}/review`, { reviewStatus: select.value });
            a.reviewStatus = res.data.reviewStatus;
            toast(`Anomaly marked ${a.reviewStatus}`);
        } catch (err) {
            select.value = a.reviewStatus;
            toast(err.message, "danger");
        }
    });
    return select;
}

// ================================================================== blockchain

export async function adminBlockchain(target, ctx) {
    mount(target, pageHead("Blockchain", "Network verification, de-duplicated alerts and the anchor audit."), spinner());
    try {
        const [status, alerts] = await Promise.all([api.get("/api/blockchain/status"), api.get("/api/blockchain/alerts?size=50")]);
        const auditOut = h("div");
        mount(target,
            pageHead("Blockchain", "Network verification, de-duplicated alerts and the anchor audit.",
                button("Refresh", () => ctx.rerender(), "outline-secondary"),
                isAdmin() ? button("Run anchor audit", async e => {
                    try {
                        const res = await busy(e.currentTarget, () => api.post("/api/blockchain/anchor-audit"));
                        mount(auditOut, resultBox("ok", `Anchor audit checked ${res.data.checked} confirmed trade(s)`,
                            h("div", { class: "small mt-1", text: "Problems appear below as ANCHOR_MISSING or ANCHOR_INVALID alerts." })));
                        setTimeout(() => ctx.rerender(), 1200);
                    } catch (err) {
                        mount(auditOut, errorBox(err));
                    }
                }, "primary") : null),
            auditOut,
            h("div", { class: "row g-3" },
                h("div", { class: "col-lg-5" }, panel("Network and contract", "Re-verified on every blockchain decision.", chainSummary(status),
                    status.enabled ? h("div", { class: "mt-3" }, kv([
                        ["Expected deployment", status.expectedDeploymentId ? h("span", { class: "hash", text: status.expectedDeploymentId }) : null],
                        ["Actual deployment", status.deploymentId ? h("span", { class: "hash", text: status.deploymentId }) : null],
                        ["Genesis hash", status.genesisHash ? h("span", { class: "hash", text: status.genesisHash }) : null]])) : null)),
                h("div", { class: "col-lg-7" }, panel("Open alerts", "Each problem is reported once and counted, not repeated.",
                    table([
                        { label: "Type", render: a => badge(a.type) },
                        { label: "Trade", render: a => a.tradeId ? h("span", { class: "mono small", text: shortHash(a.tradeId, 6) }) : "—" },
                        { label: "Detail", render: a => h("span", { class: "small", text: a.detail }) },
                        { label: "Seen", render: a => h("span", { class: "small", text: `${a.occurrences}× · last ${when(a.lastSeenAt)}` }) },
                    ], alerts.items, "No open alerts. The blockchain and all checked anchors are consistent.")))),
            h("div", { class: "mt-3" }, panel("After a chain reset", null, h("ol", { class: "small mb-0" },
                h("li", { text: "Redeploy the contract and restart the backend with the new deployment ID (scripts/start-demo.ps1 does both)." }),
                h("li", { text: "Trades submitted to the old chain move to NEEDS_REVIEW; never-submitted trades are submitted normally." }),
                h("li", { text: "Run the anchor audit: confirmed trades from the old chain show ANCHOR_MISSING." }),
                h("li", { text: "Open each affected trade and choose Re-anchor (confirmed trades are re-recorded without moving money)." })))));
    } catch (err) {
        mount(target, errorBox(err));
    }
}

// ================================================================== audit log

export async function adminAudit(target, ctx, pageNo = 0) {
    mount(target, pageHead("Audit log", "Security and business events, newest first."), spinner());
    try {
        const page = await api.get(`/api/audit-logs?size=25&page=${pageNo}`);
        mount(target, pageHead("Audit log", "Security and business events, newest first.", button("Refresh", () => ctx.rerender(), "outline-secondary")),
            panel(null, null, table([
                { label: "Time", render: a => when(a.timestamp) },
                { label: "Actor", render: a => h("span", { class: "mono small", text: a.actor }) },
                { label: "Action", render: a => h("code", { text: a.action }) },
                { label: "Outcome", render: a => badge(a.outcome) },
                { label: "Details", render: a => h("span", { class: "small", text: a.details || "" }) },
            ], page.items, "No audit entries."), pager(page, p => adminAudit(target, ctx, p))));
    } catch (err) {
        mount(target, errorBox(err));
    }
}
