// REST client for the Spring Boot API (same origin). HTTP Basic credentials are kept only in this
// tab's sessionStorage, which the browser clears when the tab closes; they are never logged.

const SESSION_KEY = "p2p-energy-session";

export class ApiError extends Error {
    constructor(status, body) {
        super((body && body.message) || (status === 0 ? "The server could not be reached" : `Request failed (${status})`));
        this.status = status;
        this.body = body || null;
        this.fieldErrors = (body && body.fieldErrors) || [];
    }
}

let session = load();

function load() {
    try {
        const raw = sessionStorage.getItem(SESSION_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}

export function currentSession() {
    return session;
}

export function clearSession() {
    session = null;
    try { sessionStorage.removeItem(SESSION_KEY); } catch { /* storage unavailable */ }
}

function encodeBasic(username, password) {
    const bytes = new TextEncoder().encode(`${username}:${password}`);
    let binary = "";
    bytes.forEach(b => { binary += String.fromCharCode(b); });
    return "Basic " + btoa(binary);
}

/** Verifies the credentials against /api/me and keeps them for this tab only. */
export async function login(username, password) {
    const authorization = encodeBasic(username.trim(), password);
    const res = await raw("GET", "/api/me", { authorization });
    if (res.status === 401) {
        throw new ApiError(401, { message: "Unknown household code or wrong password" });
    }
    if (!res.ok) {
        throw new ApiError(res.status, res.data);
    }
    session = { authorization, profile: res.data };
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(session)); } catch { /* storage unavailable */ }
    return res.data;
}

export function updateProfile(profile) {
    if (session) {
        session.profile = profile;
        try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(session)); } catch { /* storage unavailable */ }
    }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(handler) {
    onUnauthorized = handler;
}

async function raw(method, path, { body, headers = {}, authorization } = {}) {
    const init = {
        method,
        headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest", ...headers },
        credentials: "omit",
        cache: "no-store",
    };
    const auth = authorization || (session && session.authorization);
    if (auth) {
        init.headers["Authorization"] = auth;
    }
    if (body !== undefined) {
        init.headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(body);
    }
    let response;
    try {
        response = await fetch(path, init);
    } catch {
        return { status: 0, ok: false, data: null };
    }
    let data = null;
    const text = await response.text();
    if (text) {
        try { data = JSON.parse(text); } catch { data = { message: text.slice(0, 200) }; }
    }
    return { status: response.status, ok: response.ok, data };
}

/**
 * Calls the API and returns {status, ok, data} for 2xx and for business outcomes that carry a
 * result body (202 FLAGGED/PENDING, 422 REJECTED). Throws ApiError for other failures.
 */
export async function call(method, path, options = {}) {
    const res = await raw(method, path, options);
    if (res.status === 401 && session && !options.authorization) {
        clearSession();
        onUnauthorized();
    }
    // ApiError bodies carry a numeric status; business results carry a string outcome or status
    const businessOutcome = res.status === 422 && res.data
        && (typeof res.data.outcome === "string" || typeof res.data.status === "string");
    if (!res.ok && !businessOutcome) {
        throw new ApiError(res.status, res.data);
    }
    return res;
}

export const get = async (path) => (await call("GET", path)).data;
export const post = (path, body, headers) => call("POST", path, { body, headers });
export const patch = (path, body) => call("PATCH", path, { body });

/** A fresh Idempotency-Key (8-64 chars of [A-Za-z0-9._:-]). */
export function newIdempotencyKey(prefix = "ui") {
    const id = (crypto.randomUUID && crypto.randomUUID())
        || Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, "0")).join("");
    return `${prefix}-${id}`;
}
