package org.consensusj.namecoin.daemon.config;

import com.fasterxml.jackson.databind.Module;

import org.libdohj.names.NameLookupByBlockHash;
import org.libdohj.names.NameLookupByBlockHashOneFullBlock;
import org.libdohj.names.NameLookupByBlockHeight;
import org.libdohj.names.NameLookupByBlockHeightHashCache;
import org.libdohj.names.NameLookupLatest;
import org.libdohj.names.NameLookupLatestLevelDBTransactionCache;
import org.libdohj.names.NameLookupLatestRestHeightApi;
import org.libdohj.names.NameLookupLatestRestMerkleApi;
import org.libdohj.names.NameLookupLatestRestMerkleApiSingleTx;

import org.bitcoinj.core.Block;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.LevelDBBlockStore;

import com.googlecode.jsonrpc4j.spring.JsonServiceExporter;
import com.msgilligan.bitcoinj.json.conversion.RpcServerModule;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.kits.WalletAppKit;
import org.consensusj.namecoin.jsonrpc.rpcserver.NamecoinJsonRpc;
import org.libdohj.params.NamecoinMainNetParams;
import org.namecoin.bitcoinj.spring.service.NameLookupService;
//import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Date;
import java.util.EnumSet;

/**
 * Spring configuration for namecoinj, Namecoin services, and JSON-RPC server
 */
@Configuration
public class NamecoinConfig /* implements DisposableBean */ {

    @Autowired
    private Environment env;

    private NetworkParameters params;

    protected Logger log = LoggerFactory.getLogger(NamecoinConfig.class);
    
    @Bean
    public NetworkParameters networkParameters() {
        // TODO: We may also want to make this set from a configuration string
        // so a binary release can be configure via external string parameters
        // and NetworkParameters.fromID()
        if (params == null) {
            String latestAlgo = env.getProperty("namelookup.latest.algo", "restmerkleapi");
            Boolean enabledGetFullBlocks = env.getProperty("namelookup.latest.getfullblocks", Boolean.class, true);

            // WARNING: Stupid API hack here.
            // Unfortunately there's no good API in BitcoinJ to selectively check internal validity of incoming full-blocks.
            // Either you're in SPV mode (never check internal validity or signatures), or you're in full-node mode (always demand internal validity as well as signatures).
            // We want to only check internal validity for unexpired blocks (to avoid censorship attacks by non-miners).
            // We don't care about signature validity at all (miner attacks aren't in our threat model).
            // Normally the behavior we want to change would be in AbstractBlockChain.add(), but it's private.
            // Conveniently, received full blocks happen to be passed to checkDifficultyTransition prior to being accepted, and that method is public.
            // So we override it to add a conditional internal validity check based on block timestamp.
            params = new NamecoinMainNetParams() {
                @Override
                public void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock, BlockStore blockStore)
                    throws VerificationException, BlockStoreException {

                    super.checkDifficultyTransitions(storedPrev, nextBlock, blockStore);

                    switch (latestAlgo) {
                        case "leveldbtxcache":
                            if (enabledGetFullBlocks) {
                                // If the block is newer than 1 year old
                                // TODO: use BIP 113 timestamps
                                if ( ! ( (new Date().getTime() / 1000 ) - nextBlock.getTimeSeconds() > 366 * 24 * 60 * 60 ) ) {
                                    log.debug("Verifying internal validity of candidate full block " + (storedPrev.getHeight() + 1) );
                                    final EnumSet<Block.VerifyFlag> flags = EnumSet.noneOf(Block.VerifyFlag.class);
                                    nextBlock.verify(-1, flags);
                                    log.debug("Internal validity check passed for candidate full block " + (storedPrev.getHeight() + 1) );
                                }
                            }
                            break;
                    }
                }
            };
        }
        return params;
    }

    private Context context;

    @Bean
    public Context getContext(NetworkParameters params) {
        if (context == null) {
            context = new Context(params);
        }
        return context;
    }

    /*
    @Bean
    public PeerDiscovery peerDiscovery(NetworkParameters params) throws FileNotFoundException {
        PeerDiscovery pd;
        pd = new DnsDiscovery(params);
//        pd = new SeedPeers(params);
        return pd;
    }
    */

    private WalletAppKit kit;

    PeerGroup namePeerGroup;

    @Bean
    public WalletAppKit getKit(Context context) throws Exception {
        if (kit == null) {

            dieIfProxyEnabled();
            dieIfStreamIsolationEnabled();

            String latestAlgo = env.getProperty("namelookup.latest.algo", "restmerkleapi");

            // TODO: make File(".") and filePrefix configurable
            File directory = new File(".");
            String filePrefix = "LibdohjNameLookupDaemon";

            kit = new WalletAppKit(context, directory, filePrefix) {
                @Override
                protected BlockStore provideBlockStore(File file) throws BlockStoreException {
                    return new LevelDBBlockStore(context, file);
                }

                @Override
                protected void onSetupCompleted() {
                    vPeerGroup.setMinRequiredProtocolVersion(params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.MINIMUM));

                    // Some of the algos require being setup inside this hook, so we do it here instead of after getKit() finishes.
                    switch (latestAlgo) {
                        case "leveldbtxcache":
                            try {
                                namePeerGroup = new PeerGroup(context.getParams(), kit.chain());

                                // TODO: look into allowing non-bloom, non-headers peers since we don't use filtered blocks at the moment
                                namePeerGroup.setMinRequiredProtocolVersion(context.getParams().getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER));
                                namePeerGroup.addPeerDiscovery(new DnsDiscovery(context.getParams()));
                                namePeerGroup.startAsync();

                                lookupLatest = new NameLookupLatestLevelDBTransactionCache(context, new File(directory, filePrefix + ".namedb"), kit.chain(), kit.store(), namePeerGroup);

                                // TODO: optionally enable bloom filtering
                                peerGroup().setBloomFilteringEnabled(false);
                                peerGroup().setFastCatchupTimeSecs( (new Date().getTime() / 1000 ) - (366 * 24 * 60 * 60) );
                            }
                            catch (Exception e) {
                                log.error("Exception creating Name Database", e);
                                System.exit(-1);
                            }
                            break;
                    }
                }

                @Override
                protected void shutDown() throws Exception {
                    if (namePeerGroup != null) {
                        namePeerGroup.stop();
                    }

                    if (lookupLatest instanceof NameLookupLatestLevelDBTransactionCache) {
                        log.info("Closing Name Database");
                        try {
                            ((NameLookupLatestLevelDBTransactionCache)lookupLatest).close();
                        }
                        catch (Exception e) {
                            log.error("Exception occurred closing Name Database", e);
                        }
                    }

                    super.shutDown();
                }
            };

            // TODO: call kit.setCheckpoints so that we sync faster.
            // See the following links:
            // https://groups.google.com/forum/?_escaped_fragment_=topic/bitcoinj/CycE9YTS7Bs#!topic/bitcoinj/CycE9YTS7Bs
            // https://github.com/bitcoinj/bitcoinj/blob/master/tools/src/main/resources/org/bitcoinj/tools/build-checkpoints-help.txt
            // https://github.com/namecoin/namecoin-core/blob/master/src/chainparams.cpp#L164

            // When uncommented, this allows the RPC server to use an incomplete blockchain.  This is usually insecure for name lookups.
            // TODO: uncomment this and use a different method to detect incomplete blockchains that doesn't block the RPC server from replying with error messages.
            //kit.setBlockingStartup(false);

            // Start downloading the block chain and wait until it's done.
            kit.startAsync();
            kit.awaitRunning();
        }

        return kit;
    }

    private boolean getStreamIsolationEnabled() {
        Boolean val = env.getProperty("connection.streamisolation", Boolean.class, true);
        return val;
    }

    private boolean getProxyEnabled() {
        Boolean val = env.getProperty("connection.proxyenabled", Boolean.class, true);
        return val;
    }

    private void dieIfStreamIsolationEnabled() throws Exception {
        if (getStreamIsolationEnabled()) {
            throw new Exception("Stream isolation is not supported.  If you are okay with reducing your privacy, disable stream isolation.");
        }
    }

    private void dieIfProxyEnabled() throws Exception {
        if (getProxyEnabled()) {
            throw new Exception("Proxying is not supported.  If you are okay with reducing your privacy, disable proxying.");
        }
    }

    private NameLookupByBlockHash lookupByHash;

    @Bean
    public NameLookupByBlockHash getLookupByHash(NetworkParameters netParams, WalletAppKit kit) throws Exception {
        if (lookupByHash == null) {

            String algo = env.getProperty("namelookup.byhash.algo", "p2pfullblock");

            switch (algo) {
                case "p2pfullblock":
                    dieIfProxyEnabled();
                    dieIfStreamIsolationEnabled();

                    namePeerGroup = new PeerGroup(netParams, kit.chain());

                    // TODO: look into allowing non-bloom, non-headers peers since we don't use filtered blocks at the moment
                    namePeerGroup.setMinRequiredProtocolVersion(netParams.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER));
                    namePeerGroup.addPeerDiscovery(new DnsDiscovery(netParams));
                    namePeerGroup.startAsync();

                    lookupByHash = new NameLookupByBlockHashOneFullBlock(namePeerGroup);

                    break;
                default:
                    throw new Exception("Invalid algorithm for lookup by block hash.");
            }
        }

        return lookupByHash;
    }

    private NameLookupByBlockHeight lookupByHeight;

    @Bean
    public NameLookupByBlockHeight getLookupByHeight(WalletAppKit kit, NameLookupByBlockHash lookupByHash) throws Exception {
        if (lookupByHeight == null) {
            String algo = env.getProperty("namelookup.byheight.algo", "p2phashcache");

            switch (algo) {
                case "p2phashcache":
                    lookupByHeight = new NameLookupByBlockHeightHashCache(kit.chain(), lookupByHash);
                    break;
                default:
                    throw new Exception("Invalid algorithm for lookup by block height.");
            }
        }

        return lookupByHeight;
    }

    private NameLookupLatest lookupLatest;

    @Bean
    public NameLookupLatest getLookupLatest(NetworkParameters netParams, WalletAppKit kit, NameLookupByBlockHeight lookupByHeight) throws Exception {
        if (lookupLatest == null) {
            String algo = env.getProperty("namelookup.latest.algo", "restmerkleapi");

            String restUrlPrefix, restUrlSuffix;

            switch (algo) {
                case "restheightapi":
                    dieIfProxyEnabled();
                    dieIfStreamIsolationEnabled();

                    restUrlPrefix = env.getProperty("namelookup.latest.resturlprefix", "https://namecoin.webbtc.com/name/heights/");
                    restUrlSuffix = env.getProperty("namelookup.latest.resturlsuffix", ".json?raw");
                    lookupLatest = new NameLookupLatestRestHeightApi(restUrlPrefix, restUrlSuffix, kit.chain(), lookupByHeight);
                    break;
                case "restmerkleapi":
                    dieIfProxyEnabled();
                    dieIfStreamIsolationEnabled();

                    restUrlPrefix = env.getProperty("namelookup.latest.resturlprefix", "https://namecoin.webbtc.com/name/");
                    restUrlSuffix = env.getProperty("namelookup.latest.resturlsuffix", ".json?history&with_height&with_rawtx&with_mrkl_branch&with_tx_idx&raw");
                    lookupLatest = new NameLookupLatestRestMerkleApi(netParams, restUrlPrefix, restUrlSuffix, kit.chain(), kit.store(), (NameLookupByBlockHeightHashCache)lookupByHeight);
                    break;
                case "restmerkleapisingle":
                    dieIfProxyEnabled();
                    dieIfStreamIsolationEnabled();

                    restUrlPrefix = env.getProperty("namelookup.latest.resturlprefix", "https://namecoin.webbtc.com/name/");
                    restUrlSuffix = env.getProperty("namelookup.latest.resturlsuffix", ".json?with_height&with_rawtx&with_mrkl_branch&with_tx_idx&raw");
                    lookupLatest = new NameLookupLatestRestMerkleApiSingleTx(netParams, restUrlPrefix, restUrlSuffix, kit.chain(), kit.store(), (NameLookupByBlockHeightHashCache)lookupByHeight);
                    break;
                default:
                    throw new Exception("Invalid algorithm for latest lookup.");
            }
        }

        return lookupLatest;
    }

    @Bean
    public Context getContext(NetworkParameters params) {
        return new Context(params);
    }

    @Bean
    public WalletAppKit getKit(Context context) throws Exception {
        // TODO: make File(".") and filePrefix configurable
        File directory = new File(".");
        String filePrefix = "NamecoinJDaemon";

        return new WalletAppKit(context, directory, filePrefix);
     }

    @Bean
    public Module bitcoinJMapper() {
        return new RpcServerModule();
    }

    @Bean
    public NameLookupService nameLookupService(NetworkParameters params, Context context, WalletAppKit kit, NameLookupByBlockHeight lookupByHeight, NameLookupLatest lookupLatest /*, PeerDiscovery peerDiscovery*/) {
        return new NameLookupService(params, context, kit, lookupByHeight, lookupLatest);
    }

    @Bean
    public WalletAppKitService walletAppKitService(NetworkParameters params, Context context, WalletAppKit kit) {
        return new WalletAppKitService(params, context, kit);
    }

    @Bean(name="/")
    public JsonServiceExporter namecoinServiceExporter(NameLookupService nameLookupService) {
        JsonServiceExporter exporter = new JsonServiceExporter();
        exporter.setService(nameLookupService);
        exporter.setServiceInterface(NamecoinJsonRpc.class);
        exporter.setBackwardsComaptible(true);
        return exporter;
    }

    /*
    @Override
    public void destroy() {
        if (lookupLatest instanceof NameLookupLatestLevelDBTransactionCache) {
            log.info("Closing Name Database");
            try {
                ((NameLookupLatestLevelDBTransactionCache)lookupLatest).close();
            }
            catch (Exception e) {
                log.error("Exception occurred closing Name Database", e);
            }
        }
    }
    */
}
