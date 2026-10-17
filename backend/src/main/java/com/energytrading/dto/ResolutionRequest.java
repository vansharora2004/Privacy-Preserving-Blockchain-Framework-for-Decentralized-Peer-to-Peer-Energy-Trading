package com.energytrading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Administrator resolution of a trade that needs review.
 *
 * @param reason                  meaningful justification recorded in the trade history and audit log
 * @param priorDeploymentRetired  operator attestation that earlier attempts on another deployment can no
 *                                longer be mined there (e.g. the chain was reset); required when such attempts exist
 */
public record ResolutionRequest(@NotBlank @Size(min = 10, max = 200) String reason, boolean priorDeploymentRetired) {
}
