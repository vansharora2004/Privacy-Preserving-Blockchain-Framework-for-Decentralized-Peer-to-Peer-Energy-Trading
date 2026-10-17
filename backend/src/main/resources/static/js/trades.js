// Trade tables, trade detail (blockchain record, history, verification) and trade actions.
import * as api from "./api.js";
import { h, badge, kwh, perKwh, money, when, shortHash, table, kv, button, openModal, closeModal, modalBody,
    toast, errorBox, resultBox, busy, mount, spinner } from "./ui.js";

const IN_FLIGHT = ["PENDING", "SUBMITTED"];

export function isInFlight(trade) {
    return IN_FLIGHT.includes(trade.status);
}

/** The status badge plus the backend's explanation of the state. */
export function tradeStatus(trade) {
    return h("div", {}, badge(trade.status), trade.statusDetail ? h("div", { class: "small muted mt-1", text: trade.statusDetail }) : null);
}

function txCell(trade) {
    const bc = trade.blockchain;
    if (!bc || !bc.txHash) return h("span", { class: "muted", text: "—" });
    return h("div", {}, h("span", { class: "hash", title: bc.txHash, text: shortHash(bc.txHash, 6) }),
        bc.blockNumber !== null && bc.blockNumber !== undefined ? h("div", { class: "small muted", text: `block ${bc.blockNumber}` }) : null);
}

/** columns for a list of trades; `who` is "household" or "staff". */
export function tradeTable(trades, who, onChanged, emptyText = "No trades yet.") {
    const columns = [
        { label: "Trade", render: t => h("span", { class: "mono", title: t.tradeId, text: shortHash(t.tradeId, 6) }) },
        who === "household" ? { label: "Role", render: t => t.yourRole || "—" } : { label: "Seller → buyer", class: "d-none d-lg-table-cell",
            render: t => h("div", { class: "mono small" }, h("div", { text: t.sellerPseudonym }), h("div", { class: "muted", text: `→ ${t.buyerPseudonym}` })) },
        { label: "Energy", class: "d-none d-sm-table-cell", render: t => kwh(t.quantityKwh) },
        { label: "Price", class: "d-none d-md-table-cell", render: t => perKwh(t.pricePerKwh) },
        { label: "Total", render: t => money(t.totalAmount) },
        { label: "Status", render: t => tradeStatus(t) },
        { label: "Blockchain tx", class: "d-none d-md-table-cell", render: t => txCell(t) },
        { label: "Created", class: "d-none d-xl-table-cell", render: t => when(t.createdAt) },
        { label: "", class: "text-end", render: t => button("Details", () => showTrade(t.tradeId, who, onChanged)) },
    ];
    return table(columns, trades, emptyText);
}

/** Opens the trade detail modal and loads the current state. */
export async function showTrade(tradeId, who, onChanged) {
    openModal("Trade details", spinner());
    await renderTrade(tradeId, who, onChanged);
}

async function renderTrade(tradeId, who, onChanged) {
    let trade;
    try {
        trade = await api.get(`/api/trades/${encodeURIComponent(tradeId)}`);
    } catch (err) {
        mount(modalBody(), errorBox(err));
        return;
    }
    const refresh = async () => { await renderTrade(tradeId, who, onChanged); if (onChanged) onChanged(); };
    const bc = trade.blockchain || {};
    const verifyArea = h("div");
    const actionArea = h("div", { class: "d-flex flex-wrap gap-2 mt-3" }, actionsFor(trade, who, refresh, verifyArea));

    mount(modalBody(),
        h("div", { class: "d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3" },
            h("div", {}, h("div", { class: "mono small muted", text: trade.tradeId }), h("div", { class: "fs-5 fw-semibold mt-1",
                text: `${kwh(trade.quantityKwh)} at ${perKwh(trade.pricePerKwh)} = ${money(trade.totalAmount)}` })),
            tradeStatus(trade)),
        h("div", { class: "row g-3" },
            h("div", { class: "col-md-6" }, h("h3", { class: "fs-6 fw-semibold", text: "Parties (pseudonymous)" }),
                kv([["Seller", h("span", { class: "mono", text: trade.sellerPseudonym })],
                    ["Buyer", h("span", { class: "mono", text: trade.buyerPseudonym })],
                    trade.yourRole ? ["Your role", trade.yourRole] : null,
                    ["Offer", h("span", { class: "mono small", text: trade.offerId })],
                    ["Created", when(trade.createdAt)], ["Confirmed", when(trade.confirmedAt)]])),
            h("div", { class: "col-md-6" }, h("h3", { class: "fs-6 fw-semibold", text: "Blockchain record" }),
                kv([["Transaction", bc.txHash ? h("span", { class: "hash", text: bc.txHash }) : "not yet broadcast"],
                    ["Block", bc.blockNumber ?? (bc.txHash ? "not mined yet" : "—")],
                    ["Chain ID", bc.chainId], ["Contract", bc.contractAddress ? h("span", { class: "hash", text: bc.contractAddress }) : null],
                    ["Attempts", bc.submissionAttempts], ["Submitted", when(bc.submittedAt)],
                    ["Record digest", bc.recordDigest ? h("span", { class: "hash", text: bc.recordDigest }) : null]]))),
        isInFlight(trade) ? resultBox("warn", "Not confirmed yet",
            h("div", { class: "small mt-1", text: "The trade is only final once the backend has verified the transaction on-chain. This view does not show it as confirmed before that." })) : null,
        verifyArea,
        actionArea,
        h("h3", { class: "fs-6 fw-semibold mt-4", text: "History" }),
        trade.history && trade.history.length
            ? h("ul", { class: "history" }, trade.history.map(e => h("li", {},
                h("div", {}, h("strong", { text: `${e.fromStatus ? e.fromStatus + " → " : ""}${e.toStatus}` }), h("span", { class: "muted", text: ` · ${e.actor} · ${when(e.at)}` })),
                e.detail ? h("div", { class: "muted", text: e.detail }) : null)))
            : h("div", { class: "muted small", text: "No history recorded." }));
}

function actionsFor(trade, who, refresh, verifyArea) {
    const id = encodeURIComponent(trade.tradeId);
    const role = (api.currentSession() || {}).profile?.role;
    const isAdmin = role === "ROLE_ADMIN";
    const isStaff = isAdmin || role === "ROLE_AUDITOR";
    const out = [];

    const run = (label, variant, path, success) => button(label, async e => {
        try {
            await busy(e.currentTarget, () => api.post(path));
            toast(success);
            await refresh();
        } catch (err) {
            mount(verifyArea, errorBox(err));
        }
    }, variant);

    if (trade.blockchain && trade.blockchain.txHash || trade.status === "CONFIRMED") {
        out.push(button("Verify on blockchain", async e => {
            try {
                const v = await busy(e.currentTarget, () => api.get(`/api/trades/${id}/verification`));
                const variant = v.result === "MATCH" ? "ok" : (v.result === "MISMATCH" || v.result === "VOIDED" ? "bad" : "warn");
                mount(verifyArea, resultBox(variant, `On-chain verification: ${v.result}`,
                    h("div", { class: "small mt-1" }, v.onChainStatus ? `On-chain status: ${v.onChainStatus}. ` : "", v.note || "")));
            } catch (err) {
                mount(verifyArea, errorBox(err));
            }
        }, "primary"));
    }
    if (!isAdmin && who === "household" && trade.yourRole === "BUYER" && ["PENDING", "FLAGGED"].includes(trade.status)) {
        out.push(run("Cancel purchase", "outline-danger", `/api/trades/${id}/cancel`, "Purchase cancelled; funds and energy released"));
    }
    if (isAdmin) {
        if (trade.status === "FLAGGED") {
            out.push(run("Approve", "success", `/api/trades/${id}/approve`, "Trade approved"));
            out.push(run("Reject", "outline-danger", `/api/trades/${id}/reject`, "Trade rejected; reservation released"));
        }
        if (["PENDING", "SUBMITTED"].includes(trade.status)) {
            out.push(run("Reconcile with blockchain", "outline-primary", `/api/trades/${id}/reconcile`, "Reconciliation finished"));
        }
        if (["PENDING", "FLAGGED"].includes(trade.status)) {
            out.push(run("Cancel", "outline-danger", `/api/trades/${id}/cancel`, "Trade cancelled"));
        }
        if (trade.status === "NEEDS_REVIEW") {
            out.push(button("Resolve as confirmed", () => resolution(trade, "resolve-confirmed", refresh, verifyArea), "success"));
            out.push(button("Resolve as failed", () => resolution(trade, "resolve-failed", refresh, verifyArea), "outline-danger"));
            out.push(button("Re-anchor on current chain", () => resolution(trade, "re-anchor", refresh, verifyArea), "outline-primary"));
        }
        if (trade.status === "CONFIRMED") {
            out.push(button("Re-anchor on current chain", () => resolution(trade, "re-anchor", refresh, verifyArea), "outline-secondary"));
        }
    }
    if (isStaff) {
        out.push(button("Submission attempts", () => attempts(trade, verifyArea), "outline-secondary"));
    }
    return out;
}

const RESOLUTION_HELP = {
    "resolve-confirmed": "Confirms the trade and pays the seller. Allowed only when the current deployment holds a verified record of this trade.",
    "resolve-failed": "Marks the trade FAILED and releases the buyer's funds and the seller's energy. Allowed only when the current deployment shows the trade is not recorded.",
    "re-anchor": "Records the trade on the current blockchain deployment (after a chain reset). A CONFIRMED trade is re-recorded without moving money.",
};

function resolution(trade, action, refresh, area) {
    const reason = h("textarea", { class: "form-control", rows: "2", minlength: "10", maxlength: "200", required: true,
        placeholder: "Reason (10-200 characters), recorded in the audit log" });
    const retired = h("input", { class: "form-check-input", type: "checkbox", id: "retiredCheck" });
    const out = h("div");
    const submit = button("Submit", async e => {
        try {
            await busy(e.currentTarget, () => api.post(`/api/trades/${encodeURIComponent(trade.tradeId)}/${action}`,
                { reason: reason.value.trim(), priorDeploymentRetired: retired.checked }));
            toast("Resolution recorded");
            await refresh();
        } catch (err) {
            mount(out, errorBox(err));
        }
    }, "primary");
    mount(area, h("div", { class: "result-box mt-3" },
        h("strong", { text: action.replace("-", " ") }),
        h("p", { class: "small muted mt-1 mb-2", text: RESOLUTION_HELP[action] }),
        reason,
        h("div", { class: "form-check mt-2" }, retired,
            h("label", { class: "form-check-label small", for: "retiredCheck",
                text: "I have reviewed the earlier blockchain deployment and confirm it can no longer record this trade" })),
        h("div", { class: "mt-2" }, submit), out));
}

async function attempts(trade, area) {
    try {
        const list = await api.get(`/api/trades/${encodeURIComponent(trade.tradeId)}/attempts`);
        mount(area, h("div", { class: "mt-3" }, h("h3", { class: "fs-6 fw-semibold", text: "Submission attempts" }),
            table([
                { label: "#", render: a => a.attemptNo },
                { label: "Purpose", render: a => badge(a.purpose) },
                { label: "Status", render: a => badge(a.status) },
                { label: "Tx", render: a => h("span", { class: "hash", title: a.txHash, text: shortHash(a.txHash, 6) }) },
                { label: "Nonce", render: a => a.nonce },
                { label: "Deployment", render: a => h("span", { class: "hash", title: a.deploymentId, text: shortHash(a.deploymentId, 4) }) },
                { label: "Detail", render: a => h("span", { class: "small", text: a.detail || "" }) },
            ], list, "No blockchain submission was attempted.")));
    } catch (err) {
        mount(area, errorBox(err));
    }
}

export function closeTrade() {
    closeModal();
}
