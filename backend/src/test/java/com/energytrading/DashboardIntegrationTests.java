package com.energytrading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Batch 4: the dashboard is served without opening the API, and with a strict content security policy. */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardIntegrationTests {

    private static final String[] ASSETS = {"/index.html", "/css/app.css", "/js/app.js", "/js/api.js", "/js/ui.js",
            "/js/trades.js", "/js/public.js", "/js/household.js", "/js/admin.js",
            "/webjars/bootstrap/5.3.3/css/bootstrap.min.css", "/webjars/bootstrap/5.3.3/js/bootstrap.bundle.min.js"};

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("The dashboard and its local assets load without authentication, with CSP and no-referrer headers")
    void dashboardIsPublicWithStrictHeaders() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        for (String asset : ASSETS) {
            mockMvc.perform(get(asset))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                    .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }
    }

    @Test
    @DisplayName("Served pages contain no inline script, external origin or credential")
    void noInlineScriptsOrSecrets() throws Exception {
        String html = mockMvc.perform(get("/index.html")).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContainPattern("<script(?![^>]*\\ssrc=)[^>]*>");
        assertThat(html).doesNotContain("http://", "https://");
        for (String asset : ASSETS) {
            if (asset.startsWith("/js/")) {
                String js = mockMvc.perform(get(asset)).andReturn().getResponse().getContentAsString();
                assertThat(js).as(asset).doesNotContainIgnoringCase("password\":").doesNotContain("innerHTML", "eval(", "PRIVATE_KEY");
            }
        }
    }

    @Test
    @DisplayName("Opening the dashboard does not open the API: data endpoints still return 401")
    void apiStillProtected() throws Exception {
        for (String path : new String[]{"/api/me", "/api/offers", "/api/trades", "/api/households", "/api/audit-logs"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        // A traversal through the public asset path is refused before it reaches the API
        mockMvc.perform(get("/js/../api/households")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Dashboard (XHR) calls get a JSON 401 without the Basic challenge, so the browser shows no login dialog")
    void xhrUnauthorizedHasNoChallenge() throws Exception {
        mockMvc.perform(get("/api/me").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().string(containsString("Authentication required")));
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
    }
}
