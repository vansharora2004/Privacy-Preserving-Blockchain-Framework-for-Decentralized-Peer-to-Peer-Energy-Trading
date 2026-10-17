// DOM helpers. Every value from the server is inserted as text (never as HTML).

export function h(tag, attrs = {}, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
        if (value === undefined || value === null || value === false) continue;
        if (key === "class") el.className = value;
        else if (key === "text") el.textContent = value;
        else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2), value);
        else if (key === "dataset") Object.assign(el.dataset, value);
        else el.setAttribute(key, value === true ? "" : value);
    }
    append(el, children);
    return el;
}

function append(el, children) {
    for (const child of children.flat(Infinity)) {
        if (child === undefined || child === null || child === false) continue;
        el.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    }
}

export function mount(target, ...children) {
    target.replaceChildren();
    append(target, children);
}

// ------------------------------------------------------------------ formatting

const kwhFormat = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 3 });
const moneyFormat = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 2 });
const dateFormat = new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" });

export const kwh = v => (v === null || v === undefined ? "—" : `${kwhFormat.format(Number(v))} kWh`);
export const money = v => (v === null || v === undefined ? "—" : moneyFormat.format(Number(v)));
export const perKwh = v => (v === null || v === undefined ? "—" : `${moneyFormat.format(Number(v))}/kWh`);
export function when(v) {
    if (!v) return "—";
    const d = new Date(v);
    return Number.isNaN(d.getTime()) ? String(v) : dateFormat.format(d);
}
export function shortHash(v, keep = 10) {
    if (!v) return "—";
    return v.length > keep * 2 + 2 ? `${v.slice(0, keep + 2)}…${v.slice(-keep)}` : v;
}
/** Local time as yyyy-MM-ddTHH:mm:ss (the backend's LocalDateTime format). */
export function localIso(date = new Date()) {
    const p = n => String(n).padStart(2, "0");
    return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`;
}

// ------------------------------------------------------------------ status badges

const TONES = {
    ok: ["CONFIRMED", "VALID", "PASSED", "ACTIVE", "PARTIALLY_FILLED", "MATCH", "RESOLVED", "MINED_SUCCESS", "UP", "LOW"],
    wait: ["PENDING", "SUBMITTED", "BROADCAST", "SIGNED", "UNDER_REVIEW", "SUBMIT", "REANCHOR"],
    warn: ["FLAGGED", "NEEDS_REVIEW", "DETECTED", "UNCERTAIN", "DEPLOYMENT_CHANGED", "UNVERIFIABLE", "MEDIUM",
        "UNAVAILABLE", "NO_STORED_DIGEST"],
    bad: ["REJECTED", "FAILED", "MISMATCH", "VOIDED", "NOT_ON_CHAIN", "HIGH", "CRITICAL", "MINED_REVERTED", "DOWN"],
};

export function tone(value) {
    const v = String(value || "").toUpperCase();
    for (const [name, list] of Object.entries(TONES)) {
        if (list.includes(v)) return name;
    }
    return "neutral";
}

export function badge(value, title) {
    return h("span", { class: `status status-${tone(value)}`, title: title || undefined, text: value || "—" });
}

// ------------------------------------------------------------------ layout pieces

export function pageHead(title, subtitle, ...actions) {
    return h("div", { class: "page-head" },
        h("div", {}, h("h1", { text: title }), subtitle ? h("p", { text: subtitle }) : null),
        actions.length ? h("div", { class: "d-flex flex-wrap gap-2" }, actions) : null);
}

export function panel(title, subtitle, ...children) {
    return h("section", { class: "panel" },
        title ? h("h2", { text: title }) : null,
        subtitle ? h("p", { class: "panel-sub", text: subtitle }) : null,
        children);
}

export function stat(label, value, hint, variant = "") {
    return h("div", { class: `stat ${variant}` },
        h("div", { class: "label", text: label }),
        h("div", { class: "value", text: value }),
        hint ? h("div", { class: "hint", text: hint }) : null);
}

/** columns: [{label, render(row) -> Node|string, class}] */
export function table(columns, rows, emptyText = "Nothing to show yet.") {
    if (!rows || rows.length === 0) {
        return h("div", { class: "empty", text: emptyText });
    }
    return h("div", { class: "table-wrap" },
        h("table", { class: "table table-hover align-middle" },
            h("thead", {}, h("tr", {}, columns.map(c => h("th", { scope: "col", class: c.class, text: c.label })))),
            h("tbody", {}, rows.map(row => h("tr", {}, columns.map(c => h("td", { class: c.class }, c.render(row))))))));
}

export function kv(pairs) {
    return h("dl", { class: "kv" }, pairs.filter(Boolean).flatMap(([k, v]) => [h("dt", { text: k }), h("dd", {}, v ?? "—")]));
}

export function button(label, onclick, variant = "outline-primary", extra = {}) {
    return h("button", { type: "button", class: `btn btn-${variant} btn-sm`, onclick, ...extra }, label);
}

export function spinner(text = "Loading…") {
    return h("div", { class: "d-flex align-items-center gap-2 muted py-3" },
        h("div", { class: "spinner-border spinner-border-sm", role: "status" }), h("span", { text }));
}

/** Findings returned by the validation and anomaly rules. */
export function findings(list) {
    if (!list || list.length === 0) return null;
    return h("div", {}, list.map(f => h("div", { class: "finding" },
        badge(f.outcome || f.severity), " ", h("code", { text: f.ruleCode }), " — ", f.message)));
}

export function resultBox(variant, title, ...children) {
    return h("div", { class: `result-box ${variant}`, role: "status" }, h("strong", { text: title }), children);
}

/** A readable box for an ApiError, including field-level validation errors. */
export function errorBox(err) {
    const status = err && err.status ? ` (HTTP ${err.status})` : "";
    const fields = (err && err.fieldErrors) || [];
    return resultBox("bad", `${(err && err.message) || "Something went wrong"}${status}`,
        fields.length ? h("ul", { class: "mb-0 mt-1" }, fields.map(f => h("li", { text: `${f.field}: ${f.message}` }))) : null);
}

// ------------------------------------------------------------------ toasts and modal

export function toast(message, variant = "success") {
    const el = h("div", { class: `toast align-items-center text-bg-${variant} border-0`, role: "alert", "aria-live": "assertive" },
        h("div", { class: "d-flex" },
            h("div", { class: "toast-body", text: message }),
            h("button", { type: "button", class: "btn-close btn-close-white me-2 m-auto", "data-bs-dismiss": "toast", "aria-label": "Close" })));
    document.getElementById("toasts").appendChild(el);
    const t = new bootstrap.Toast(el, { delay: 4500 });
    el.addEventListener("hidden.bs.toast", () => el.remove());
    t.show();
}

const modalEl = () => document.getElementById("appModal");
// Bootstrap ignores hide() while the dialog is still fading in, so a close requested then is queued
let modalOpening = false;
let closeQueued = false;
function watchModal() {
    const el = modalEl();
    if (!el || el.dataset.watched) return;
    el.dataset.watched = "1";
    el.addEventListener("shown.bs.modal", () => {
        modalOpening = false;
        if (closeQueued) { closeQueued = false; bootstrap.Modal.getOrCreateInstance(el).hide(); }
    });
}

export function openModal(title, body, footerButtons = []) {
    document.getElementById("appModalTitle").textContent = title;
    mount(document.getElementById("appModalBody"), body);
    mount(document.getElementById("appModalFooter"),
        footerButtons, h("button", { type: "button", class: "btn btn-light btn-sm", "data-bs-dismiss": "modal", text: "Close" }));
    watchModal();
    const modal = bootstrap.Modal.getOrCreateInstance(modalEl());
    if (!modalEl().classList.contains("show")) {
        modalOpening = true;
        closeQueued = false;
    }
    modal.show();
    return modal;
}

export function closeModal() {
    if (modalOpening) {
        closeQueued = true;
        return;
    }
    bootstrap.Modal.getOrCreateInstance(modalEl()).hide();
}

export function modalBody() {
    return document.getElementById("appModalBody");
}

/** Disables a button while an async action runs; returns the action's result. */
export async function busy(btn, action) {
    const original = btn.textContent;
    btn.disabled = true;
    btn.textContent = "Working…";
    try {
        return await action();
    } finally {
        btn.disabled = false;
        btn.textContent = original;
    }
}
