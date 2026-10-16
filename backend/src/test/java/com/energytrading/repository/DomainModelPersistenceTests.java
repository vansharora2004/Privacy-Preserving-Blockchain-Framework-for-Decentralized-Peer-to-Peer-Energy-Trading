package com.energytrading.repository;

import com.energytrading.model.AnomalyLog;
import com.energytrading.model.AuditLog;
import com.energytrading.model.EnergyOffer;
import com.energytrading.model.MeterReading;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.User;
import com.energytrading.model.enums.AnomalyReviewStatus;
import com.energytrading.model.enums.AnomalySeverity;
import com.energytrading.model.enums.OfferStatus;
import com.energytrading.model.enums.ReadingValidationStatus;
import com.energytrading.model.enums.Role;
import com.energytrading.model.enums.TradeStatus;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Batch 1 persistence tests: entity mappings, relationships, database constraints,
 * bean-validation rules and the derived queries of every repository.
 */
@DataJpaTest
class DomainModelPersistenceTests {

    @Autowired private TestEntityManager em;
    @Autowired private UserRepository userRepository;
    @Autowired private MeterReadingRepository meterReadingRepository;
    @Autowired private EnergyOfferRepository energyOfferRepository;
    @Autowired private TradeTransactionRepository tradeTransactionRepository;
    @Autowired private AnomalyLogRepository anomalyLogRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private User seller;
    private User buyer;

    @BeforeEach
    void setUp() {
        seller = userRepository.saveAndFlush(user("T-SELLER", "seller@test.energy"));
        buyer = userRepository.saveAndFlush(user("T-BUYER", "buyer@test.energy"));
    }

    @Test
    @DisplayName("User: timestamps populated, lookups by household code and email work")
    void userPersistenceAndLookup() {
        assertThat(seller.getId()).isNotNull();
        assertThat(seller.getCreatedAt()).isNotNull();
        assertThat(seller.getUpdatedAt()).isNotNull();
        assertThat(userRepository.findByHouseholdCode("T-SELLER")).contains(seller);
        assertThat(userRepository.findByEmail("buyer@test.energy")).contains(buyer);
        assertThat(userRepository.existsByHouseholdCode("T-BUYER")).isTrue();
        assertThat(userRepository.existsByEmail("nobody@test.energy")).isFalse();
    }

    @Test
    @DisplayName("User: household code must be unique")
    void duplicateHouseholdCodeRejected() {
        assertThatThrownBy(() -> userRepository.saveAndFlush(user("T-SELLER", "other@test.energy")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("User: invalid email and negative balance fail bean validation")
    void userValidation() {
        User badEmail = user("T-BAD-EMAIL", "not-an-email");
        assertThatThrownBy(() -> userRepository.saveAndFlush(badEmail))
                .isInstanceOf(ConstraintViolationException.class);

        User negativeBalance = user("T-NEG", "neg@test.energy");
        negativeBalance.setBalance(BigDecimal.valueOf(-1));
        assertThatThrownBy(() -> userRepository.saveAndFlush(negativeBalance))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("MeterReading: relationship, enum and latest-sequence query")
    void meterReadingPersistence() {
        meterReadingRepository.saveAndFlush(reading(seller, "R-1", 1L));
        meterReadingRepository.saveAndFlush(reading(seller, "R-2", 2L));
        em.clear();

        MeterReading latest = meterReadingRepository.findTopByHouseholdIdOrderBySequenceNoDesc(seller.getId()).orElseThrow();
        assertThat(latest.getReadingId()).isEqualTo("R-2");
        assertThat(latest.getHousehold().getHouseholdCode()).isEqualTo("T-SELLER");
        assertThat(latest.getValidationStatus()).isEqualTo(ReadingValidationStatus.VALID);
        assertThat(latest.getCreatedAt()).isNotNull();
        assertThat(meterReadingRepository.findByHouseholdIdOrderBySequenceNoDesc(seller.getId()))
                .extracting(MeterReading::getSequenceNo).containsExactly(2L, 1L);
        assertThat(meterReadingRepository.existsByReadingId("R-1")).isTrue();
    }

    @Test
    @DisplayName("MeterReading: reading ID must be unique (replay protection)")
    void duplicateReadingIdRejected() {
        meterReadingRepository.saveAndFlush(reading(seller, "R-DUP", 1L));
        assertThatThrownBy(() -> meterReadingRepository.saveAndFlush(reading(seller, "R-DUP", 2L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("MeterReading: a household cannot reuse a sequence number, other households can")
    void duplicateSequencePerHouseholdRejected() {
        meterReadingRepository.saveAndFlush(reading(seller, "R-A", 7L));
        meterReadingRepository.saveAndFlush(reading(buyer, "R-B", 7L));
        assertThatThrownBy(() -> meterReadingRepository.saveAndFlush(reading(seller, "R-C", 7L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("MeterReading: negative energy values fail bean validation")
    void negativeEnergyRejected() {
        MeterReading bad = reading(seller, "R-NEG", 1L);
        bad.setEnergyGeneratedKwh(-0.5);
        assertThatThrownBy(() -> meterReadingRepository.saveAndFlush(bad))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("EnergyOffer: decimal price, seller/source relationships and queries")
    void energyOfferPersistence() {
        MeterReading source = meterReadingRepository.saveAndFlush(reading(seller, "R-SRC", 1L));
        energyOfferRepository.saveAndFlush(offer("OF-1", source, new BigDecimal("6.25")));
        em.clear();

        EnergyOffer loaded = energyOfferRepository.findByOfferId("OF-1").orElseThrow();
        assertThat(loaded.getPricePerKwh()).isEqualByComparingTo("6.25");
        assertThat(loaded.getSeller().getId()).isEqualTo(seller.getId());
        assertThat(loaded.getSourceReading().getReadingId()).isEqualTo("R-SRC");
        assertThat(loaded.getStatus()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(energyOfferRepository.findByStatus(OfferStatus.ACTIVE)).hasSize(1);
        assertThat(energyOfferRepository.findBySellerId(seller.getId())).hasSize(1);
    }

    @Test
    @DisplayName("EnergyOffer: offer ID unique and price must be positive")
    void energyOfferConstraints() {
        energyOfferRepository.saveAndFlush(offer("OF-DUP", null, BigDecimal.ONE));
        assertThatThrownBy(() -> energyOfferRepository.saveAndFlush(offer("OF-DUP", null, BigDecimal.ONE)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> energyOfferRepository.saveAndFlush(offer("OF-ZERO", null, BigDecimal.ZERO)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("TradeTransaction: buyer/seller/offer links, blockchain fields and queries")
    void tradeTransactionPersistence() {
        EnergyOffer offer = energyOfferRepository.saveAndFlush(offer("OF-T", null, new BigDecimal("5.00")));
        TradeTransaction trade = new TradeTransaction("TR-1", offer, buyer, seller, 2.0,
                new BigDecimal("5.00"), new BigDecimal("10.00"), TradeStatus.PENDING);
        trade.setTxHash("0x" + "ab".repeat(32));
        trade.setBlockNumber(42L);
        tradeTransactionRepository.saveAndFlush(trade);
        em.clear();

        TradeTransaction loaded = tradeTransactionRepository.findByTradeId("TR-1").orElseThrow();
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo("10.00");
        assertThat(loaded.getBuyer().getHouseholdCode()).isEqualTo("T-BUYER");
        assertThat(loaded.getSeller().getHouseholdCode()).isEqualTo("T-SELLER");
        assertThat(loaded.getOffer().getOfferId()).isEqualTo("OF-T");
        assertThat(tradeTransactionRepository.findByTxHash("0x" + "ab".repeat(32))).isPresent();
        assertThat(tradeTransactionRepository.findByBuyerId(buyer.getId())).hasSize(1);
        assertThat(tradeTransactionRepository.findBySellerId(seller.getId())).hasSize(1);
        assertThat(tradeTransactionRepository.findByStatus(TradeStatus.PENDING)).hasSize(1);
        assertThat(tradeTransactionRepository.existsByTradeId("TR-1")).isTrue();
    }

    @Test
    @DisplayName("TradeTransaction: trade ID must be unique (replay protection)")
    void duplicateTradeIdRejected() {
        tradeTransactionRepository.saveAndFlush(trade("TR-DUP"));
        assertThatThrownBy(() -> tradeTransactionRepository.saveAndFlush(trade("TR-DUP")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("AnomalyLog and AuditLog: defaults, timestamps and queries")
    void logsPersistence() {
        AnomalyLog anomaly = anomalyLogRepository.saveAndFlush(new AnomalyLog("READING", "R-9", "PRICE_LIMIT",
                null, "Price above configured maximum", AnomalyReviewStatus.DETECTED));
        assertThat(anomaly.getSeverity()).isEqualTo(AnomalySeverity.MEDIUM);
        assertThat(anomaly.getCreatedAt()).isNotNull();
        assertThat(anomalyLogRepository.findByRuleCode("PRICE_LIMIT")).hasSize(1);
        assertThat(anomalyLogRepository.findByTargetReferenceId("R-9")).hasSize(1);
        assertThat(anomalyLogRepository.findByReviewStatus(AnomalyReviewStatus.DETECTED)).hasSize(1);

        AuditLog audit = auditLogRepository.saveAndFlush(new AuditLog("tester", "LOGIN", "SUCCESS", null, "127.0.0.1"));
        assertThat(audit.getTimestamp()).isNotNull();
        assertThat(auditLogRepository.findByActorUsername("tester")).hasSize(1);
        assertThat(auditLogRepository.findAllByOrderByTimestampDesc()).isNotEmpty();
    }

    @Test
    @DisplayName("AuditLog: action and outcome are required")
    void auditLogValidation() {
        assertThatThrownBy(() -> auditLogRepository.saveAndFlush(new AuditLog("tester", " ", "SUCCESS", null, null)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    private static User user(String code, String email) {
        return new User(code, code + " alias", null, email, "$2a$10$hash", Role.ROLE_HOUSEHOLD,
                new BigDecimal("100.00"), 0.0, 0.0);
    }

    private static MeterReading reading(User household, String readingId, long sequenceNo) {
        return new MeterReading(household, readingId, sequenceNo, 4.0, 1.0, 3.0,
                LocalDateTime.now().minusMinutes(5), ReadingValidationStatus.VALID, null);
    }

    private EnergyOffer offer(String offerId, MeterReading source, BigDecimal price) {
        return new EnergyOffer(seller, source, offerId, 3.0, 3.0, price, OfferStatus.ACTIVE);
    }

    private TradeTransaction trade(String tradeId) {
        return new TradeTransaction(tradeId, null, buyer, seller, 1.0,
                new BigDecimal("5.00"), new BigDecimal("5.00"), TradeStatus.PENDING);
    }
}
