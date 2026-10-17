const { expect } = require("chai");
const { ethers, network } = require("hardhat");
const { loadFixture } = require("@nomicfoundation/hardhat-toolbox/network-helpers");

const Status = { None: 0n, Recorded: 1n, Settled: 2n, Voided: 3n };

// Off-chain conventions mirrored from the Java backend
const tradeKey = (tradeId) => ethers.sha256(ethers.toUtf8Bytes(`energy-trade|${tradeId}`));
const ref = (label) => ethers.keccak256(ethers.toUtf8Bytes(label)); // stands in for the backend HMAC
const totalFor = (quantityWh, pricePaisePerKwh) => (quantityWh * pricePaisePerKwh + 500n) / 1000n;

function trade(overrides = {}) {
  const quantityWh = overrides.quantityWh ?? 2500n; // 2.5 kWh
  const pricePaisePerKwh = overrides.pricePaisePerKwh ?? 650n; // Rs 6.50 per kWh
  return {
    tradeKey: overrides.tradeKey ?? tradeKey("TR-0001"),
    sellerRef: overrides.sellerRef ?? ref("seller"),
    buyerRef: overrides.buyerRef ?? ref("buyer"),
    quantityWh,
    pricePaisePerKwh,
    totalPaise: overrides.totalPaise ?? totalFor(quantityWh, pricePaisePerKwh),
    recordDigest: overrides.recordDigest ?? ethers.sha256(ethers.toUtf8Bytes("canonical trade record")),
  };
}

const args = (t) => [t.tradeKey, t.sellerRef, t.buyerRef, t.quantityWh, t.pricePaisePerKwh, t.totalPaise, t.recordDigest];

describe("EnergyTrading", function () {
  const salt = (label = "test") => ethers.keccak256(ethers.toUtf8Bytes(`salt|${label}`));

  async function deployFixture() {
    const [owner, recorder, outsider, newRecorder] = await ethers.getSigners();
    const contract = await ethers.deployContract("EnergyTrading", [recorder.address, salt()]);
    await contract.waitForDeployment();
    return { contract, owner, recorder, outsider, newRecorder };
  }

  describe("deployment and administration", function () {
    it("sets the owner, the initial recorder and the version", async function () {
      const { contract, owner, recorder, outsider } = await loadFixture(deployFixture);
      expect(await contract.owner()).to.equal(owner.address);
      expect(await contract.isRecorder(recorder.address)).to.equal(true);
      expect(await contract.isRecorder(outsider.address)).to.equal(false);
      expect(await contract.isRecorder(owner.address)).to.equal(false);
      expect(await contract.VERSION()).to.equal("EnergyTrading/2");
      expect(await contract.tradeCount()).to.equal(0n);
    });

    it("can be deployed without an initial recorder", async function () {
      const contract = await ethers.deployContract("EnergyTrading", [ethers.ZeroAddress, salt("no-recorder")]);
      const [owner] = await ethers.getSigners();
      expect(await contract.isRecorder(owner.address)).to.equal(false);
    });

    it("has a non-zero deployment identity, emitted at deployment", async function () {
      const { contract } = await loadFixture(deployFixture);
      const id = await contract.deploymentId();
      expect(id).to.not.equal(ethers.ZeroHash);
      const receipt = await contract.deploymentTransaction().wait();
      const event = receipt.logs.map((l) => contract.interface.parseLog(l)).find((e) => e && e.name === "Deployed");
      expect(event.args.deploymentId).to.equal(id);
      expect(event.args.chainId).to.equal(31337n);
    });

    it("gives every deployment a different identity, even with the same salt", async function () {
      const [, recorder] = await ethers.getSigners();
      const a = await ethers.deployContract("EnergyTrading", [recorder.address, salt("same")]);
      const b = await ethers.deployContract("EnergyTrading", [recorder.address, salt("same")]);
      const c = await ethers.deployContract("EnergyTrading", [recorder.address, salt("other")]);
      const ids = [await a.deploymentId(), await b.deploymentId(), await c.deploymentId()];
      expect(new Set(ids).size).to.equal(3);
    });

    it("identifies a redeployment at the same address on a reset chain as a different deployment", async function () {
      const [, recorder] = await ethers.getSigners();
      const snapshot = await network.provider.send("evm_snapshot");
      const first = await ethers.deployContract("EnergyTrading", [recorder.address, salt("reset")]);
      const firstAddress = await first.getAddress();
      const firstId = await first.deploymentId();
      await network.provider.send("evm_revert", [snapshot]); // simulate a chain reset
      await network.provider.send("evm_mine");                // the "new" chain moves on
      const second = await ethers.deployContract("EnergyTrading", [recorder.address, salt("reset-2")]);
      expect(await second.getAddress()).to.equal(firstAddress);
      expect(await second.deploymentId()).to.not.equal(firstId);
    });

    it("rejects a zero deployment salt", async function () {
      const [, recorder] = await ethers.getSigners();
      const factory = await ethers.getContractFactory("EnergyTrading");
      await expect(factory.deploy(recorder.address, ethers.ZeroHash)).to.be.revertedWithCustomError(factory, "InvalidSalt");
    });

    it("lets only the owner manage recorders, with events", async function () {
      const { contract, outsider, newRecorder } = await loadFixture(deployFixture);
      await expect(contract.setRecorder(newRecorder.address, true))
        .to.emit(contract, "RecorderUpdated").withArgs(newRecorder.address, true);
      expect(await contract.isRecorder(newRecorder.address)).to.equal(true);
      await expect(contract.connect(outsider).setRecorder(outsider.address, true))
        .to.be.revertedWithCustomError(contract, "NotOwner");
      await expect(contract.setRecorder(ethers.ZeroAddress, true))
        .to.be.revertedWithCustomError(contract, "ZeroAddress");
    });

    it("lets only the owner transfer ownership", async function () {
      const { contract, owner, outsider } = await loadFixture(deployFixture);
      await expect(contract.connect(outsider).transferOwnership(outsider.address))
        .to.be.revertedWithCustomError(contract, "NotOwner");
      await expect(contract.transferOwnership(ethers.ZeroAddress))
        .to.be.revertedWithCustomError(contract, "ZeroAddress");
      await expect(contract.transferOwnership(outsider.address))
        .to.emit(contract, "OwnershipTransferred").withArgs(owner.address, outsider.address);
      expect(await contract.owner()).to.equal(outsider.address);
      await expect(contract.setRecorder(owner.address, true)).to.be.revertedWithCustomError(contract, "NotOwner");
    });
  });

  describe("recordTrade", function () {
    it("records a valid trade, stores it and emits TradeRecorded", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      const t = trade();
      await expect(contract.connect(recorder).recordTrade(...args(t)))
        .to.emit(contract, "TradeRecorded")
        .withArgs(t.tradeKey, t.sellerRef, t.buyerRef, t.quantityWh, t.pricePaisePerKwh, t.totalPaise, t.recordDigest, recorder.address);

      expect(await contract.isRecorded(t.tradeKey)).to.equal(true);
      expect(await contract.tradeCount()).to.equal(1n);
      const stored = await contract.getTrade(t.tradeKey);
      expect(stored.sellerRef).to.equal(t.sellerRef);
      expect(stored.buyerRef).to.equal(t.buyerRef);
      expect(stored.quantityWh).to.equal(2500n);
      expect(stored.pricePaisePerKwh).to.equal(650n);
      expect(stored.totalPaise).to.equal(1625n); // 2.5 kWh x Rs 6.50 = Rs 16.25
      expect(stored.recordDigest).to.equal(t.recordDigest);
      expect(stored.recordedAt).to.be.greaterThan(0n);
      expect(stored.status).to.equal(Status.Recorded);
    });

    it("rounds the total half-up exactly like the backend (BigDecimal HALF_UP)", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      // 1.001 kWh x Rs 4.50 = Rs 4.5045 -> Rs 4.50 (450 paise); 0.333 kWh x Rs 1.50 = 0.4995 -> 0.50
      await contract.connect(recorder).recordTrade(...args(trade({ tradeKey: tradeKey("R1"), quantityWh: 1001n, pricePaisePerKwh: 450n, totalPaise: 450n })));
      await contract.connect(recorder).recordTrade(...args(trade({ tradeKey: tradeKey("R2"), quantityWh: 333n, pricePaisePerKwh: 150n, totalPaise: 50n })));
      expect(await contract.tradeCount()).to.equal(2n);
    });

    it("rejects callers that are not authorized recorders, including the owner", async function () {
      const { contract, owner, outsider } = await loadFixture(deployFixture);
      await expect(contract.connect(outsider).recordTrade(...args(trade()))).to.be.revertedWithCustomError(contract, "NotRecorder");
      await expect(contract.connect(owner).recordTrade(...args(trade()))).to.be.revertedWithCustomError(contract, "NotRecorder");
    });

    it("stops accepting trades from a recorder whose authorization was revoked", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      await contract.setRecorder(recorder.address, false);
      await expect(contract.connect(recorder).recordTrade(...args(trade()))).to.be.revertedWithCustomError(contract, "NotRecorder");
    });

    it("prevents duplicate trade registration (replay)", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      const t = trade();
      await contract.connect(recorder).recordTrade(...args(t));
      await expect(contract.connect(recorder).recordTrade(...args(t)))
        .to.be.revertedWithCustomError(contract, "TradeAlreadyRecorded").withArgs(t.tradeKey);
      // A different payload under the same key is also refused
      await expect(contract.connect(recorder).recordTrade(...args(trade({ quantityWh: 1000n }))))
        .to.be.revertedWithCustomError(contract, "TradeAlreadyRecorded");
      expect(await contract.tradeCount()).to.equal(1n);
    });

    it("validates the trade key, parties and digest", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      const c = contract.connect(recorder);
      await expect(c.recordTrade(...args(trade({ tradeKey: ethers.ZeroHash })))).to.be.revertedWithCustomError(contract, "InvalidTradeKey");
      await expect(c.recordTrade(...args(trade({ sellerRef: ethers.ZeroHash })))).to.be.revertedWithCustomError(contract, "InvalidParty");
      await expect(c.recordTrade(...args(trade({ buyerRef: ethers.ZeroHash })))).to.be.revertedWithCustomError(contract, "InvalidParty");
      await expect(c.recordTrade(...args(trade({ buyerRef: ref("seller") })))).to.be.revertedWithCustomError(contract, "SameParty");
      await expect(c.recordTrade(...args(trade({ recordDigest: ethers.ZeroHash })))).to.be.revertedWithCustomError(contract, "InvalidDigest");
    });

    it("enforces quantity and price bounds", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      const c = contract.connect(recorder);
      const maxQ = await contract.MAX_QUANTITY_WH();
      const maxP = await contract.MAX_PRICE_PAISE_PER_KWH();
      await expect(c.recordTrade(...args(trade({ quantityWh: 0n })))).to.be.revertedWithCustomError(contract, "InvalidQuantity");
      await expect(c.recordTrade(...args(trade({ quantityWh: maxQ + 1n })))).to.be.revertedWithCustomError(contract, "InvalidQuantity");
      await expect(c.recordTrade(...args(trade({ pricePaisePerKwh: 0n })))).to.be.revertedWithCustomError(contract, "InvalidPrice");
      await expect(c.recordTrade(...args(trade({ pricePaisePerKwh: maxP + 1n })))).to.be.revertedWithCustomError(contract, "InvalidPrice");
      await expect(c.recordTrade(...args(trade({ tradeKey: tradeKey("MAX"), quantityWh: maxQ, pricePaisePerKwh: 1n }))))
        .to.emit(contract, "TradeRecorded");
    });

    it("rejects a total that does not match quantity x price", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      await expect(contract.connect(recorder).recordTrade(...args(trade({ totalPaise: 1626n }))))
        .to.be.revertedWithCustomError(contract, "TotalMismatch").withArgs(1625n, 1626n);
    });

    it("keeps rejected attempts off the chain: a reverted call changes no state", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      const t = trade({ quantityWh: 0n });
      await expect(contract.connect(recorder).recordTrade(...args(t))).to.be.reverted;
      expect(await contract.isRecorded(t.tradeKey)).to.equal(false);
      expect(await contract.tradeCount()).to.equal(0n);
    });
  });

  describe("state transitions", function () {
    async function recordedFixture() {
      const f = await deployFixture();
      const t = trade();
      await f.contract.connect(f.recorder).recordTrade(...args(t));
      return { ...f, t };
    }

    it("settles a recorded trade once, with an event", async function () {
      const { contract, recorder, t } = await loadFixture(recordedFixture);
      await expect(contract.connect(recorder).settleTrade(t.tradeKey)).to.emit(contract, "TradeSettled").withArgs(t.tradeKey, recorder.address);
      expect((await contract.getTrade(t.tradeKey)).status).to.equal(Status.Settled);
      await expect(contract.connect(recorder).settleTrade(t.tradeKey))
        .to.be.revertedWithCustomError(contract, "InvalidTransition").withArgs(t.tradeKey, Status.Settled);
      await expect(contract.connect(recorder).voidTrade(t.tradeKey, 1))
        .to.be.revertedWithCustomError(contract, "InvalidTransition").withArgs(t.tradeKey, Status.Settled);
    });

    it("voids a recorded trade with a reason; the key cannot be reused afterwards", async function () {
      const { contract, recorder, t } = await loadFixture(recordedFixture);
      await expect(contract.connect(recorder).voidTrade(t.tradeKey, 0)).to.be.revertedWithCustomError(contract, "InvalidReason");
      await expect(contract.connect(recorder).voidTrade(t.tradeKey, 2)).to.emit(contract, "TradeVoided").withArgs(t.tradeKey, 2, recorder.address);
      expect((await contract.getTrade(t.tradeKey)).status).to.equal(Status.Voided);
      await expect(contract.connect(recorder).settleTrade(t.tradeKey)).to.be.revertedWithCustomError(contract, "InvalidTransition");
      await expect(contract.connect(recorder).recordTrade(...args(t))).to.be.revertedWithCustomError(contract, "TradeAlreadyRecorded");
    });

    it("rejects transitions for unknown trades and from unauthorized callers", async function () {
      const { contract, recorder, outsider, t } = await loadFixture(recordedFixture);
      const unknown = tradeKey("TR-UNKNOWN");
      await expect(contract.connect(recorder).settleTrade(unknown)).to.be.revertedWithCustomError(contract, "TradeNotFound").withArgs(unknown);
      await expect(contract.connect(recorder).voidTrade(unknown, 1)).to.be.revertedWithCustomError(contract, "TradeNotFound");
      await expect(contract.connect(outsider).settleTrade(t.tradeKey)).to.be.revertedWithCustomError(contract, "NotRecorder");
      await expect(contract.connect(outsider).voidTrade(t.tradeKey, 1)).to.be.revertedWithCustomError(contract, "NotRecorder");
    });

    it("reports unknown trades as None", async function () {
      const { contract } = await loadFixture(deployFixture);
      expect(await contract.isRecorded(tradeKey("nothing"))).to.equal(false);
      expect((await contract.getTrade(tradeKey("nothing"))).status).to.equal(Status.None);
    });
  });

  describe("privacy", function () {
    it("emits only hashed or keyed references, never readable identifiers", async function () {
      const { contract, recorder } = await loadFixture(deployFixture);
      const tx = await contract.connect(recorder).recordTrade(...args(trade({ tradeKey: tradeKey("TR-PRIVATE") })));
      const receipt = await tx.wait();
      const blob = JSON.stringify(receipt.logs) + tx.data;
      for (const plain of ["TR-PRIVATE", "HH-001", "seller", "buyer"]) {
        expect(blob).to.not.include(ethers.hexlify(ethers.toUtf8Bytes(plain)).slice(2));
      }
    });
  });
});
