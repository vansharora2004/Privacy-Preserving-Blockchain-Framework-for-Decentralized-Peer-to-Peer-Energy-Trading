package com.energytrading.security;

import com.energytrading.model.AnomalyLog;
import com.energytrading.model.MeterReading;
import com.energytrading.model.User;
import com.energytrading.model.enums.AnomalyReviewStatus;
import com.energytrading.model.enums.AnomalySeverity;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.model.enums.Role;
import com.energytrading.privacy.DigestService;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.AnomalyLogRepository;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role and ownership policy through the full security filter chain with HTTP Basic logins.
 * Uses test-only accounts; the seeded admin's password comes from the test properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationApiTests {

    private static final String HOUSEHOLD_PASSWORD = "it-household-pass";
    private static final String AUDITOR_PASSWORD = "it-auditor-pass";
    private static final String ALICE = "T-ALICE";
    private static final String BOB = "T-BOB";
    private static final String AUDITOR = "T-AUDITOR";
    private static final String ALICE_READING = "T-ALICE-R1";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MeterReadingRepository meterReadingRepository;
    @Autowired private AnomalyLogRepository anomalyLogRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DigestService digestService;
    @Autowired private PseudonymService pseudonymService;

    @BeforeEach
    void accounts() {
        ensureUser(ALICE, Role.ROLE_HOUSEHOLD, HOUSEHOLD_PASSWORD, "Alice Tester", "alice@test.example");
        ensureUser(BOB, Role.ROLE_HOUSEHOLD, HOUSEHOLD_PASSWORD, "Bob Tester", "bob@test.example");
        ensureUser(AUDITOR, Role.ROLE_AUDITOR, AUDITOR_PASSWORD, "Audit Tester", "auditor@test.example");
        if (!meterReadingRepository.existsByReadingId(ALICE_READING)) {
            User alice = userRepository.findByHouseholdCode(ALICE).orElseThrow();
            LocalDateTime time = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);
            String digest = digestService.meterReadingDigest(ALICE, ALICE_READING, 1L, new BigDecimal("4"),
                    new BigDecimal("1"), new BigDecimal("3"), time);
            meterReadingRepository.save(new MeterReading(alice, ALICE_READING, 1L, 4.0, 1.0, 3.0, time,
                    ReadingValidationStatus.VALID, digest));
        }
    }

    private User ensureUser(String code, Role role, String password, String fullName, String email) {
        return userRepository.findByHouseholdCode(code).orElseGet(() -> userRepository.save(new User(code, code + " alias",
                fullName, email, passwordEncoder.encode(password), role, new BigDecimal("100.00"), 3.0, 0.0)));
    }

    private static RequestPostProcessor alice() {
        return httpBasic(ALICE, HOUSEHOLD_PASSWORD);
    }

    private static RequestPostProcessor bob() {
        return httpBasic(BOB, HOUSEHOLD_PASSWORD);
    }

    private static RequestPostProcessor admin() {
        return httpBasic("HH-ADMIN", "test-admin-pass");
    }

    private static RequestPostProcessor auditor() {
        return httpBasic(AUDITOR, AUDITOR_PASSWORD);
    }

    private Long anomalyId() {
        return anomalyLogRepository.save(new AnomalyLog("METER_READING", pseudonymService.householdPseudonym(ALICE),
                "SEQUENCE_GAP", AnomalySeverity.LOW, "[sequence 9] test anomaly", AnomalyReviewStatus.DETECTED)).getId();
    }

    private static String readingJson(String readingId, long seq, String generated, String householdCode) {
        String household = householdCode == null ? "" : "\"householdCode\":\"" + householdCode + "\",";
        return "{" + household + "\"readingId\":\"" + readingId + "\",\"sequenceNo\":" + seq
                + ",\"energyGeneratedKwh\":" + generated + ",\"energyConsumedKwh\":1.0,\"readingTime\":\""
                + LocalDateTime.now().minusMinutes(60 - seq).truncatedTo(ChronoUnit.SECONDS) + "\"}";
    }

    // 1 + 7 --------------------------------------------------------------------------------

    @Test
    @DisplayName("1/7. Anonymous access to protected endpoints is 401; a wrong password is 401")
    void anonymousDenied() throws Exception {
        for (String path : new String[]{"/api/me", "/api/anomalies", "/api/audit-logs", "/api/households",
                "/api/privacy/households/" + ALICE, "/api/households/" + ALICE + "/meter-readings",
                "/api/meter-readings/" + ALICE_READING + "/integrity"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }
        mvc.perform(post("/api/meter-readings").contentType(MediaType.APPLICATION_JSON).content(readingJson("X", 1, "1", null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").with(httpBasic(ALICE, "wrong-password"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").with(httpBasic("NO-SUCH-USER", "x"))).andExpect(status().isUnauthorized());
    }

    // 2 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("2. A household can read its own private profile, readings and integrity results")
    void householdOwnData() throws Exception {
        String body = mvc.perform(get("/api/privacy/households/" + ALICE).with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.householdCode").value(ALICE))
                .andExpect(jsonPath("$.email").value("alice@test.example"))
                .andExpect(jsonPath("$.householdPseudonym").value(pseudonymService.householdPseudonym(ALICE)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("password", "$2a$", "\"id\"");

        mvc.perform(get("/api/me").with(alice())).andExpect(status().isOk()).andExpect(jsonPath("$.householdCode").value(ALICE));
        mvc.perform(get("/api/households/" + ALICE + "/meter-readings").with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].readingId", hasItem(ALICE_READING)));
        mvc.perform(get("/api/meter-readings/" + ALICE_READING + "/integrity").with(alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MATCH"))
                .andExpect(jsonPath("$.energyGeneratedKwh").doesNotExist());
    }

    // 3 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("3. One household cannot read or act on another household's private data (403, audited)")
    void householdCannotAccessOthers() throws Exception {
        String profile = mvc.perform(get("/api/privacy/households/" + ALICE).with(bob()))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(profile).doesNotContain("alice@test.example", "Alice Tester");
        mvc.perform(get("/api/households/" + ALICE + "/meter-readings").with(bob())).andExpect(status().isForbidden());
        mvc.perform(get("/api/meter-readings/" + ALICE_READING + "/integrity").with(bob())).andExpect(status().isForbidden());
        mvc.perform(post("/api/meter-readings").with(bob()).contentType(MediaType.APPLICATION_JSON)
                .content(readingJson("T-BOB-AS-ALICE", 1, "1", ALICE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/households").with(bob())).andExpect(status().isForbidden());

        assertThat(meterReadingRepository.existsByReadingId("T-BOB-AS-ALICE")).isFalse();
        assertThat(auditLogRepository.findByActorUsername(BOB)).anyMatch(a -> a.getAction().equals("ACCESS_DENIED"));
    }

    // 4 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("4. Admin policy: may read all private data and review anomalies, but may not submit readings or offers")
    void adminPolicy() throws Exception {
        String list = mvc.perform(get("/api/households").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].householdCode", hasItem(ALICE)))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("password", "$2a$");
        mvc.perform(get("/api/privacy/households/" + ALICE).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/households/" + ALICE + "/meter-readings").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/meter-readings/" + ALICE_READING + "/integrity").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/anomalies").with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/audit-logs").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ipAddress").doesNotExist());
        mvc.perform(patch("/api/anomalies/" + anomalyId() + "/review").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewStatus\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.outcome").value("FLAGGED"));

        mvc.perform(post("/api/meter-readings").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(readingJson("ADMIN-R", 1, "1", null))).andExpect(status().isForbidden());
        mvc.perform(post("/api/anomaly-checks/offer").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantityKwh\":1,\"pricePerKwh\":5}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/privacy/households/NO-SUCH-HOUSEHOLD").with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("4b. Auditor policy: anomaly/audit logs and integrity checks only, no private household data")
    void auditorPolicy() throws Exception {
        mvc.perform(get("/api/anomalies").with(auditor())).andExpect(status().isOk());
        mvc.perform(get("/api/audit-logs").with(auditor())).andExpect(status().isOk());
        mvc.perform(get("/api/meter-readings/" + ALICE_READING + "/integrity").with(auditor())).andExpect(status().isOk());
        mvc.perform(get("/api/me").with(auditor())).andExpect(status().isOk()).andExpect(jsonPath("$.householdCode").value(AUDITOR));

        mvc.perform(get("/api/privacy/households/" + ALICE).with(auditor())).andExpect(status().isForbidden());
        mvc.perform(get("/api/households").with(auditor())).andExpect(status().isForbidden());
        mvc.perform(get("/api/households/" + ALICE + "/meter-readings").with(auditor())).andExpect(status().isForbidden());
        mvc.perform(patch("/api/anomalies/" + anomalyId() + "/review").with(auditor())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reviewStatus\":\"RESOLVED\"}")).andExpect(status().isForbidden());
    }

    // 5 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("5. The public household view leaks no names, e-mail, household codes, database IDs or credentials")
    void publicViewDoesNotLeak() throws Exception {
        String body = mvc.perform(get("/api/privacy/public/households"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].householdPseudonym", hasItem(pseudonymService.householdPseudonym(ALICE))))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(ALICE, "HH-001", "HH-ADMIN", "alice@", "Alice", "Sharma", "email", "fullName",
                "password", "$2a$", "\"id\"", "balance", "energyGeneratedKwh");
    }

    // 6 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("6. Households cannot read anomaly or audit logs, or review anomalies")
    void logsBlockedForHouseholds() throws Exception {
        mvc.perform(get("/api/anomalies").with(alice())).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/audit-logs").with(alice())).andExpect(status().isForbidden());
        mvc.perform(patch("/api/anomalies/" + anomalyId() + "/review").with(alice())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reviewStatus\":\"DISMISSED\"}")).andExpect(status().isForbidden());
    }

    // Status codes for submissions and errors ----------------------------------------------

    @Test
    @DisplayName("Submission status codes: 201 VALID, 202 FLAGGED, 422 REJECTED, 400 malformed, 415 wrong type")
    void submissionStatusCodes() throws Exception {
        String carol = "T-CAROL-" + UUID.randomUUID().toString().substring(0, 6);
        ensureUser(carol, Role.ROLE_HOUSEHOLD, HOUSEHOLD_PASSWORD, "Carol Tester", null);
        RequestPostProcessor asCarol = httpBasic(carol, HOUSEHOLD_PASSWORD);

        mvc.perform(post("/api/meter-readings").with(asCarol).contentType(MediaType.APPLICATION_JSON)
                        .content(readingJson(carol + "-1", 1, "3.0", null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("VALID"));
        mvc.perform(post("/api/meter-readings").with(asCarol).contentType(MediaType.APPLICATION_JSON)
                        .content(readingJson(carol + "-3", 3, "3.5", null)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("FLAGGED"))
                .andExpect(jsonPath("$.sellableSurplusKwh").doesNotExist())
                .andExpect(jsonPath("$.findings[0].ruleCode").value("SEQUENCE_GAP"));
        mvc.perform(post("/api/meter-readings").with(asCarol).contentType(MediaType.APPLICATION_JSON)
                        .content(readingJson(carol + "-4", 4, "-1", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.stored").value(false))
                .andExpect(jsonPath("$.availableKwh").doesNotExist())
                .andExpect(jsonPath("$.sellableSurplusKwh").doesNotExist())
                .andExpect(jsonPath("$.findings[0].ruleCode").value("INVALID_QUANTITY"))
                .andExpect(jsonPath("$.findings[0].field").value("energyGeneratedKwh"));
        mvc.perform(post("/api/meter-readings").with(asCarol).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(post("/api/meter-readings").with(asCarol).contentType(MediaType.APPLICATION_JSON)
                        .content(readingJson(carol + "-5", 5, "NaN", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/meter-readings").with(asCarol).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());

        // Offer pre-check: carol's validated surplus is 2 kWh (3.0 generated - 1.0 consumed)
        mvc.perform(post("/api/anomaly-checks/offer").with(asCarol).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityKwh\":50,\"pricePerKwh\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.validatedSurplusKwh").value(2.0))
                .andExpect(jsonPath("$.findings[*].ruleCode", hasItem("OVERSELLING")))
                .andExpect(jsonPath("$.findings[*].ruleCode", hasItem("SUSPICIOUS_PRICE")));
        mvc.perform(post("/api/anomaly-checks/offer").with(asCarol).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityKwh\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("pricePerKwh"));
        assertThat(auditLogRepository.findByActorUsername(carol))
                .anyMatch(a -> a.getAction().equals("OFFER_PRECHECK") && a.getOutcome().equals("REJECTED"));
    }

    // 8 ------------------------------------------------------------------------------------

    @Test
    @DisplayName("8. CORS: configured origins only, never a wildcard with credentials")
    void corsRestricted() throws Exception {
        mvc.perform(options("/api/me")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(get("/api/me").with(alice()).header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mvc.perform(options("/api/me")
                        .header(HttpHeaders.ORIGIN, "http://localhost:8082")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8082"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }
}
