// Local demo helper used by scripts/start-demo.ps1:
//   npx hardhat run scripts/demo-env.js --network localhost
//
// Prints one JSON line with the recorder account the backend signs with (Hardhat dev account #1)
// and whether deployments/localhost.json still matches a live contract on the node.
//
// Safety: it refuses to run unless the node is a local Hardhat chain (chain ID 31337) whose
// accounts are Hardhat's well-known default development accounts. Those keys are published in the
// Hardhat documentation and hold no real value; never use this with a real network or wallet.
// The key is written to stdout for the calling script to capture; do not paste it anywhere.
const fs = require("fs");
const path = require("path");
const hre = require("hardhat");

const HARDHAT_DEFAULT_MNEMONIC = "test test test test test test test test test test test junk";
const RECORDER_INDEX = 1;

async function main() {
  const { ethers } = hre;
  const chainId = (await ethers.provider.getNetwork()).chainId;
  if (chainId !== 31337n) {
    throw new Error(`refusing to run: chain ID ${chainId} is not the local Hardhat chain (31337)`);
  }
  const wallet = ethers.HDNodeWallet.fromPhrase(HARDHAT_DEFAULT_MNEMONIC, undefined, `m/44'/60'/0'/0/${RECORDER_INDEX}`);
  const nodeAccounts = await ethers.provider.send("eth_accounts", []);
  if (!nodeAccounts.map((a) => a.toLowerCase()).includes(wallet.address.toLowerCase())) {
    throw new Error("refusing to run: the node does not use Hardhat's default development accounts");
  }

  const result = { recorderAddress: wallet.address, recorderKey: wallet.privateKey, deployment: "missing" };
  const file = path.join(__dirname, "..", "deployments", "localhost.json");
  if (fs.existsSync(file)) {
    const record = JSON.parse(fs.readFileSync(file, "utf8"));
    result.contractAddress = record.address;
    result.deploymentId = record.deploymentId;
    const code = await ethers.provider.getCode(record.address);
    result.deployment = "stale"; // recorded, but not (or no longer) live on this node
    if (code && code !== "0x") {
      const contract = await ethers.getContractAt("EnergyTrading", record.address);
      try {
        const [liveId, isRecorder] = await Promise.all([contract.deploymentId(), contract.isRecorder(wallet.address)]);
        result.deployment = liveId === record.deploymentId && isRecorder ? "valid" : "stale";
      } catch {
        result.deployment = "stale";
      }
    }
  }
  process.stdout.write(JSON.stringify(result) + "\n");
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
