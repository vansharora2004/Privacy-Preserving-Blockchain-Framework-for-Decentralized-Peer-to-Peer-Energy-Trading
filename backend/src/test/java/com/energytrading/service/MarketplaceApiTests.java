package com.energytrading.service;

import com.energytrading.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Marketplace endpoints through the full security filter chain (HTTP Basic, roles, ownership). */
@SpringBootTest
@AutoConfigureMockMvc
class MarketplaceApiTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MeterReadingService meterReadingService;
    @Autowired private MarketplaceService marketplaceService;

    private MarketplaceFixtures fx;
    private String seller;
    private String buyer;
    private String outsider;

    @BeforeEach
    void setUp() {
        fx = new MarketplaceFixtures(userRepository, passwordEncoder, meterReadingService, marketplaceService);
        seller = fx.household("ASELL", "0.00");
        buyer = fx.household("ABUY", "100.00");
        outsider = fx.household("AOUT", "100.00");
        fx.validReading(seller, "8.0", "3.0");
    }

    private static RequestPostProcessor as(String code) {
        return httpBasic(code, MarketplaceFixtures.PASSWORD);
    }

    private static RequestPostProcessor admin() {
        return httpBasic("HH-ADMIN", "test-admin-pass");
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String createOffer(String quantity, String price) throws Exception {
        JsonNode result = body(mvc.perform(post("/api/offers").with(as(seller)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityKwh\":" + quantity + ",\"pricePerKwh\":" + price + "}"))
                .andExpect(status().isCreated()));
        return result.at("/offer/offerId").asText();
    }

    private org.springframework.test.web.servlet.ResultActions buy(String code, String offerId, String quantity, String key) throws Exception {
        var request = post("/api/trades").with(as(code)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":\"" + offerId + "\",\"quantityKwh\":" + quantity + "}");
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    @Test
    @DisplayName("Anonymous access to marketplace endpoints is 401; wrong roles get 403")
    void authenticationAndRoles() throws Exception {
        mvc.perform(get("/api/offers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/offers").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/trades/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/blockchain/status")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/offers").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantityKwh\":1,\"pricePerKwh\":5}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/trades").with(admin()).header("Idempotency-Key", "admin-key-01").contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":\"x\",\"quantityKwh\":1}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/trades").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/blockchain/status").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/trades").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/blockchain/status").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @DisplayName("Offer listing and trade views expose pseudonyms only, never codes, names or e-mail")
    void noPrivateDataInMarketplaceResponses() throws Exception {
        String offerId = createOffer("3", "6.00");
        String listing = mvc.perform(get("/api/offers").with(as(buyer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].offerId", hasItem(offerId))).andReturn().getResponse().getContentAsString();
        String trade = buy(buyer, offerId, "1", "pii-key-0001").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String publicTrades = mvc.perform(get("/api/privacy/public/trades")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String response : new String[]{listing, trade, publicTrades}) {
            assertThat(response).doesNotContain(seller, buyer, "Private ", "@market.example", "passwordHash", "\"balance\"");
        }
        assertThat(listing).contains("sellerPseudonym");
    }

    @Test
    @DisplayName("Offer status codes: 201 created, 422 rejected with findings, 400 invalid body, 200 idempotent replay")
    void offerStatusCodes() throws Exception {
        mvc.perform(post("/api/offers").with(as(seller)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityKwh\":50,\"pricePerKwh\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.findings[0].ruleCode").value("OVERSELLING"));
        mvc.perform(post("/api/offers").with(as(seller)).contentType(MediaType.APPLICATION_JSON).content("{\"quantityKwh\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("pricePerKwh"));
        String body = "{\"quantityKwh\":1,\"pricePerKwh\":5,\"expiresInMinutes\":30}";
        mvc.perform(post("/api/offers").with(as(seller)).header("Idempotency-Key", "api-offer-key-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/offers").with(as(seller)).header("Idempotency-Key", "api-offer-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/api/offers").with(as(seller)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantityKwh\":1,\"pricePerKwh\":5,\"expiresInMinutes\":2}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/offers/availability").with(as(seller))).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableKwh").value(4.0));
    }

    @Test
    @DisplayName("Trade status codes: 400 without Idempotency-Key, 202 reserved, 200 replay, 409 key reuse, 422 rejected, 404 unknown offer")
    void tradeStatusCodes() throws Exception {
        String offerId = createOffer("3", "6.00");
        buy(buyer, offerId, "1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The Idempotency-Key header is required for this request"));
        buy(buyer, offerId, "1", "api-trade-key-1").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING"))
                .andExpect(jsonPath("$.trade.yourRole").value("BUYER"));
        buy(buyer, offerId, "1", "api-trade-key-1").andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        buy(buyer, offerId, "2", "api-trade-key-1").andExpect(status().isConflict());
        buy(seller, offerId, "1", "api-trade-key-2").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.findings[*].ruleCode", hasItem("SELF_TRADE")));
        buy(buyer, offerId, "9", "api-trade-key-3").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.findings[0].ruleCode").value("OVERSELLING"));
        buy(buyer, "OF-unknown", "1", "api-trade-key-4").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Self-trading is refused by role (sellers buy as households, but not their own offer)")
    void selfTradeViaApi() throws Exception {
        String offerId = createOffer("3", "6.00");
        String other = fx.household("ASELF", "100.00");
        fx.validReading(other, "4.0", "1.0");
        // The seller role check passes (HOUSEHOLD); the rule engine rejects buying one's own offer.
        mvc.perform(post("/api/trades").with(as(seller)).header("Idempotency-Key", "self-key-001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"" + offerId + "\",\"quantityKwh\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.findings[*].ruleCode", hasItem("SELF_TRADE")));
    }

    @Test
    @DisplayName("B: recovery, attempt-history and alert endpoints are administrator-only and validate the reason")
    void recoveryEndpointsAreAdminOnly() throws Exception {
        String offerId = createOffer("3", "6.00");
        String tradeId = body(buy(buyer, offerId, "1", "rec-key-0001")).at("/trade/tradeId").asText();
        String goodReason = "{\"reason\":\"Reviewed after the local chain was reset\",\"priorDeploymentRetired\":true}";
        for (String action : new String[]{"resolve-failed", "resolve-confirmed", "re-anchor"}) {
            mvc.perform(post("/api/trades/" + tradeId + "/" + action).with(as(buyer))
                    .contentType(MediaType.APPLICATION_JSON).content(goodReason)).andExpect(status().isForbidden());
            mvc.perform(post("/api/trades/" + tradeId + "/" + action).with(as(seller))
                    .contentType(MediaType.APPLICATION_JSON).content(goodReason)).andExpect(status().isForbidden());
            mvc.perform(post("/api/trades/" + tradeId + "/" + action).with(admin())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"short\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        }
        mvc.perform(post("/api/trades/" + tradeId + "/resolve-failed").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(goodReason))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NEEDS_REVIEW")));
        mvc.perform(get("/api/trades/" + tradeId + "/attempts").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/trades/" + tradeId + "/attempts").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/blockchain/alerts").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/blockchain/alerts").with(admin())).andExpect(status().isOk());
        assertThat(body(mvc.perform(get("/api/trades/" + tradeId).with(as(buyer)))).at("/statusDetail").asText())
                .isEqualTo("Energy reserved; queued for blockchain submission");
    }

    @Test
    @DisplayName("Ownership: only participants (and admin/auditor) read a trade; only the seller or admin cancels an offer")
    void ownership() throws Exception {
        String offerId = createOffer("3", "6.00");
        String tradeId = body(buy(buyer, offerId, "1", "own-key-0001")).at("/trade/tradeId").asText();

        mvc.perform(get("/api/trades/" + tradeId).with(as(buyer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.yourRole").value("BUYER")).andExpect(jsonPath("$.history[0].actor").value("BUYER"));
        mvc.perform(get("/api/trades/" + tradeId).with(as(seller))).andExpect(status().isOk())
                .andExpect(jsonPath("$.yourRole").value("SELLER"));
        mvc.perform(get("/api/trades/" + tradeId).with(as(outsider))).andExpect(status().isForbidden());
        mvc.perform(get("/api/trades/" + tradeId).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/trades/" + tradeId + "/verification").with(as(outsider))).andExpect(status().isForbidden());
        mvc.perform(get("/api/trades/" + tradeId + "/verification").with(as(buyer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("BLOCKCHAIN_DISABLED"));
        mvc.perform(get("/api/trades/mine").with(as(seller))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tradeId").value(tradeId));

        mvc.perform(post("/api/trades/" + tradeId + "/approve").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(post("/api/trades/" + tradeId + "/cancel").with(as(seller))).andExpect(status().isForbidden());
        mvc.perform(post("/api/offers/" + offerId + "/cancel").with(as(buyer))).andExpect(status().isForbidden());
        mvc.perform(post("/api/trades/" + tradeId + "/cancel").with(as(buyer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/trades/" + tradeId + "/cancel").with(as(buyer))).andExpect(status().isConflict());
        mvc.perform(post("/api/offers/" + offerId + "/cancel").with(as(seller))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
