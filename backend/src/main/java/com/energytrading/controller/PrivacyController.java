package com.energytrading.controller;

import com.energytrading.dto.PageResponse;
import com.energytrading.dto.PrivateHouseholdView;
import com.energytrading.dto.PrivateMeterReadingView;
import com.energytrading.dto.PublicHouseholdView;
import com.energytrading.dto.MarketSummaryView;
import com.energytrading.dto.PublicTradeView;
import com.energytrading.privacy.PrivacyService;
import com.energytrading.service.TradingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public pseudonymous views and private, ownership-checked household views. */
@RestController
@RequestMapping("/api")
public class PrivacyController {

    private final PrivacyService privacyService;
    private final TradingService tradingService;

    public PrivacyController(PrivacyService privacyService, TradingService tradingService) {
        this.privacyService = privacyService;
        this.tradingService = tradingService;
    }

    /** Public: aggregate market figures only (withheld when too few sellers to stay non-identifying). */
    @GetMapping("/privacy/public/market-summary")
    public MarketSummaryView marketSummary() {
        return tradingService.marketSummary();
    }

    /** Public: confirmed trades with amounts and blockchain references, without household identifiers. */
    @GetMapping("/privacy/public/trades")
    public PageResponse<PublicTradeView> publicTrades(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return tradingService.publicConfirmedTrades(page, size);
    }

    /** Public: household pseudonyms only (no per-household energy figures). */
    @GetMapping("/privacy/public/households")
    public List<PublicHouseholdView> publicHouseholds() {
        return privacyService.publicHouseholds();
    }

    /** Private profile: the household itself or an administrator. */
    @GetMapping("/privacy/households/{householdCode}")
    @PreAuthorize("hasAnyRole('HOUSEHOLD', 'ADMIN')")
    public PrivateHouseholdView privateHousehold(@PathVariable String householdCode, Authentication authentication) {
        return privacyService.privateHousehold(authentication, householdCode);
    }

    /** The authenticated account's own private profile. */
    @GetMapping("/me")
    public PrivateHouseholdView me(Authentication authentication) {
        return privacyService.ownProfile(authentication);
    }

    /** Administrators only: all accounts (without credentials). */
    @GetMapping("/households")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PrivateHouseholdView> households() {
        return privacyService.allHouseholds();
    }

    /** Detailed readings: the household itself or an administrator. */
    @GetMapping("/households/{householdCode}/meter-readings")
    @PreAuthorize("hasAnyRole('HOUSEHOLD', 'ADMIN')")
    public PageResponse<PrivateMeterReadingView> readings(@PathVariable String householdCode,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          Authentication authentication) {
        return privacyService.householdReadings(authentication, householdCode, page, size);
    }
}
