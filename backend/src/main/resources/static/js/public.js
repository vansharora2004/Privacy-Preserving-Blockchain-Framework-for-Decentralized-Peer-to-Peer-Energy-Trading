// Login page and the anonymous (public) privacy view. Only the public API is used here.
import * as api from "./api.js";
import { h, mount, panel, pageHead, stat, table, kwh, perKwh, when, shortHash, errorBox, spinner, badge } from "./ui.js";

export function loginView(target, onLoggedIn) {
    const code = h("input", { class: "form-control", id: "loginCode", autocomplete: "username", required: true,
        placeholder: "e.g. HH-001", autocapitalize: "characters", spellcheck: "false" });
    const password = h("input", { class: "form-control", id: "loginPassword", type: "password", autocomplete: "current-password", required: true });
    const message = h("div");
    const submit = h("button", { type: "submit", class: "btn btn-primary w-100", text: "Sign in" });

    const form = h("form", { novalidate: true, onsubmit: async e => {
        e.preventDefault();
        mount(message);
        if (!code.value.trim() || !password.value) {
            mount(message, errorBox({ message: "Enter your household code and password" }));
            return;
        }
        submit.disabled = true;
        submit.textContent = "Signing in…";
        try {
            const profile = await api.login(code.value, password.value);
            password.value = "";
            onLoggedIn(profile);
        } catch (err) {
            mount(message, errorBox(err));
        } finally {
            submit.disabled = false;
            submit.textContent = "Sign in";
        }
    } },
        h("div", { class: "mb-3" }, h("label", { class: "form-label", for: "loginCode", text: "Household code" }), code),
        h("div", { class: "mb-3" }, h("label", { class: "form-label", for: "loginPassword", text: "Password" }), password),
        submit, message);

    const publicArea = h("div", { class: "mt-4" }, spinner("Loading the public market view…"));

    mount(target,
        h("div", { class: "row g-4 align-items-stretch" },
            h("div", { class: "col-lg-7" }, h("div", { class: "login-hero" },
                h("h1", { text: "Peer-to-peer solar energy, recorded on a blockchain" }),
                h("p", { class: "mt-3", text: "Households sell validated surplus energy to their neighbours. Every confirmed trade is anchored on a local Ethereum (Hardhat) chain, while names and household codes stay off-chain." }),
                h("div", { class: "mt-3" }, ["Smart-meter validation", "Replay protection", "Anomaly detection",
                    "HMAC pseudonyms", "Role-based access", "On-chain verification"].map(t => h("span", { class: "pill", text: t }))),
                h("ul", { class: "mt-4 mb-0 small" },
                    h("li", { text: "Households: submit readings, offer surplus, buy energy, follow each trade to its blockchain receipt." }),
                    h("li", { text: "Administrators: review flagged trades and anomalies, reconcile with the chain, resolve held trades." }),
                    h("li", { text: "Visitors: see only aggregate and pseudonymous market data (below)." })))),
            h("div", { class: "col-lg-5" }, panel("Sign in", "Use the household code as the username. Demo passwords are set by the operator (see README).", form))),
        publicArea);

    renderPublicData(publicArea, true);
}

export function publicView(target) {
    const area = h("div", {}, spinner());
    mount(target, pageHead("Public market view", "Exactly what an anonymous visitor can see. No names, household codes, energy balances or trade parties."), area);
    renderPublicData(area, false);
}

async function renderPublicData(area, compact) {
    try {
        const [summary, trades, households] = await Promise.all([
            api.get("/api/privacy/public/market-summary"),
            api.get("/api/privacy/public/trades?size=10"),
            api.get("/api/privacy/public/households"),
        ]);
        const withheld = summary.figuresWithheld;
        mount(area,
            h("div", { class: "row g-3 mb-3" },
                h("div", { class: "col-6 col-md-3" }, stat("Open offers", String(summary.openOffers), "public count", "accent")),
                h("div", { class: "col-6 col-md-3" }, stat("Energy on offer", withheld ? "withheld" : kwh(summary.totalOfferedKwh),
                    withheld ? `shown once ${summary.minimumSellers}+ sellers are active` : "all open offers", "sun")),
                h("div", { class: "col-6 col-md-3" }, stat("Price range", withheld ? "withheld" : `${perKwh(summary.minPricePerKwh)} – ${perKwh(summary.maxPricePerKwh)}`,
                    withheld ? "protects small markets" : "open offers")),
                h("div", { class: "col-6 col-md-3" }, stat("Confirmed (24 h)", String(summary.confirmedTradesLast24h), "trades on-chain"))),
            h("div", { class: "row g-3" },
                h("div", { class: compact ? "col-lg-8" : "col-lg-8" }, panel("Recent confirmed trades", "Public fields only: quantity, price and the blockchain transaction.",
                    table([
                        { label: "Energy", render: t => kwh(t.quantityKwh) },
                        { label: "Price", render: t => perKwh(t.pricePerKwh) },
                        { label: "Transaction", render: t => h("span", { class: "hash", title: t.txHash, text: shortHash(t.txHash, 8) }) },
                        { label: "Block", render: t => t.blockNumber ?? "—" },
                        { label: "Confirmed", render: t => when(t.confirmedAt) },
                    ], trades.items, "No confirmed trades yet."))),
                h("div", { class: "col-lg-4" }, panel("Participating households", "Keyed HMAC pseudonyms; no energy figures are published.",
                    households.length
                        ? h("div", { class: "d-flex flex-wrap gap-2" }, households.map(p => h("span", { class: "mono badge text-bg-light border", text: p.householdPseudonym })))
                        : h("div", { class: "empty", text: "No households." }),
                    h("div", { class: "mt-3 small muted" }, badge("PUBLIC"), " The private profile (name, e-mail, balance) is visible only to the household itself and to administrators.")))));
    } catch (err) {
        mount(area, errorBox(err));
    }
}
