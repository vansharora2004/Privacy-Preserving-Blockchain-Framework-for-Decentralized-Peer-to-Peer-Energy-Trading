package com.energytrading.dto;

/** Result of verifying the configured network, contract and deployment. Never contains the private key. */
public record BlockchainStatusView(boolean enabled, Long expectedChainId, Long actualChainId, String contractAddress,
                                   Boolean contractDeployed, String contractVersion, String signerAddress,
                                   Boolean signerAuthorized, Long latestBlock, String expectedDeploymentId,
                                   String deploymentId, String genesisHash, boolean ok, String problem) {
}
