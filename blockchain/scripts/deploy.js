// Deploys EnergyTrading and writes the address to deployments/<network>.json.
//
//   npx hardhat node                       (terminal 1)
//   npm run deploy:local                   (terminal 2; network "localhost")
//
// RECORDER_ADDRESS (optional) is the backend signer allowed to record trades; it defaults to the
// deployer. Only public addresses are handled here; no private key is read or written.
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const hre = require("hardhat");

async function main() {
  const { ethers, network } = hre;
  const [deployer] = await ethers.getSigners();
  const recorder = process.env.RECORDER_ADDRESS || deployer.address;
  if (!ethers.isAddress(recorder)) {
    throw new Error("RECORDER_ADDRESS is not a valid address");
  }

  const chainId = (await ethers.provider.getNetwork()).chainId;
  // A fresh random salt per deployment makes deploymentId unique even at a reused address.
  const salt = "0x" + crypto.randomBytes(32).toString("hex");
  const contract = await ethers.deployContract("EnergyTrading", [recorder, salt]);
  await contract.waitForDeployment();
  const address = await contract.getAddress();
  const receipt = await contract.deploymentTransaction().wait();

  if (!(await contract.isRecorder(recorder))) {
    throw new Error("Recorder authorization check failed after deployment");
  }

  const record = {
    contract: "EnergyTrading",
    version: await contract.VERSION(),
    network: network.name,
    chainId: chainId.toString(),
    address,
    deploymentId: await contract.deploymentId(),
    genesisHash: (await ethers.provider.getBlock(0)).hash,
    deployer: deployer.address,
    recorder: ethers.getAddress(recorder),
    deploymentTx: receipt.hash,
    blockNumber: receipt.blockNumber,
    deployedAt: new Date().toISOString(),
  };
  const dir = path.join(__dirname, "..", "deployments");
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${network.name}.json`);
  fs.writeFileSync(file, JSON.stringify(record, null, 2) + "\n");

  console.log(`EnergyTrading ${record.version} deployed to ${address} on ${network.name} (chainId ${record.chainId})`);
  console.log(`Authorized recorder: ${record.recorder}`);
  console.log(`Deployment record: ${path.relative(process.cwd(), file)}`);
  console.log(`Deployment ID: ${record.deploymentId}`);
  console.log(`Backend: set CONTRACT_ADDRESS=${address}, BLOCKCHAIN_CHAIN_ID=${record.chainId} and BLOCKCHAIN_DEPLOYMENT_ID=${record.deploymentId}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
