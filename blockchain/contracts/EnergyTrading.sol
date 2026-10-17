// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

/// @title EnergyTrading
/// @notice Immutable registry of accepted peer-to-peer energy trades for the local demo network.
/// @dev Privacy: the contract stores no names, household codes, e-mail addresses, credentials or
///      meter readings. Each trade is identified by `tradeKey = sha256("energy-trade|" + tradeId)`
///      and its parties by per-trade keyed references (HMAC-SHA-256 computed off-chain with a
///      backend secret), so references cannot be linked across trades without that secret.
///      `recordDigest` is the SHA-256 of the canonical off-chain trade record, letting the backend
///      prove its database record is unchanged. A digest is for integrity, not anonymity.
///
///      Units (integers only, no floating point):
///        quantityWh        energy in watt-hours           (1 kWh = 1000 Wh)
///        pricePaisePerKwh  price in paise per kWh         (1 rupee = 100 paise)
///        totalPaise        total in paise = round_half_up(quantityWh * pricePaisePerKwh / 1000)
///
///      Lifecycle: None -> Recorded -> Settled, or Recorded -> Voided. Only authorized recorders
///      (managed by the owner) can change state. The backend treats Recorded and Settled as a
///      valid anchor and Voided as an invalidated one (never as a successful trade). The chain
///      proves that a trade was recorded; it does not prove that electricity was physically delivered.
///
///      Deployment identity: `deploymentId` is fixed at construction from a caller-supplied random
///      salt, the chain ID, the contract address, the previous block hash and the block timestamp.
///      A fresh local chain that redeploys at the same address therefore gets a different identity,
///      which lets the backend detect chain resets and redeployments.
contract EnergyTrading {
    string public constant VERSION = "EnergyTrading/2";
    uint64 public constant MAX_QUANTITY_WH = 1_000_000_000; // 1 GWh per trade (sanity bound)
    uint64 public constant MAX_PRICE_PAISE_PER_KWH = 100_000_000; // Rs 1,000,000 per kWh (sanity bound)

    enum Status {
        None,
        Recorded,
        Settled,
        Voided
    }

    struct Trade {
        bytes32 sellerRef;
        bytes32 buyerRef;
        uint64 quantityWh;
        uint64 pricePaisePerKwh;
        uint128 totalPaise;
        bytes32 recordDigest;
        uint64 recordedAt;
        Status status;
    }

    /// Unique identity of this deployment (see contract documentation).
    bytes32 public immutable deploymentId;
    address public owner;
    mapping(address => bool) public isRecorder;
    uint256 public tradeCount;
    mapping(bytes32 => Trade) private trades;

    event Deployed(bytes32 indexed deploymentId, uint256 chainId);
    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);
    event RecorderUpdated(address indexed account, bool authorized);
    event TradeRecorded(
        bytes32 indexed tradeKey,
        bytes32 sellerRef,
        bytes32 buyerRef,
        uint64 quantityWh,
        uint64 pricePaisePerKwh,
        uint128 totalPaise,
        bytes32 recordDigest,
        address indexed recorder
    );
    event TradeSettled(bytes32 indexed tradeKey, address indexed recorder);
    event TradeVoided(bytes32 indexed tradeKey, uint8 reasonCode, address indexed recorder);

    error NotOwner();
    error NotRecorder();
    error ZeroAddress();
    error InvalidTradeKey();
    error InvalidParty();
    error SameParty();
    error InvalidQuantity();
    error InvalidPrice();
    error TotalMismatch(uint256 expected, uint256 actual);
    error InvalidDigest();
    error InvalidReason();
    error TradeAlreadyRecorded(bytes32 tradeKey);
    error TradeNotFound(bytes32 tradeKey);
    error InvalidTransition(bytes32 tradeKey, Status current);
    error InvalidSalt();

    modifier onlyOwner() {
        if (msg.sender != owner) revert NotOwner();
        _;
    }

    modifier onlyRecorder() {
        if (!isRecorder[msg.sender]) revert NotRecorder();
        _;
    }

    /// @param initialRecorder account allowed to record trades (the backend signer); may be zero
    /// @param deploymentSalt  random, non-zero value chosen by the deployer (see scripts/deploy.js)
    constructor(address initialRecorder, bytes32 deploymentSalt) {
        if (deploymentSalt == bytes32(0)) revert InvalidSalt();
        deploymentId = keccak256(abi.encode(block.chainid, address(this), deploymentSalt,
            blockhash(block.number - 1), block.timestamp));
        emit Deployed(deploymentId, block.chainid);
        owner = msg.sender;
        emit OwnershipTransferred(address(0), msg.sender);
        if (initialRecorder != address(0)) {
            isRecorder[initialRecorder] = true;
            emit RecorderUpdated(initialRecorder, true);
        }
    }

    function transferOwnership(address newOwner) external onlyOwner {
        if (newOwner == address(0)) revert ZeroAddress();
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }

    function setRecorder(address account, bool authorized) external onlyOwner {
        if (account == address(0)) revert ZeroAddress();
        isRecorder[account] = authorized;
        emit RecorderUpdated(account, authorized);
    }

    /// @notice Records an accepted trade. Reverts if the trade key was ever used before.
    function recordTrade(
        bytes32 tradeKey,
        bytes32 sellerRef,
        bytes32 buyerRef,
        uint64 quantityWh,
        uint64 pricePaisePerKwh,
        uint128 totalPaise,
        bytes32 recordDigest
    ) external onlyRecorder {
        if (tradeKey == bytes32(0)) revert InvalidTradeKey();
        if (trades[tradeKey].status != Status.None) revert TradeAlreadyRecorded(tradeKey);
        if (sellerRef == bytes32(0) || buyerRef == bytes32(0)) revert InvalidParty();
        if (sellerRef == buyerRef) revert SameParty();
        if (quantityWh == 0 || quantityWh > MAX_QUANTITY_WH) revert InvalidQuantity();
        if (pricePaisePerKwh == 0 || pricePaisePerKwh > MAX_PRICE_PAISE_PER_KWH) revert InvalidPrice();
        uint256 expectedTotal = (uint256(quantityWh) * uint256(pricePaisePerKwh) + 500) / 1000;
        if (uint256(totalPaise) != expectedTotal) revert TotalMismatch(expectedTotal, totalPaise);
        if (recordDigest == bytes32(0)) revert InvalidDigest();

        trades[tradeKey] = Trade({
            sellerRef: sellerRef,
            buyerRef: buyerRef,
            quantityWh: quantityWh,
            pricePaisePerKwh: pricePaisePerKwh,
            totalPaise: totalPaise,
            recordDigest: recordDigest,
            recordedAt: uint64(block.timestamp),
            status: Status.Recorded
        });
        tradeCount += 1;
        emit TradeRecorded(tradeKey, sellerRef, buyerRef, quantityWh, pricePaisePerKwh, totalPaise, recordDigest, msg.sender);
    }

    /// @notice Marks a recorded trade as settled (e.g. delivery confirmed off-chain).
    function settleTrade(bytes32 tradeKey) external onlyRecorder {
        Status current = trades[tradeKey].status;
        if (current == Status.None) revert TradeNotFound(tradeKey);
        if (current != Status.Recorded) revert InvalidTransition(tradeKey, current);
        trades[tradeKey].status = Status.Settled;
        emit TradeSettled(tradeKey, msg.sender);
    }

    /// @notice Voids a recorded, unsettled trade. The trade key stays used and cannot be replayed.
    function voidTrade(bytes32 tradeKey, uint8 reasonCode) external onlyRecorder {
        if (reasonCode == 0) revert InvalidReason();
        Status current = trades[tradeKey].status;
        if (current == Status.None) revert TradeNotFound(tradeKey);
        if (current != Status.Recorded) revert InvalidTransition(tradeKey, current);
        trades[tradeKey].status = Status.Voided;
        emit TradeVoided(tradeKey, reasonCode, msg.sender);
    }

    function isRecorded(bytes32 tradeKey) external view returns (bool) {
        return trades[tradeKey].status != Status.None;
    }

    /// @dev Returned as a flat tuple so that off-chain clients can decode it without struct support.
    function getTrade(bytes32 tradeKey)
        external
        view
        returns (
            bytes32 sellerRef,
            bytes32 buyerRef,
            uint64 quantityWh,
            uint64 pricePaisePerKwh,
            uint128 totalPaise,
            bytes32 recordDigest,
            uint64 recordedAt,
            uint8 status
        )
    {
        Trade storage t = trades[tradeKey];
        return (t.sellerRef, t.buyerRef, t.quantityWh, t.pricePaisePerKwh, t.totalPaise, t.recordDigest, t.recordedAt, uint8(t.status));
    }
}
