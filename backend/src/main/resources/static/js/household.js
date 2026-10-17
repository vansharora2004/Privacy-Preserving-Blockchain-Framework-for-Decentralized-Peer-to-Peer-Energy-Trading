// Household dashboard: overview, meter readings, marketplace, own trades and the privacy center.
import * as api from "./api.js";
import { h, mount, pageHead, panel, stat, table, kv, badge, kwh, perKwh, money, when, shortHash, localIso, button,
    findings, resultBox, errorBox, spinner, toast, busy } from "./ui.js";
import { tradeTable, isInFlight, showTrade } from "./trades.js";

const me = () => api.currentSession().profile;
const code = () => me().householdCode;

async function refreshProfile() {
    const profile = await api.get("/api/me");
    api.updateProfile(profile);
    return profile;
}

// ================================================================== overview

export async function overviewView(target, ctx) {
    mount(target, pageHead(`Welcome, ${me().displayAlias || code()}`, "Your energy position, offers and trades."), spinner());
    try {
        const [profile, availability, offers, trades] = await Promise.all([
            refreshProfile(), api.get("/api/offers/availability"), api.get("/api/offers/mine"), api.get("/api/trades/mine?size=5"),
        ]);
        const open = offers.filter(o => ["ACTIVE", "PARTIALLY_FILLED"].includes(o.status));
        mount(target,
            pageHead(`Welcome, ${profile.displayAlias || profile.householdCode}`, "Your energy position, offers and trades.",
                h("a", { class: "btn btn-primary btn-sm", href: "#/meter", text: "Submit meter reading" }),
                h("a", { class: "btn btn-outline-primary btn-sm", href: "#/market", text: "Go to marketplace" })),
            h("div", { class: "row g-3 mb-4" },
                h("div", { class: "col-6 col-lg-3" }, stat("Wallet balance", money(profile.balance), "simulated rupees", "accent")),
                h("div", { class: "col-6 col-lg-3" }, stat("Can offer now", kwh(availability.availableKwh), "from your latest valid reading", "sun")),
                h("div", { class: "col-6 col-lg-3" }, stat("Surplus / deficit", `${kwh(profile.currentSurplusKwh)} / ${kwh(profile.currentDeficitKwh)}`, "latest reading")),
                h("div", { class: "col-6 col-lg-3" }, stat("Open offers", String(open.length), `${offers.length} offers in total`))),
            panel("Recent trades", "Buy and sell trades you are part of. Open a trade to see its blockchain record.",
                tradeTable(trades.items, "household", () => ctx.rerender()),
                h("div", { class: "mt-2" }, h("a", { href: "#/trades", text: "All trades →" }))));
        if (trades.items.some(isInFlight)) ctx.poll(5000);
    } catch (err) {
        mount(target, errorBox(err));
    }
}

// ================================================================== meter readings

let lastSubmission = null;

export async function meterView(target, ctx) {
    mount(target, pageHead("Smart meter", "Submit a reading. The backend validates it, checks for replays and anomalies, and only VALID readings create sellable surplus."), spinner());
    let readings;
    try {
        readings = await api.get(`/api/households/${encodeURIComponent(code())}/meter-readings?size=20`);
    } catch (err) {
        mount(target, errorBox(err));
        return;
    }
    const nextSeq = readings.items.reduce((m, r) => Math.max(m, r.sequenceNo || 0), 0) + 1;
    const compact = code().replace(/[^A-Za-z0-9]/g, "");
    const field = (id, label, attrs, help) => h("div", { class: "col-sm-6" },
        h("label", { class: "form-label", for: id, text: label }), h("input", { class: "form-control", id, ...attrs }),
        help ? h("div", { class: "form-text", text: help }) : null);

    const form = h("form", { class: "row g-3", novalidate: true },
        field("mrId", "Reading ID", { value: `MR-${compact}-${String(nextSeq).padStart(4, "0")}`, required: true }, "Unique per reading; reusing one is a replay."),
        field("mrSeq", "Sequence number", { type: "number", min: "1", step: "1", value: String(nextSeq), required: true }, "Must increase by one each reading."),
        field("mrGen", "Energy generated (kWh)", { type: "number", min: "0", step: "0.001", value: "8.000", required: true }, "Up to 25 kWh is plausible for a household."),
        field("mrCons", "Energy consumed (kWh)", { type: "number", min: "0", step: "0.001", value: "3.000", required: true }),
        field("mrTime", "Reading time", { type: "datetime-local", step: "1", value: localIso(), required: true }, "Must be later than your previous reading and not in the future."));
    const result = h("div");
    const submitBtn = h("button", { type: "submit", class: "btn btn-primary", text: "Submit reading" });
    const replayBtn = button("Resubmit last reading (replay test)", async e => {
        if (!lastSubmission) return;
        await busy(e.currentTarget, () => submitReading(lastSubmission, result, true));
    }, "outline-secondary", { disabled: !lastSubmission });
    form.appendChild(h("div", { class: "col-12 d-flex flex-wrap gap-2" }, submitBtn, replayBtn));

    form.addEventListener("submit", async e => {
        e.preventDefault();
        const val = id => form.querySelector(`#${id}`).value;
        const payload = { readingId: val("mrId").trim(), sequenceNo: Number(val("mrSeq")), energyGeneratedKwh: val("mrGen"),
            energyConsumedKwh: val("mrCons"), readingTime: val("mrTime").length === 16 ? `${val("mrTime")}:00` : val("mrTime") };
        const r = await busy(submitBtn, () => submitReading(payload, result, false));
        replayBtn.disabled = !lastSubmission;
        if (r && r.stored) {
            // advance the form to the next reading and refresh the list, keeping the result visible
            const seq = payload.sequenceNo + 1;
            form.querySelector("#mrSeq").value = String(seq);
            form.querySelector("#mrId").value = `MR-${compact}-${String(seq).padStart(4, "0")}`;
            form.querySelector("#mrTime").value = localIso();
            try {
                const fresh = await api.get(`/api/households/${encodeURIComponent(code())}/meter-readings?size=20`);
                mount(listArea, readingsTable(fresh.items, result));
            } catch { /* the list refreshes on the next visit */ }
        }
    });

    const listArea = h("div", {}, readingsTable(readings.items, result));
    mount(target,
        pageHead("Smart meter", "Submit a reading. The backend validates it, checks for replays and anomalies, and only VALID readings create sellable surplus."),
        h("div", { class: "row g-3" },
            h("div", { class: "col-lg-6" }, panel("New reading", null, form, result)),
            h("div", { class: "col-lg-6" }, panel("Validation rules", "Applied on the server for every reading.",
                h("ul", { class: "small mb-0" },
                    h("li", {}, h("strong", { text: "REJECTED" }), " (not stored): implausible values above 25 kWh, negative values, more than 3 decimals, future or stale times, reused reading ID or sequence (replay)."),
                    h("li", {}, h("strong", { text: "FLAGGED" }), " (stored for review, no surplus): sudden jumps, sequence gaps, excessive activity."),
                    h("li", {}, h("strong", { text: "VALID" }), ": available = generated − consumed becomes your sellable surplus."),
                    h("li", { text: "Each stored reading gets a canonical SHA-256 digest; use “Check integrity” to recompute it." }))))),
        h("div", { class: "mt-3" }, panel("Your readings", "Private: visible only to you and administrators.",
            listArea)));
}

async function submitReading(payload, area, isReplay) {
    try {
        const res = await api.post("/api/meter-readings", payload);
        const r = res.data;
        if (!isReplay && r.stored) lastSubmission = payload;
        const variant = r.status === "VALID" ? "ok" : r.status === "FLAGGED" ? "warn" : "bad";
        mount(area, resultBox(variant, `${isReplay ? "Replay attempt: " : ""}${r.status} (HTTP ${res.status})`,
            h("div", { class: "small mt-1" }, kv([
                ["Stored", r.stored ? "yes" : "no"],
                ["Available", kwh(r.availableKwh)],
                ["Sellable surplus", kwh(r.sellableSurplusKwh)],
                r.recordHash ? ["Record digest", h("span", { class: "hash", text: r.recordHash })] : null])),
            findings(r.findings),
            r.notes && r.notes.length ? h("ul", { class: "small mt-2 mb-0" }, r.notes.map(n => h("li", { text: n }))) : null));
        if (r.stored) toast(`Reading ${r.readingId} stored as ${r.status}`, r.status === "VALID" ? "success" : "warning");
        return r;
    } catch (err) {
        mount(area, errorBox(err));
        return null;
    }
}

function readingsTable(items, area) {
    return table([
        { label: "Reading", render: r => h("span", { class: "mono", text: r.readingId }) },
        { label: "Seq", render: r => r.sequenceNo },
        { label: "Generated", render: r => kwh(r.energyGeneratedKwh) },
        { label: "Consumed", render: r => kwh(r.energyConsumedKwh) },
        { label: "Available", render: r => kwh(r.availableKwh) },
        { label: "Status", render: r => h("div", {}, badge(r.validationStatus), r.reviewNote ? h("div", { class: "small muted", text: r.reviewNote }) : null) },
        { label: "Time", render: r => when(r.readingTime) },
        { label: "", class: "text-end", render: r => button("Check integrity", async e => {
            try {
                const c = await busy(e.currentTarget, () => api.get(`/api/meter-readings/${encodeURIComponent(r.readingId)}/integrity`));
                mount(area, resultBox(c.result === "MATCH" ? "ok" : c.result === "MISMATCH" ? "bad" : "warn",
                    `Integrity of ${c.readingId}: ${c.result}`, h("div", { class: "small mt-1", text: c.note || c.digestFormat || "" })));
                area.scrollIntoView({ behavior: "smooth", block: "center" });
            } catch (err) {
                mount(area, errorBox(err));
            }
        }) },
    ], items, "No readings yet.");
}

// ================================================================== marketplace

let lastPurchase = null;

export async function marketView(target, ctx) {
    mount(target, pageHead("Marketplace", "Offer your validated surplus or buy energy from neighbours."), spinner());
    let offers, mine, availability;
    try {
        [offers, mine, availability] = await Promise.all([
            api.get("/api/offers?size=50"), api.get("/api/offers/mine"), api.get("/api/offers/availability")]);
    } catch (err) {
        mount(target, errorBox(err));
        return;
    }
    const purchaseArea = h("div");
    const myPseudonym = me().householdPseudonym;

    const offerTable = table([
        { label: "Seller", render: o => h("span", { class: "mono", text: o.sellerPseudonym }) },
        { label: "Available", render: o => kwh(o.remainingKwh) },
        { label: "Price", render: o => perKwh(o.pricePerKwh) },
        { label: "Expires", render: o => when(o.expiresAt) },
        { label: "Buy", class: "text-end", render: o => {
            if (o.sellerPseudonym === myPseudonym) return h("span", { class: "small muted", text: "your offer" });
            const qty = h("input", { class: "form-control form-control-sm", type: "number", min: "0.001", step: "0.001",
                max: String(o.remainingKwh), value: String(Math.min(1, Number(o.remainingKwh))), "aria-label": "Quantity in kWh" });
            const buy = button("Buy", async e => {
                const request = { offerId: o.offerId, quantityKwh: qty.value };
                await busy(e.currentTarget, () => purchase(request, api.newIdempotencyKey("buy"), purchaseArea, false));
                ctx.rerender();
            }, "primary");
            return h("div", { class: "d-flex gap-2 justify-content-end align-items-center" }, h("div", { class: "input-group input-group-sm qty-group" },
                qty, h("span", { class: "input-group-text", text: "kWh" })), buy);
        } },
    ], offers.items, "No open offers right now.");

    mount(target,
        pageHead("Marketplace", "Offer your validated surplus or buy energy from neighbours.",
            button("Refresh", () => ctx.rerender(), "outline-secondary")),
        h("div", { class: "row g-3" },
            h("div", { class: "col-xl-8" }, panel("Open offers", "Sorted by price. Sellers are shown only as pseudonyms.", offerTable, purchaseArea)),
            h("div", { class: "col-xl-4" }, panel("Create an offer", `You can offer up to ${kwh(availability.availableKwh)} from your latest valid reading.`,
                offerForm(availability.availableKwh, ctx)))),
        h("div", { class: "mt-3" }, panel("Your offers", null, myOffersTable(mine, ctx))));
    if (lastPurchase) renderPurchase(lastPurchase, purchaseArea);
}

async function purchase(request, key, area, isReplay) {
    try {
        const res = await api.post("/api/trades", request, { "Idempotency-Key": key });
        lastPurchase = { request, key, res, isReplay };
        renderPurchase(lastPurchase, area);
        const o = res.data.outcome;
        toast(isReplay ? "Replay returned the original trade" : `Purchase ${o}`, o === "CONFIRMED" ? "success" : o === "REJECTED" || o === "FAILED" ? "danger" : "warning");
    } catch (err) {
        lastPurchase = null;
        mount(area, errorBox(err));
    }
}

function renderPurchase({ request, key, res, isReplay }, area) {
    const r = res.data;
    const t = r.trade;
    const variant = r.outcome === "CONFIRMED" ? "ok" : ["REJECTED", "FAILED"].includes(r.outcome) ? "bad" : "warn";
    const bc = t && t.blockchain;
    mount(area, resultBox(variant, `${isReplay ? "Replay: " : ""}Purchase ${r.outcome} (HTTP ${res.status})${r.replayed ? " — original trade returned, nothing charged twice" : ""}`,
        t ? h("div", { class: "small mt-2" }, kv([
            ["Trade", h("span", { class: "mono", text: t.tradeId })],
            ["Total", money(t.totalAmount)],
            ["Status", h("span", {}, badge(t.status), " ", t.statusDetail || "")],
            ["Transaction", bc && bc.txHash ? h("span", { class: "hash", text: bc.txHash }) : "—"],
            ["Block", bc && bc.blockNumber !== null && bc.blockNumber !== undefined ? String(bc.blockNumber) : "—"]])) : null,
        findings(r.findings),
        h("div", { class: "d-flex flex-wrap gap-2 mt-2" },
            t ? button("Open trade", () => showTrade(t.tradeId, "household")) : null,
            button("Send the same request again (replay test)", async e => {
                await busy(e.currentTarget, () => purchase(request, key, area, true));
            }, "outline-secondary"))));
}

function offerForm(available, ctx) {
    const qty = h("input", { class: "form-control", id: "ofQty", type: "number", min: "0.001", step: "0.001", required: true,
        value: String(Math.max(0, Math.min(2, Number(available) || 0))) });
    const price = h("input", { class: "form-control", id: "ofPrice", type: "number", min: "0.01", step: "0.01", value: "6.00", required: true });
    const minutes = h("input", { class: "form-control", id: "ofExp", type: "number", min: "5", max: "1440", step: "1", value: "60" });
    const out = h("div");
    const body = () => ({ quantityKwh: qty.value, pricePerKwh: price.value });
    const check = button("Check first", async e => {
        try {
            const res = await busy(e.currentTarget, () => api.post("/api/anomaly-checks/offer", body()));
            const c = res.data;
            mount(out, resultBox(c.outcome === "PASSED" ? "ok" : c.outcome === "FLAGGED" ? "warn" : "bad",
                `Pre-check: ${c.outcome} (nothing was created)`,
                h("div", { class: "small mt-1", text: `Validated surplus: ${kwh(c.validatedSurplusKwh)}` }), findings(c.findings)));
        } catch (err) {
            mount(out, errorBox(err));
        }
    }, "outline-secondary");
    const create = h("button", { type: "submit", class: "btn btn-primary btn-sm", text: "Create offer" });
    return h("form", { novalidate: true, onsubmit: async e => {
        e.preventDefault();
        try {
            const res = await busy(create, () => api.post("/api/offers", { ...body(), expiresInMinutes: Number(minutes.value) || null },
                { "Idempotency-Key": api.newIdempotencyKey("offer") }));
            const r = res.data;
            const variant = r.outcome === "PASSED" ? "ok" : r.outcome === "FLAGGED" ? "warn" : "bad";
            if (r.offer) {
                toast(`Offer created (${r.outcome})`, r.outcome === "PASSED" ? "success" : "warning");
                ctx.rerender();
                return;
            }
            mount(out, resultBox(variant, `Offer ${r.outcome} (HTTP ${res.status})`,
                h("div", { class: "small mt-1", text: `Available to offer: ${kwh(r.availableKwh)}` }), findings(r.findings)));
        } catch (err) {
            mount(out, errorBox(err));
        }
    } },
        h("div", { class: "mb-2" }, h("label", { class: "form-label", for: "ofQty", text: "Quantity (kWh)" }), qty),
        h("div", { class: "mb-2" }, h("label", { class: "form-label", for: "ofPrice", text: "Price (₹ per kWh)" }), price,
            h("div", { class: "form-text", text: "Above ₹15/kWh is flagged for review." })),
        h("div", { class: "mb-3" }, h("label", { class: "form-label", for: "ofExp", text: "Expires in (minutes)" }), minutes),
        h("div", { class: "d-flex gap-2" }, create, check), out);
}

function myOffersTable(mine, ctx) {
    return table([
        { label: "Offer", render: o => h("span", { class: "mono", title: o.offerId, text: shortHash(o.offerId, 6) }) },
        { label: "Status", render: o => badge(o.status) },
        { label: "Offered", render: o => kwh(o.quantityKwh) },
        { label: "Remaining", render: o => kwh(o.remainingKwh) },
        { label: "Price", render: o => perKwh(o.pricePerKwh) },
        { label: "Source reading", render: o => h("span", { class: "mono small", text: o.sourceReadingId || "—" }) },
        { label: "Expires", render: o => when(o.expiresAt) },
        { label: "", class: "text-end", render: o => ["ACTIVE", "PARTIALLY_FILLED"].includes(o.status)
            ? button("Cancel", async e => {
                try {
                    await busy(e.currentTarget, () => api.post(`/api/offers/${encodeURIComponent(o.offerId)}/cancel`));
                    toast("Offer cancelled; unsold energy returned");
                    ctx.rerender();
                } catch (err) {
                    toast(err.message, "danger");
                }
            }, "outline-danger") : "" },
    ], mine, "You have not created any offers.");
}

// ================================================================== trades

export async function tradesView(target, ctx) {
    mount(target, pageHead("My trades", "Every purchase and sale you are part of, with its blockchain status."), spinner());
    try {
        const page = await api.get(`/api/trades/mine?size=50`);
        mount(target,
            pageHead("My trades", "Every purchase and sale you are part of, with its blockchain status.",
                button("Refresh", () => ctx.rerender(), "outline-secondary")),
            panel(null, null, tradeTable(page.items, "household", () => ctx.rerender())));
        if (page.items.some(isInFlight)) ctx.poll(4000);
    } catch (err) {
        mount(target, errorBox(err));
    }
}

// ================================================================== privacy center

export async function privacyView(target) {
    mount(target, pageHead("Privacy center", "What you can see about yourself compared with what the public can see."), spinner());
    try {
        const [profile, households, trades] = await Promise.all([
            api.get(`/api/privacy/households/${encodeURIComponent(code())}`), api.get("/api/privacy/public/households"),
            api.get("/api/privacy/public/trades?size=5")]);
        mount(target,
            pageHead("Privacy center", "What you can see about yourself compared with what the public can see."),
            h("div", { class: "row g-3" },
                h("div", { class: "col-lg-6" }, panel(null, null,
                    h("div", { class: "d-flex justify-content-between align-items-center mb-3" }, h("h2", { class: "fs-6 fw-semibold m-0", text: "Your private profile" }),
                        h("span", { class: "privacy-tag private", text: "private" })),
                    kv([["Household code", profile.householdCode], ["Pseudonym", h("span", { class: "mono", text: profile.householdPseudonym })],
                        ["Display alias", profile.displayAlias], ["Name", profile.fullName], ["E-mail", profile.email], ["Role", profile.role],
                        ["Balance", money(profile.balance)], ["Surplus", kwh(profile.currentSurplusKwh)], ["Deficit", kwh(profile.currentDeficitKwh)],
                        ["Member since", when(profile.createdAt)]]),
                    h("p", { class: "small muted mt-3 mb-0", text: "Only you and administrators can read this. Passwords and password hashes are never returned by the API." }))),
                h("div", { class: "col-lg-6" }, panel(null, null,
                    h("div", { class: "d-flex justify-content-between align-items-center mb-3" }, h("h2", { class: "fs-6 fw-semibold m-0", text: "What the public sees" }),
                        h("span", { class: "privacy-tag public", text: "public" })),
                    kv([["Your pseudonym in the directory", h("span", { class: "mono", text: profile.householdPseudonym })],
                        ["Households listed", String(households.length)],
                        ["Public trade fields", "quantity, price, transaction hash, block, time"],
                        ["Not published", "name, e-mail, household code, balance, energy figures, trade parties"]]),
                    h("h3", { class: "fs-6 fw-semibold mt-3", text: "On the blockchain" }),
                    h("ul", { class: "small" },
                        h("li", { text: "A hashed trade key, per-trade keyed party references, amounts and a record digest." }),
                        h("li", { text: "Party references differ in every trade, so your trades are not linked on-chain by an identifier." }),
                        h("li", { text: "Limitation: amounts and timing are public, so patterns can still be inferred." })),
                    h("h3", { class: "fs-6 fw-semibold mt-3", text: "Latest public trades" }),
                    table([{ label: "Energy", render: t => kwh(t.quantityKwh) }, { label: "Price", render: t => perKwh(t.pricePerKwh) },
                        { label: "Tx", render: t => h("span", { class: "hash", text: shortHash(t.txHash, 6) }) }], trades.items, "No confirmed trades yet.")))));
    } catch (err) {
        mount(target, errorBox(err));
    }
}
