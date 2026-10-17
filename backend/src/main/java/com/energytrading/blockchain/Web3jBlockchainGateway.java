package com.energytrading.blockchain;

import com.energytrading.config.BlockchainProperties;
import okhttp3.OkHttpClient;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint128;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Web3j implementation of {@link BlockchainGateway} using raw ABI encoding (no generated wrapper)
 * and locally signed EIP-155 legacy transactions. The private key is held only inside
 * {@link Credentials}; it is never logged or returned.
 */
public class Web3jBlockchainGateway implements BlockchainGateway, AutoCloseable {

    private static final Event TRADE_RECORDED = new Event("TradeRecorded", Arrays.<TypeReference<?>>asList(
            new TypeReference<Bytes32>(true) {
            },
            new TypeReference<Bytes32>() {
            },
            new TypeReference<Bytes32>() {
            },
            new TypeReference<Uint64>() {
            },
            new TypeReference<Uint64>() {
            },
            new TypeReference<Uint128>() {
            },
            new TypeReference<Bytes32>() {
            },
            new TypeReference<Address>(true) {
            }));

    private final Web3j web3j;
    private final Credentials credentials;
    private final String contractAddress;
    private final long chainId;
    private final BigInteger gasLimit;
    private final String expectedDeploymentId;

    public Web3jBlockchainGateway(BlockchainProperties properties) {
        List<String> problems = properties.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid blockchain configuration: " + String.join("; ", problems));
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(properties.rpcTimeout())
                .readTimeout(properties.rpcTimeout())
                .writeTimeout(properties.rpcTimeout())
                .build();
        this.web3j = Web3j.build(new HttpService(properties.rpcUrl(), client));
        this.credentials = Credentials.create(properties.signerPrivateKey().trim());
        this.contractAddress = properties.contractAddress();
        this.chainId = properties.chainId();
        this.gasLimit = BigInteger.valueOf(properties.gasLimit());
        this.expectedDeploymentId = properties.deploymentId();
    }

    @Override
    public long chainId() {
        return chainId;
    }

    @Override
    public String contractAddress() {
        return contractAddress;
    }

    @Override
    public String signerAddress() {
        return credentials.getAddress();
    }

    @Override
    public NetworkStatus verifyNetwork() throws IOException {
        long actualChainId = ok(web3j.ethChainId().send()).getChainId().longValue();
        String code = ok(web3j.ethGetCode(contractAddress, DefaultBlockParameterName.LATEST).send()).getCode();
        boolean deployed = code != null && code.length() > 2 && !code.matches("0x0*");
        String version = null;
        boolean authorized = false;
        String deploymentId = null;
        if (deployed && actualChainId == chainId) {
            List<Type> versionOut = call(new Function("VERSION", List.of(), List.of(new TypeReference<Utf8String>() {
            })));
            version = versionOut.isEmpty() ? null : ((Utf8String) versionOut.get(0)).getValue();
            List<Type> recorderOut = call(new Function("isRecorder", List.of(new Address(signerAddress())),
                    List.of(new TypeReference<Bool>() {
                    })));
            authorized = !recorderOut.isEmpty() && ((Bool) recorderOut.get(0)).getValue();
            if (NetworkStatus.EXPECTED_VERSION.equals(version)) {
                List<Type> idOut = call(new Function("deploymentId", List.of(), List.of(new TypeReference<Bytes32>() {
                })));
                deploymentId = idOut.isEmpty() ? null : Numeric.toHexString(((Bytes32) idOut.get(0)).getValue());
            }
        }
        long latestBlock = ok(web3j.ethBlockNumber().send()).getBlockNumber().longValue();
        var genesis = ok(web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(BigInteger.ZERO), false).send()).getBlock();
        String genesisHash = genesis != null ? genesis.getHash() : null;
        return new NetworkStatus(chainId, actualChainId, contractAddress, deployed, version, signerAddress(), authorized,
                latestBlock, expectedDeploymentId, deploymentId, genesisHash);
    }

    @Override
    public boolean isTradeRecorded(byte[] tradeKey) throws IOException {
        List<Type> out = call(new Function("isRecorded", List.of(new Bytes32(tradeKey)), List.of(new TypeReference<Bool>() {
        })));
        return !out.isEmpty() && ((Bool) out.get(0)).getValue();
    }

    @Override
    public Optional<OnChainTrade> getTrade(byte[] tradeKey) throws IOException {
        List<Type> out = call(new Function("getTrade", List.of(new Bytes32(tradeKey)), Arrays.<TypeReference<?>>asList(
                new TypeReference<Bytes32>() {
                }, new TypeReference<Bytes32>() {
                }, new TypeReference<Uint64>() {
                }, new TypeReference<Uint64>() {
                }, new TypeReference<Uint128>() {
                }, new TypeReference<Bytes32>() {
                }, new TypeReference<Uint64>() {
                }, new TypeReference<Uint8>() {
                })));
        if (out.size() != 8) {
            return Optional.empty();
        }
        int status = ((Uint8) out.get(7)).getValue().intValue();
        if (status == 0) {
            return Optional.empty();
        }
        return Optional.of(new OnChainTrade(((Bytes32) out.get(0)).getValue(), ((Bytes32) out.get(1)).getValue(),
                ((Uint64) out.get(2)).getValue().longValue(), ((Uint64) out.get(3)).getValue().longValue(),
                ((Uint128) out.get(4)).getValue(), ((Bytes32) out.get(5)).getValue(),
                ((Uint64) out.get(6)).getValue().longValue(), status));
    }

    @Override
    public Optional<RecordedLog> findTradeRecordedLog(byte[] tradeKey) throws IOException {
        EthFilter filter = new EthFilter(DefaultBlockParameterName.EARLIEST, DefaultBlockParameterName.LATEST, contractAddress);
        filter.addSingleTopic(EventEncoder.encode(TRADE_RECORDED));
        filter.addSingleTopic(Numeric.toHexString(tradeKey));
        EthLog logs = ok(web3j.ethGetLogs(filter).send());
        for (EthLog.LogResult<?> result : logs.getLogs()) {
            if (result.get() instanceof Log log) {
                return Optional.of(new RecordedLog(log.getTransactionHash(), log.getBlockNumber().longValue()));
            }
        }
        return Optional.empty();
    }

    @Override
    public BigInteger pendingNonce() throws IOException {
        return ok(web3j.ethGetTransactionCount(signerAddress(), DefaultBlockParameterName.PENDING).send()).getTransactionCount();
    }

    @Override
    public BigInteger latestNonce() throws IOException {
        return ok(web3j.ethGetTransactionCount(signerAddress(), DefaultBlockParameterName.LATEST).send()).getTransactionCount();
    }

    @Override
    public SignedTransaction signRecordTrade(TradeRecordPayload p, BigInteger nonce) throws IOException {
        Function function = new Function("recordTrade", Arrays.<Type>asList(
                new Bytes32(p.tradeKey()), new Bytes32(p.sellerRef()), new Bytes32(p.buyerRef()),
                new Uint64(BigInteger.valueOf(p.quantityWh())), new Uint64(BigInteger.valueOf(p.pricePaisePerKwh())),
                new Uint128(p.totalPaise()), new Bytes32(p.recordDigest())), List.of());
        BigInteger gasPrice = ok(web3j.ethGasPrice().send()).getGasPrice();
        RawTransaction raw = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contractAddress, BigInteger.ZERO,
                FunctionEncoder.encode(function));
        String rawHex = Numeric.toHexString(TransactionEncoder.signMessage(raw, chainId, credentials));
        return new SignedTransaction(Hash.sha3(rawHex), rawHex, nonce);
    }

    @Override
    public BroadcastResult broadcast(SignedTransaction transaction) {
        try {
            EthSendTransaction response = web3j.ethSendRawTransaction(transaction.rawHex()).send();
            if (response.hasError()) {
                return BroadcastResult.rejected(shorten(response.getError().getMessage()));
            }
            String hash = response.getTransactionHash();
            if (hash != null && !hash.equalsIgnoreCase(transaction.hash())) {
                return BroadcastResult.unknown("node returned an unexpected transaction hash");
            }
            return BroadcastResult.accepted();
        } catch (IOException | RuntimeException e) {
            return BroadcastResult.unknown(e.getClass().getSimpleName() + ": " + shorten(e.getMessage()));
        }
    }

    @Override
    public Optional<ReceiptInfo> receipt(String transactionHash) throws IOException {
        EthGetTransactionReceipt response = ok(web3j.ethGetTransactionReceipt(transactionHash).send());
        String eventTopic = EventEncoder.encode(TRADE_RECORDED);
        return response.getTransactionReceipt().map(r -> new ReceiptInfo(r.getTransactionHash(),
                r.getBlockNumber().longValue(), r.isStatusOK(), r.getRevertReason(), r.getTo(),
                r.getLogs() == null ? List.of() : r.getLogs().stream()
                        .filter(l -> contractAddress.equalsIgnoreCase(l.getAddress()) && l.getTopics() != null
                                && l.getTopics().size() >= 2 && eventTopic.equalsIgnoreCase(l.getTopics().get(0)))
                        .map(l -> Numeric.cleanHexPrefix(l.getTopics().get(1)).toLowerCase())
                        .toList()));
    }

    @Override
    public boolean isTransactionKnown(String transactionHash) throws IOException {
        return ok(web3j.ethGetTransactionByHash(transactionHash).send()).getTransaction().isPresent();
    }

    @Override
    public void close() {
        web3j.shutdown();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Type> call(Function function) throws IOException {
        EthCall response = ok(web3j.ethCall(Transaction.createEthCallTransaction(signerAddress(), contractAddress,
                FunctionEncoder.encode(function)), DefaultBlockParameterName.LATEST).send());
        if (response.isReverted()) {
            throw new IOException("eth_call reverted: " + shorten(response.getRevertReason()));
        }
        return FunctionReturnDecoder.decode(response.getValue(), (List) function.getOutputParameters());
    }

    private static <T extends Response<?>> T ok(T response) throws IOException {
        if (response.hasError()) {
            throw new IOException("JSON-RPC error " + response.getError().getCode() + ": " + shorten(response.getError().getMessage()));
        }
        return response;
    }

    private static String shorten(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    @Override
    public String toString() {
        return "Web3jBlockchainGateway[contract=" + contractAddress + ", chainId=" + chainId + ", signer=" + signerAddress() + "]";
    }
}
