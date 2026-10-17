package com.energytrading.controller;

import com.energytrading.dto.CreateOfferRequest;
import com.energytrading.dto.OfferResult;
import com.energytrading.dto.OfferView;
import com.energytrading.dto.OwnOfferView;
import com.energytrading.dto.PageResponse;
import com.energytrading.service.MarketplaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/offers")
public class OfferController {

    private final MarketplaceService marketplaceService;

    public OfferController(MarketplaceService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    /** 201 created (PASSED or FLAGGED), 200 idempotent replay, 422 rejected. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('HOUSEHOLD')")
    public ResponseEntity<OfferResult> create(@Valid @RequestBody CreateOfferRequest request,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                              Authentication authentication) {
        OfferResult result = marketplaceService.createOffer(authentication.getName(), request, idempotencyKey);
        HttpStatus status = result.offer() == null ? HttpStatus.UNPROCESSABLE_ENTITY
                : result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    /** Open offers for any authenticated account; sellers appear only as pseudonyms. */
    @GetMapping
    public PageResponse<OfferView> open(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return marketplaceService.openOffers(page, size);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('HOUSEHOLD')")
    public List<OwnOfferView> mine(Authentication authentication) {
        return marketplaceService.offersOf(authentication.getName());
    }

    /** Energy the caller can still offer from its latest validated reading. */
    @GetMapping("/availability")
    @PreAuthorize("hasRole('HOUSEHOLD')")
    public Map<String, BigDecimal> availability(Authentication authentication) {
        return Map.of("availableKwh", marketplaceService.availableForNewOffers(authentication.getName()));
    }

    @PostMapping("/{offerId}/cancel")
    @PreAuthorize("hasAnyRole('HOUSEHOLD', 'ADMIN')")
    public OwnOfferView cancel(@PathVariable String offerId, Authentication authentication) {
        return marketplaceService.cancelOffer(authentication, offerId);
    }
}
