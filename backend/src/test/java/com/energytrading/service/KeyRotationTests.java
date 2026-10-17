package com.energytrading.service;

import com.energytrading.blockchain.BlockchainGateway.OnChainTrade;
import com.energytrading.blockchain.BlockchainGateway.TradeRecordPayload;
import com.energytrading.blockchain.TradeChainMapper;
import com.energytrading.config.PrivacyProperties;
import com.energytrading.model.TradeTransaction;
import com.energytrading.model.User;
import com.energytrading.model.enums.Role;
import com.energytrading.model.enums.TradeStatus;
import com.energytrading.privacy.DigestService;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.service.TradeSettlementService.Match;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase E: historical verification across a PRIVACY_PSEUDONYM_KEY rotation. */
class KeyRotationTests {

    private final DigestService digests = new DigestService();
    private final PseudonymService keyA = new PseudonymService(new PrivacyProperties("rotation-key-A-0123456789abcdefghijkl"));
    private final PseudonymService keyB = new PseudonymService(new PrivacyProperties("rotation-key-B-0123456789abcdefghijkl"));
    private final TradeChainMapper beforeRotation = new TradeChainMapper(digests, keyA);
    private final TradeChainMapper afterRotation = new TradeChainMapper(digests, keyB);

    private TradeTransaction trade(String tradeId, boolean storeRefs) {
        User seller = new User("KR-SELL", "s", null, null, "h", Role.ROLE_HOUSEHOLD, BigDecimal.ZERO, 0.0, 0.0);
        User buyer = new User("KR-BUY", "b", null, null, "h", Role.ROLE_HOUSEHOLD, BigDecimal.ZERO, 0.0, 0.0);
        TradeTransaction t = new TradeTransaction(tradeId, null, buyer, seller, 2.0, new BigDecimal("5.00"),
                new BigDecimal("10.00"), TradeStatus.PENDING);
        t.setRecordDigest(digests.tradeDigest(tradeId, "OF-1", "KR-SELL", "KR-BUY", new BigDecimal("2"),
                new BigDecimal("5.00"), new BigDecimal("10.00")));
        if (storeRefs) {
            String[] refs = beforeRotation.partyRefs(tradeId, "KR-SELL", "KR-BUY"); // stored at reservation with key A
            t.setSellerRef(refs[0]);
            t.setBuyerRef(refs[1]);
            t.setPrivacyKeyId(keyA.keyId());
        }
        return t;
    }

    private static OnChainTrade anchored(TradeRecordPayload p) {
        return new OnChainTrade(p.sellerRef(), p.buyerRef(), p.quantityWh(), p.pricePaisePerKwh(), p.totalPaise(),
                p.recordDigest(), 1, OnChainTrade.RECORDED);
    }

    @Test
    @DisplayName("Stored references keep a trade verifiable after the key is rotated")
    void storedReferencesSurviveRotation() {
        TradeTransaction t = trade("TR-KR-1", true);
        OnChainTrade onChain = anchored(beforeRotation.payload(t, "KR-SELL", "KR-BUY"));

        TradeRecordPayload afterwards = afterRotation.payload(t, "KR-SELL", "KR-BUY");
        assertThat(TradeSettlementService.compare(onChain, afterwards, true)).isEqualTo(Match.MATCH);
    }

    @Test
    @DisplayName("An in-flight trade submitted after the rotation still uses the references from reservation time")
    void inFlightTradeUsesStoredReferences() {
        TradeTransaction t = trade("TR-KR-2", true);
        TradeRecordPayload before = beforeRotation.payload(t, "KR-SELL", "KR-BUY");
        TradeRecordPayload after = afterRotation.payload(t, "KR-SELL", "KR-BUY");
        assertThat(after.sellerRef()).isEqualTo(before.sellerRef());
        assertThat(after.buyerRef()).isEqualTo(before.buyerRef());
        assertThat(afterRotation.partyRefs("TR-KR-2", "KR-SELL", "KR-BUY")[0])
                .as("a NEW trade would use the active key").isNotEqualTo(t.getSellerRef());
    }

    @Test
    @DisplayName("A legacy record without stored references is UNVERIFIABLE after rotation, not a false MISMATCH")
    void legacyRecordIsUnverifiable() {
        TradeTransaction legacy = trade("TR-KR-3", false);
        OnChainTrade onChain = anchored(beforeRotation.payload(legacy, "KR-SELL", "KR-BUY"));
        assertThat(TradeSettlementService.compare(onChain, afterRotation.payload(legacy, "KR-SELL", "KR-BUY"), false))
                .isEqualTo(Match.UNVERIFIABLE);
        assertThat(TradeSettlementService.compare(onChain, beforeRotation.payload(legacy, "KR-SELL", "KR-BUY"), false))
                .as("still MATCH while the original key is active").isEqualTo(Match.MATCH);
    }

    @Test
    @DisplayName("A real integrity mismatch stays MISMATCH, with or without stored references")
    void realMismatchIsDistinguishable() {
        for (boolean stored : new boolean[]{true, false}) {
            TradeTransaction t = trade("TR-KR-4" + stored, stored);
            TradeRecordPayload p = beforeRotation.payload(t, "KR-SELL", "KR-BUY");
            OnChainTrade tampered = new OnChainTrade(p.sellerRef(), p.buyerRef(), p.quantityWh() + 1, p.pricePaisePerKwh(),
                    p.totalPaise(), p.recordDigest(), 1, OnChainTrade.RECORDED);
            assertThat(TradeSettlementService.compare(tampered, afterRotation.payload(t, "KR-SELL", "KR-BUY"), stored))
                    .isEqualTo(Match.MISMATCH);
        }
        TradeTransaction stored = trade("TR-KR-5", true);
        TradeRecordPayload p = beforeRotation.payload(stored, "KR-SELL", "KR-BUY");
        OnChainTrade otherRefs = new OnChainTrade(new byte[32], p.buyerRef(), p.quantityWh(), p.pricePaisePerKwh(),
                p.totalPaise(), p.recordDigest(), 1, OnChainTrade.RECORDED);
        assertThat(TradeSettlementService.compare(otherRefs, p, true)).as("stored references that differ are a real mismatch")
                .isEqualTo(Match.MISMATCH);
    }

    @Test
    @DisplayName("The key ID identifies the key without revealing it")
    void keyId() {
        assertThat(keyA.keyId()).isEqualTo(keyA.keyId()).isNotEqualTo(keyB.keyId()).matches("k-[0-9a-f]{16}")
                .doesNotContain("rotation-key");
    }
}
