// Hash router, role-based navigation and session handling.
import * as api from "./api.js";
import { h, mount, errorBox, toast, closeModal } from "./ui.js";
import { loginView, publicView } from "./public.js";
import * as household from "./household.js";
import * as admin from "./admin.js";

const view = document.getElementById("view");

const NAV = {
    ROLE_HOUSEHOLD: [
        ["#/home", "Overview", household.overviewView],
        ["#/meter", "Smart meter", household.meterView],
        ["#/market", "Marketplace", household.marketView],
        ["#/trades", "My trades", household.tradesView],
        ["#/privacy", "Privacy center", household.privacyView],
        ["#/public", "Public view", publicView],
    ],
    ROLE_ADMIN: [
        ["#/admin", "Overview", admin.adminOverview],
        ["#/admin/trades", "Trades", admin.adminTrades],
        ["#/admin/offers", "Offers", admin.adminOffers],
        ["#/admin/households", "Households", admin.adminHouseholds],
        ["#/admin/anomalies", "Anomalies", admin.adminAnomalies],
        ["#/admin/blockchain", "Blockchain", admin.adminBlockchain],
        ["#/admin/audit", "Audit log", admin.adminAudit],
        ["#/public", "Public view", publicView],
    ],
    ROLE_AUDITOR: [
        ["#/admin", "Overview", admin.adminOverview],
        ["#/admin/trades", "Trades", admin.adminTrades],
        ["#/admin/anomalies", "Anomalies", admin.adminAnomalies],
        ["#/admin/blockchain", "Blockchain", admin.adminBlockchain],
        ["#/admin/audit", "Audit log", admin.adminAudit],
        ["#/public", "Public view", publicView],
    ],
};

let pollTimer = null;
let renderId = 0;

function routesFor(session) {
    return session ? (NAV[session.profile.role] || []) : [];
}

function homeFor(session) {
    const routes = routesFor(session);
    return routes.length ? routes[0][0] : "#/";
}

function renderNav(session, hash) {
    const links = document.getElementById("navLinks");
    const box = document.getElementById("sessionBox");
    const routes = session ? routesFor(session) : [["#/", "Sign in"], ["#/public", "Public view"]];
    mount(links, routes.map(([href, label]) => h("li", { class: "nav-item" },
        h("a", { class: `nav-link${href === hash ? " active" : ""}`, href, "aria-current": href === hash ? "page" : undefined, text: label }))));
    if (session) {
        const p = session.profile;
        mount(box,
            h("span", { class: "session-chip" }, h("strong", { text: p.displayAlias || p.householdCode }),
                " ", h("span", { class: "role", text: `· ${p.role.replace("ROLE_", "").toLowerCase()}` })),
            h("button", { type: "button", class: "btn btn-outline-light btn-sm", text: "Sign out", onclick: () => {
                api.clearSession();
                document.getElementById("toasts").replaceChildren(); // nothing from this session stays on screen
                location.hash = "#/";
                toast("Signed out", "secondary");
            } }));
    } else {
        mount(box);
    }
    // collapse the mobile menu after navigation
    const collapse = document.getElementById("mainNav");
    if (collapse.classList.contains("show")) bootstrap.Collapse.getOrCreateInstance(collapse).hide();
}

async function render() {
    clearTimeout(pollTimer);
    const id = ++renderId;
    const session = api.currentSession();
    let hash = location.hash || "#/";

    if (!session) {
        renderNav(null, hash);
        if (hash === "#/public") return publicView(view);
        if (hash !== "#/") { location.hash = "#/"; return; }
        return loginView(view, () => { location.hash = homeFor(api.currentSession()); });
    }

    const routes = routesFor(session);
    const route = routes.find(([href]) => href === hash);
    if (!route) { location.hash = homeFor(session); return; }
    renderNav(session, hash);

    const ctx = {
        rerender: () => { if (id === renderId) render(); },
        poll: ms => {
            clearTimeout(pollTimer);
            pollTimer = setTimeout(() => {
                // keep the page still while a dialog is open or the user is typing
                const busyUi = document.querySelector(".modal.show") || (document.activeElement && view.contains(document.activeElement)
                    && ["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement.tagName));
                if (id !== renderId) return;
                if (busyUi) ctx.poll(ms); else render();
            }, ms);
        },
    };
    try {
        await route[2](view, ctx);
    } catch (err) {
        mount(view, errorBox(err));
    }
}

api.setUnauthorizedHandler(() => {
    toast("Your session ended. Please sign in again.", "warning");
    location.hash = "#/";
    render();
});

// Escape closes the dialog even after its content was re-rendered (focus may have left the dialog)
document.addEventListener("keydown", e => {
    if (e.key === "Escape" && document.querySelector("#appModal.show")) closeModal();
});

window.addEventListener("hashchange", () => {
    // a dialog belongs to the page it was opened on
    if (document.querySelector("#appModal.show")) closeModal();
    render();
    view.focus({ preventScroll: true });
});
render();
