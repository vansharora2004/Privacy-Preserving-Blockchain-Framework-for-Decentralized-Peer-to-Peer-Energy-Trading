package com.energytrading.service;

import com.energytrading.dto.TradeRequest;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase C: the serialized public responses (own database, so the market figures are deterministic). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:privacytest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
class PublicPrivacyApiTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MeterReadingService meterReadingService;
    @Autowired private MarketplaceService marketplaceService;
    @Autowired private TradingService tradingService;
    @Autowired private PseudonymService pseudonymService;

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("C1/C2: public trades carry no household identifiers; households carry no energy figures; the summary is aggregate")
    void publicResponses() throws Exception {
        MarketplaceFixtures fx = new MarketplaceFixtures(userRepository, passwordEncoder, meterReadingService, marketplaceService);
        String sellerA = fx.household("PSA", "0.00");
        String sellerB = fx.household("PSB", "0.00");
        String buyer = fx.household("PBUY", "100.00");
        fx.validReading(sellerA, "8.0", "3.0");
        fx.validReading(sellerB, "6.0", "1.0");
        String offerA = fx.offer(sellerA, "4", "6.00");
        fx.offer(sellerB, "2", "7.00");

        // Fewer than 3 sellers: figures withheld (a single household could be singled out)
        JsonNode withheld = getJson("/api/privacy/public/market-summary");
        assertThat(withheld.get("figuresWithheld").asBoolean()).isTrue();
        assertThat(withheld.get("totalOfferedKwh").isNull()).isTrue();
        assertThat(withheld.get("openOffers").asLong()).isEqualTo(2);

        String sellerC = fx.household("PSC", "0.00");
        fx.validReading(sellerC, "5.0", "1.0");
        fx.offer(sellerC, "3", "5.00");
        JsonNode summary = getJson("/api/privacy/public/market-summary");
        assertThat(summary.get("figuresWithheld").asBoolean()).isFalse();
        assertThat(summary.get("totalOfferedKwh").decimalValue()).isEqualByComparingTo("9");
        assertThat(summary.get("minPricePerKwh").decimalValue()).isEqualByComparingTo("5.00");

        // A confirmed trade (blockchain disabled here, so the confirmation is simulated in the database)
        String tradeId = tradingService.requestTrade(buyer, new TradeRequest(offerA, new BigDecimal("1")), "privacy-key-01")
                .trade().tradeId();
        jdbc.update("UPDATE trade_transactions SET status = 'CONFIRMED', tx_hash = ?, block_number = 42, confirmed_at = ? WHERE trade_id = ?",
                "0x" + "ab".repeat(32), LocalDateTime.now(), tradeId);

        String tradesBody = mvc.perform(get("/api/privacy/public/trades")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode item = json.readTree(tradesBody).get("items").get(0);
        assertThat(fields(item)).containsExactlyInAnyOrder("quantityKwh", "pricePerKwh", "txHash", "blockNumber", "confirmedAt");
        assertThat(tradesBody).doesNotContain("Pseudonym", tradeId, sellerA, buyer, offerA,
                pseudonymService.householdPseudonym(sellerA), pseudonymService.householdPseudonym(buyer));

        String householdsBody = mvc.perform(get("/api/privacy/public/households")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode households = json.readTree(householdsBody);
        households.forEach(h -> assertThat(fields(h)).containsExactly("householdPseudonym"));
        assertThat(householdsBody).doesNotContain("Kwh", "kwh", sellerA, "Private");

        // The directory pseudonym cannot be joined to the public trade (no pseudonym there)
        assertThat(householdsBody).contains(pseudonymService.householdPseudonym(sellerA));
        assertThat(tradesBody).doesNotContain(pseudonymService.householdPseudonym(sellerA));
    }
}
