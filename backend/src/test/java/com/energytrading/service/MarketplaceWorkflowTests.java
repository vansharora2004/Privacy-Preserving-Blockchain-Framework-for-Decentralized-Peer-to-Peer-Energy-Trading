package com.energytrading.service;

import com.energytrading.dto.CreateOfferRequest;
import com.energytrading.dto.FindingView;
import com.energytrading.dto.OfferResult;
import com.energytrading.dto.TradeRequest;
import com.energytrading.dto.TradeResult;
import com.energytrading.dto.TradeView;
import com.energytrading.exception.BusinessRuleException;
import com.energytrading.model.EnergyOffer;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.enums.OfferStatus;
import com.energytrading.model.enums.Role;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.repository.AnomalyLogRepository;
import com.energytrading.repository.AuditLogRepository;
import com.energytrading.repository.EnergyOfferRepository;
import com.energytrading.repository.MeterReadingRepository;
import com.energytrading.repository.TradeEventRepository;
import com.energytrading.repository.TradeTransactionRepository;
import com.energytrading.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 6 workflow against H2 with the blockchain disabled (trades stop at PENDING). */
@SpringBootTest
class MarketplaceWorkflowTests {

    @Autowired private MarketplaceService marketplaceService;
    @Autowired private TradingService tradingService;
    @Autowired private MeterReadingService meterReadingService;
    @Autowired private UserRepository userRepository;
    @Autowired private EnergyOfferRepository offerRepository;
    @Autowired private TradeTransactionRepository tradeRepository;
    @Autowired private TradeEventRepository eventRepository;
    @Autowired private MeterReadingRepository readingRepository;
    @Autowired private AnomalyLogRepository anomalyLogRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PseudonymService pseudonymService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private MarketplaceFixtures fx;
    private String seller;
    private String buyer;

    @BeforeEach
    void setUp() {
        fx = new MarketplaceFixtures(userRepository, passwordEncoder, meterReadingService, marketplaceService);
        seller = fx.household("SELLER", "0.00");
        buyer = fx.household("BUYER", "100.00");
    }

    private static Authentication auth(String code, Role role) {
        return new UsernamePasswordAuthenticationToken(code, null, List.of(new SimpleGrantedAuthority(role.name())));
    }

    private OfferResult offer(String code, String quantity, String price) {
        return marketplaceService.createOffer(code, new CreateOfferRequest(new BigDecimal(quantity), new BigDecimal(price), null), null);
    }

    private TradeResult buy(String code, String offerId, String quantity) {
        return tradingService.requestTrade(code, new TradeRequest(offerId, new BigDecimal(quantity)), "key-" + UUID.randomUUID());
    }

    private static List<String> codes(List<FindingView> findings) {
        return findings.stream().map(FindingView::ruleCode).toList();
    }

    private EnergyOffer offerRow(String offerId) {
        return offerRepository.findByOfferId(offerId).orElseThrow();
    }

    private BigDecimal balance(String code) {
        return fx.user(code).getBalance();
    }

    // ------------------------------------------------------------------ offers

    @Test
    @DisplayName("Offers are created from validated surplus and cannot exceed what is still available")
    void offersFromValidatedSurplus() {
        fx.validReading(seller, "8.0", "3.0"); // 5 kWh sellable

        OfferResult first = offer(seller, "3", "6.00");
        assertThat(first.outcome()).isEqualTo("PASSED");
        assertThat(first.offer().status()).isEqualTo("ACTIVE");
        assertThat(first.offer().sourceReadingId()).isEqualTo(seller + "-R1");
        assertThat(first.availableKwh()).isEqualByComparingTo("2");

        OfferResult tooMuch = offer(seller, "2.5", "6.00");
        assertThat(tooMuch.outcome()).isEqualTo("REJECTED");
        assertThat(tooMuch.offer()).isNull();
        assertThat(codes(tooMuch.findings())).containsExactly("OVERSELLING");
        assertThat(tooMuch.findings().get(0).message()).contains("2.5 kWh exceeds the validated available surplus of 2 kWh");

        assertThat(offer(seller, "2", "6.00").outcome()).isEqualTo("PASSED");
        assertThat(marketplaceService.availableForNewOffers(seller)).isEqualByComparingTo("0");
        assertThat(anomalyLogRepository.findByTargetReferenceId(pseudonymService.householdPseudonym(seller)))
                .extracting(a -> a.getRuleCode()).contains("OVERSELLING");
        assertThat(auditLogRepository.findByActorUsername(seller)).filteredOn(a -> a.getAction().equals("OFFER_CREATE"))
                .extracting(a -> a.getOutcome()).containsExactlyInAnyOrder("PASSED", "REJECTED", "PASSED");
    }

    @Test
    @DisplayName("Without a recent validated reading nothing can be offered; consumed energy is never offerable")
    void noValidatedSurplus() {
        OfferResult none = offer(seller, "1", "5.00");
        assertThat(none.outcome()).isEqualTo("REJECTED");
        assertThat(none.findings().get(0).message()).contains("No validated meter reading");

        fx.validReading(seller, "1.0", "4.0"); // deficit
        assertThat(marketplaceService.availableForNewOffers(seller)).isEqualByComparingTo("0");
        assertThat(offer(seller, "0.5", "5.00").outcome()).isEqualTo("REJECTED");

        String stale = fx.household("STALE", "0");
        fx.validReading(stale, "9.0", "1.0");
        jdbc.update("UPDATE meter_readings SET reading_time = ? WHERE reading_id = ?", LocalDateTime.now().minusDays(2), stale + "-R1");
        OfferResult old = offer(stale, "1", "5.00");
        assertThat(old.outcome()).isEqualTo("REJECTED");
        assertThat(old.findings().get(0).message()).contains("older than 24 hours");
    }

    @Test
    @DisplayName("Invalid quantities and prices are rejected; a suspicious price creates a FLAGGED offer")
    void offerQuantityAndPriceRules() {
        fx.validReading(seller, "8.0", "1.0");
        assertThat(codes(offer(seller, "0", "5.00").findings())).containsExactly("INVALID_QUANTITY");
        assertThat(codes(offer(seller, "-1", "5.00").findings())).containsExactly("INVALID_QUANTITY");
        assertThat(codes(offer(seller, "1.0001", "5.00").findings())).containsExactly("INVALID_PRECISION");
        assertThat(codes(offer(seller, "1", "0").findings())).containsExactly("INVALID_PRICE");
        assertThat(codes(offer(seller, "1", "5.001").findings())).containsExactly("INVALID_PRECISION");

        OfferResult flagged = offer(seller, "1", "100.00");
        assertThat(flagged.outcome()).isEqualTo("FLAGGED");
        assertThat(flagged.offer()).isNotNull();
        assertThat(codes(flagged.findings())).containsExactly("SUSPICIOUS_PRICE");

        assertThatThrownBy(() -> marketplaceService.createOffer(seller,
                new CreateOfferRequest(BigDecimal.ONE, new BigDecimal("5.00"), 1), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("expiresInMinutes");
    }

    @Test
    @DisplayName("Offer creation is idempotent per Idempotency-Key")
    void offerIdempotency() {
        fx.validReading(seller, "8.0", "1.0");
        CreateOfferRequest request = new CreateOfferRequest(new BigDecimal("2"), new BigDecimal("5.00"), 30);
        OfferResult first = marketplaceService.createOffer(seller, request, "offer-key-0001");
        OfferResult retry = marketplaceService.createOffer(seller, request, "offer-key-0001");
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.offer().offerId()).isEqualTo(first.offer().offerId());
        assertThat(marketplaceService.offersOf(seller)).hasSize(1);
        assertThat(marketplaceService.availableForNewOffers(seller)).isEqualByComparingTo("5");

        assertThatThrownBy(() -> marketplaceService.createOffer(seller,
                new CreateOfferRequest(new BigDecimal("3"), new BigDecimal("5.00"), 30), "offer-key-0001"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("different offer");
        assertThatThrownBy(() -> marketplaceService.createOffer(seller, request, "short"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("Offer cancellation: owner or admin only, releases pending reservations and unsold energy")
    void offerCancellation() {
        fx.validReading(seller, "6.0", "1.0");
        String offerId = fx.offer(seller, "4", "5.00");
        TradeResult reserved = buy(buyer, offerId, "1");
        assertThat(reserved.outcome()).isEqualTo("PENDING");

        assertThatThrownBy(() -> marketplaceService.cancelOffer(auth(buyer, Role.ROLE_HOUSEHOLD), offerId))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(marketplaceService.cancelOffer(auth(seller, Role.ROLE_HOUSEHOLD), offerId).status()).isEqualTo("CANCELLED");
        TradeTransaction trade = tradeRepository.findByTradeId(reserved.trade().tradeId()).orElseThrow();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
        assertThat(balance(buyer)).isEqualByComparingTo("100.00");
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(4.0);
        assertThat(marketplaceService.availableForNewOffers(seller)).isEqualByComparingTo("5");

        assertThatThrownBy(() -> marketplaceService.cancelOffer(auth(seller, Role.ROLE_HOUSEHOLD), offerId))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("CANCELLED");

        String second = fx.offer(seller, "1", "5.00");
        assertThat(marketplaceService.cancelOffer(auth("HH-ADMIN", Role.ROLE_ADMIN), second).status()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Expired offers are closed, cannot be bought, and return their energy")
    void offerExpiration() {
        fx.validReading(seller, "6.0", "1.0");
        String offerId = fx.offer(seller, "5", "5.00");
        assertThat(marketplaceService.availableForNewOffers(seller)).isEqualByComparingTo("0");
        jdbc.update("UPDATE energy_offers SET expires_at = ? WHERE offer_id = ?", LocalDateTime.now().minusMinutes(1), offerId);

        assertThatThrownBy(() -> buy(buyer, offerId, "1")).isInstanceOf(BusinessRuleException.class).hasMessageContaining("expired");
        assertThat(marketplaceService.openOffers(0, 100).items()).noneMatch(o -> o.offerId().equals(offerId));

        assertThat(marketplaceService.expireOffers()).isGreaterThanOrEqualTo(1);
        assertThat(offerRow(offerId).getStatus()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(marketplaceService.availableForNewOffers(seller)).isEqualByComparingTo("5");
    }

    // ------------------------------------------------------------------ trades

    @Test
    @DisplayName("A purchase reserves energy, escrows funds and records history; price comes from the offer")
    void reservation() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "6.50");

        TradeResult result = buy(buyer, offerId, "2");
        assertThat(result.outcome()).isEqualTo("PENDING");
        TradeView view = result.trade();
        assertThat(view.totalAmount()).isEqualByComparingTo("13.00");
        assertThat(view.pricePerKwh()).isEqualByComparingTo("6.50");
        assertThat(view.yourRole()).isEqualTo("BUYER");
        assertThat(view.sellerPseudonym()).isEqualTo(pseudonymService.householdPseudonym(seller));
        assertThat(view.blockchain().recordDigest()).hasSize(64);
        assertThat(view.blockchain().txHash()).isNull();
        assertThat(view.history()).extracting(h -> h.toStatus() + "/" + h.actor()).containsExactly("PENDING/BUYER");

        assertThat(balance(buyer)).isEqualByComparingTo("87.00");
        assertThat(balance(seller)).as("seller is paid only on confirmation").isEqualByComparingTo("0.00");
        EnergyOffer offer = offerRow(offerId);
        assertThat(offer.getRemainingKwh()).isEqualTo(3.0);
        assertThat(offer.getStatus()).isEqualTo(OfferStatus.PARTIALLY_FILLED);
        assertThat(auditLogRepository.findByActorUsername(buyer)).anyMatch(a -> a.getAction().equals("TRADE_REQUEST") && a.getOutcome().equals("PENDING"));
    }

    @Test
    @DisplayName("Self-trading, overselling, invalid quantities and insufficient funds are rejected without reserving anything")
    void tradeRejections() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "6.00");
        String poor = fx.household("POOR", "1.00");

        assertThat(codes(buy(seller, offerId, "1").findings())).contains("SELF_TRADE");
        assertThat(codes(buy(buyer, offerId, "6").findings())).containsExactly("OVERSELLING");
        assertThat(codes(buy(buyer, offerId, "0").findings())).containsExactly("INVALID_QUANTITY");
        assertThat(codes(buy(buyer, offerId, "-1").findings())).containsExactly("INVALID_QUANTITY");
        assertThat(codes(buy(poor, offerId, "1").findings())).containsExactly("INSUFFICIENT_FUNDS");

        TradeResult rejected = buy(buyer, offerId, "6");
        assertThat(rejected.outcome()).isEqualTo("REJECTED");
        assertThat(rejected.trade()).isNull();
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(5.0);
        assertThat(balance(buyer)).isEqualByComparingTo("100.00");
        assertThat(tradeRepository.findByBuyerId(fx.user(buyer).getId())).isEmpty();
        assertThat(anomalyLogRepository.findByTargetReferenceId(pseudonymService.householdPseudonym(seller)))
                .extracting(a -> a.getRuleCode()).contains("SELF_TRADE");
        assertThat(auditLogRepository.findByActorUsername(buyer)).anyMatch(a -> a.getAction().equals("TRADE_REQUEST") && a.getOutcome().equals("REJECTED"));
        assertThatThrownBy(() -> buy(buyer, "OF-does-not-exist", "1")).hasMessageContaining("Offer not found");
    }

    @Test
    @DisplayName("Meter validation is re-checked at trade time: an offer whose source reading is no longer VALID cannot be bought")
    void sourceReadingMustStillBeValid() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "5.00");
        jdbc.update("UPDATE meter_readings SET validation_status = 'FLAGGED' WHERE reading_id = ?", seller + "-R1");

        TradeResult result = buy(buyer, offerId, "1");
        assertThat(result.outcome()).isEqualTo("REJECTED");
        assertThat(codes(result.findings())).containsExactly("UNVERIFIED_SOURCE_READING");
        assertThat(result.findings().get(0).message()).contains("not backed by a validated meter reading");
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("Suspicious price: the trade is FLAGGED and held; admin approve resumes it, admin reject releases it")
    void flaggedTradeReview() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "20.00"); // above the 15.00 limit -> FLAGGED offer
        TradeResult flagged = buy(buyer, offerId, "1");
        assertThat(flagged.outcome()).isEqualTo("FLAGGED");
        assertThat(codes(flagged.findings())).containsExactly("SUSPICIOUS_PRICE");
        assertThat(balance(buyer)).isEqualByComparingTo("80.00");

        TradeView approved = tradingService.approve("HH-ADMIN", flagged.trade().tradeId());
        assertThat(approved.status()).as("blockchain disabled: approved trade waits as PENDING").isEqualTo("PENDING");
        assertThat(approved.history()).extracting(h -> h.toStatus() + "/" + h.actor()).containsExactly("FLAGGED/BUYER", "PENDING/ADMIN");
        assertThatThrownBy(() -> tradingService.reject("HH-ADMIN", flagged.trade().tradeId()))
                .isInstanceOf(BusinessRuleException.class);

        TradeResult second = buy(buyer, offerId, "1");
        TradeView rejected = tradingService.reject("HH-ADMIN", second.trade().tradeId());
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(balance(buyer)).isEqualByComparingTo("80.00");
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(4.0);
    }

    @Test
    @DisplayName("High trade frequency flags the request for review")
    void tradeFrequency() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "1.00");
        for (int i = 0; i < 10; i++) {
            assertThat(buy(buyer, offerId, "0.1").outcome()).isEqualTo("PENDING");
        }
        TradeResult eleventh = buy(buyer, offerId, "0.1");
        assertThat(eleventh.outcome()).isEqualTo("FLAGGED");
        assertThat(eleventh.findings().get(0).message()).contains("trade 11 within 60 minutes (limit 10)");
    }

    @Test
    @DisplayName("Idempotent purchase: a retry returns the same trade and reserves only once")
    void tradeIdempotency() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "5.00");
        TradeRequest request = new TradeRequest(offerId, new BigDecimal("2"));
        TradeResult first = tradingService.requestTrade(buyer, request, "trade-key-0001");
        TradeResult retry = tradingService.requestTrade(buyer, request, "trade-key-0001");

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.trade().tradeId()).isEqualTo(first.trade().tradeId());
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(3.0);
        assertThat(balance(buyer)).isEqualByComparingTo("90.00");
        assertThat(tradeRepository.findByBuyerId(fx.user(buyer).getId())).hasSize(1);

        assertThatThrownBy(() -> tradingService.requestTrade(buyer, new TradeRequest(offerId, new BigDecimal("1")), "trade-key-0001"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("different trade request");
        assertThatThrownBy(() -> tradingService.requestTrade(buyer, request, null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Idempotency-Key header is required");
    }

    @Test
    @DisplayName("Buyer cancellation releases energy and funds; others cannot cancel; terminal trades cannot be cancelled")
    void tradeCancellation() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "5.00");
        String tradeId = buy(buyer, offerId, "2").trade().tradeId();

        assertThatThrownBy(() -> tradingService.cancel(auth(seller, Role.ROLE_HOUSEHOLD), tradeId)).isInstanceOf(AccessDeniedException.class);
        TradeView cancelled = tradingService.cancel(auth(buyer, Role.ROLE_HOUSEHOLD), tradeId);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.history()).extracting(h -> h.toStatus()).containsExactly("PENDING", "CANCELLED");
        assertThat(balance(buyer)).isEqualByComparingTo("100.00");
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(5.0);
        assertThat(offerRow(offerId).getStatus()).isEqualTo(OfferStatus.ACTIVE);
        assertThatThrownBy(() -> tradingService.cancel(auth(buyer, Role.ROLE_HOUSEHOLD), tradeId))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("CANCELLED");
    }

    @Test
    @DisplayName("Stale reservations expire and are released, so energy is never reserved permanently")
    void reservationExpiry() {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "5.00");
        String tradeId = buy(buyer, offerId, "5").trade().tradeId();
        assertThat(offerRow(offerId).getStatus()).isEqualTo(OfferStatus.FILLED);

        jdbc.update("UPDATE trade_transactions SET updated_at = ? WHERE trade_id = ?", LocalDateTime.now().minusMinutes(16), tradeId);
        assertThat(tradingService.expireStaleReservations()).isGreaterThanOrEqualTo(1);

        TradeTransaction trade = tradeRepository.findByTradeId(tradeId).orElseThrow();
        assertThat(trade.getStatus()).isEqualTo(TradeStatus.EXPIRED);
        assertThat(trade.getRejectionReason()).contains("reservation timeout");
        assertThat(balance(buyer)).isEqualByComparingTo("100.00");
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(5.0);
        assertThat(offerRow(offerId).getStatus()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(eventRepository.findByTradeIdOrderByIdAsc(trade.getId())).extracting(e -> e.getToStatus())
                .containsExactly(TradeStatus.PENDING, TradeStatus.EXPIRED);
    }

    @Test
    @DisplayName("Concurrent purchases cannot oversell an offer (row lock on the offer)")
    void concurrentPurchasesDoNotOversell() throws Exception {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "2.00");
        List<String> buyers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buyers.add(fx.household("RACE", "50.00"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (String b : buyers) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return buy(b, offerId, "1").outcome();
                } catch (BusinessRuleException e) {
                    return "CONFLICT"; // offer already FILLED: 409 "Offer is not open"
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn("PENDING"::equals).hasSize(5);
        assertThat(outcomes).as("the other 3 are refused (REJECTED as oversell, or 409 once FILLED)")
                .filteredOn(o -> o.equals("REJECTED") || o.equals("CONFLICT")).hasSize(3);
        EnergyOffer offer = offerRow(offerId);
        assertThat(offer.getRemainingKwh()).isEqualTo(0.0);
        assertThat(offer.getStatus()).isEqualTo(OfferStatus.FILLED);
        BigDecimal escrowed = buyers.stream().map(this::balance).map(b -> new BigDecimal("50.00").subtract(b))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(escrowed).as("exactly 5 kWh x 2.00 escrowed").isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Concurrent retries with the same Idempotency-Key create exactly one trade")
    void concurrentDuplicateRequests() throws Exception {
        fx.validReading(seller, "8.0", "3.0");
        String offerId = fx.offer(seller, "5", "2.00");
        TradeRequest request = new TradeRequest(offerId, new BigDecimal("1"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TradeResult>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return tradingService.requestTrade(buyer, request, "same-key-0001");
            }));
        }
        start.countDown();
        List<String> tradeIds = new ArrayList<>();
        for (Future<TradeResult> f : futures) {
            tradeIds.add(f.get(60, TimeUnit.SECONDS).trade().tradeId());
        }
        pool.shutdown();
        assertThat(tradeIds).containsOnly(tradeIds.get(0));
        assertThat(offerRow(offerId).getRemainingKwh()).isEqualTo(4.0);
        assertThat(balance(buyer)).isEqualByComparingTo("98.00");
    }
}
