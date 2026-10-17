package com.energytrading.blockchain;

import com.energytrading.model.TradeTransaction;
import com.energytrading.privacy.DigestService;
import com.energytrading.privacy.PseudonymService;
import com.energytrading.service.EnergyValues;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HexFormat;

/**
 * Converts a trade into the contract's minimal, privacy-preserving payload and integer units.
 * <ul>
 *   <li>{@code tradeKey} = SHA-256("energy-trade|" + tradeId); the trade ID stays off-chain.</li>
 *   <li>{@code sellerRef}/{@code buyerRef} = per-trade HMAC references (unlinkable across trades without the key).</li>
 *   <li>quantity kWh x 1000 = Wh; price rupees x 100 = paise per kWh; total rupees x 100 = paise.</li>
 *   <li>{@code recordDigest} = SHA-256 of the canonical trade record (integrity anchor).</li>
 * </ul>
 * Names, household codes, e-mail addresses and meter readings are never part of the payload.
 */
@Component
public class TradeChainMapper {

    private final DigestService digestService;
    private final PseudonymService pseudonymService;

    public TradeChainMapper(DigestService digestService, PseudonymService pseudonymService) {
        this.digestService = digestService;
        this.pseudonymService = pseudonymService;
    }

    public byte[] tradeKey(String tradeId) {
        return digestService.tradeKey(tradeId);
    }

    /** Per-trade party references computed with the active key, as stored at reservation. */
    public String[] partyRefs(String tradeId, String sellerCode, String buyerCode) {
        return new String[]{"0x" + HexFormat.of().formatHex(pseudonymService.tradePartyReference(tradeId, "seller", sellerCode)),
                "0x" + HexFormat.of().formatHex(pseudonymService.tradePartyReference(tradeId, "buyer", buyerCode))};
    }

    /** True when the trade carries the references stored at reservation (schema v3 and later). */
    public boolean refsStored(TradeTransaction trade) {
        return trade.getSellerRef() != null && trade.getBuyerRef() != null;
    }

    /**
     * Payload for the contract. Stored references are used when present, so a later rotation of the
     * pseudonym key does not change what is submitted or verified; legacy trades without stored
     * references fall back to the active key (and verify as UNVERIFIABLE if the key has changed).
     */
    public BlockchainGateway.TradeRecordPayload payload(TradeTransaction trade, String sellerCode, String buyerCode) {
        BigDecimal quantity = EnergyValues.toBigDecimal(trade.getQuantityKwh());
        return new BlockchainGateway.TradeRecordPayload(
                tradeKey(trade.getTradeId()),
                refsStored(trade) ? parse(trade.getSellerRef()) : pseudonymService.tradePartyReference(trade.getTradeId(), "seller", sellerCode),
                refsStored(trade) ? parse(trade.getBuyerRef()) : pseudonymService.tradePartyReference(trade.getTradeId(), "buyer", buyerCode),
                quantity.movePointRight(3).stripTrailingZeros().longValueExact(),
                trade.getPricePerKwh().movePointRight(2).stripTrailingZeros().longValueExact(),
                trade.getTotalAmount().movePointRight(2).stripTrailingZeros().toBigIntegerExact(),
                HexFormat.of().parseHex(trade.getRecordDigest()));
    }

    private static byte[] parse(String hex) {
        return HexFormat.of().parseHex(hex.startsWith("0x") ? hex.substring(2) : hex);
    }
}
