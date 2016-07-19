package org.namecoin.bitcoinj.daemon.config;

import org.libdohj.names.NameLookupByBlockHash;
import org.libdohj.names.NameLookupByBlockHashOneFullBlock;
import org.libdohj.names.NameLookupByBlockHeight;
import org.libdohj.names.NameLookupByBlockHeightHashCache;
import org.libdohj.names.NameLookupLatest;
import org.libdohj.names.NameLookupLatestRestHeightApi;
import org.libdohj.names.NameLookupLatestRestMerkleApi;
import org.libdohj.names.NameLookupLatestRestMerkleApiSingleTx;

import org.bitcoinj.core.Context;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.LevelDBBlockStore;

import com.googlecode.jsonrpc4j.spring.JsonServiceExporter;
import com.msgilligan.bitcoinj.json.conversion.RpcServerModule;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.libdohj.params.NamecoinMainNetParams;
import org.namecoin.bitcoinj.rpcserver.NamecoinJsonRpc;
import org.namecoin.bitcoinj.spring.service.NameLookupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.fasterxml.jackson.databind.Module;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Spring configuration for bitcoinj, Bitcoin services, and JSON-RPC server
 */
@Configuration
public class NamecoinConfig {

    @Autowired
    private Environment env;

    @Bean
    public NetworkParameters networkParameters() {
        return NamecoinMainNetParams.get();
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
    
    @Bean
    public WalletAppKit getKit(Context context) {
        if (kit == null) {
        
            // TODO: check privacy settings here
            
            // TODO: make File(".") and filePrefix configurable
            String filePrefix = "LibdohjNameLookupDaemon";
            
            kit = new WalletAppKit(context, new File("."), filePrefix) {
                @Override
                protected BlockStore provideBlockStore(File file) throws BlockStoreException {
                    return new LevelDBBlockStore(context, file);
                }
                
                @Override
                protected void onSetupCompleted() {
                    vPeerGroup.setMinRequiredProtocolVersion(params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.MINIMUM));
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
    
    private NameLookupByBlockHash lookupByHash;
    
    @Bean
    public NameLookupByBlockHash getLookupByHash(NetworkParameters netParams, WalletAppKit kit) throws Exception {
        if (lookupByHash == null) {
        
            String algo = env.getProperty("namelookup.byhash.algo", "p2pfullblock");
            
            switch (algo) {
                case "p2pfullblock":
                    if (getStreamIsolationEnabled()) {
                        throw new Exception("Stream isolation is not supported.  If you are okay with reducing your privacy, disable stream isolation.");
                    }
                    if (getProxyEnabled()) {
                        throw new Exception("Proxying is not supported.  If you are okay with reducing your privacy, disable proxying.");
                    }
                    
                    PeerGroup namePeerGroup = new PeerGroup(netParams, kit.chain());
                    
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
    
    // TODO: finish this logic
    @Bean
    public NameLookupByBlockHeight getLookupByHeight(WalletAppKit kit, NameLookupByBlockHash lookupByHash) throws Exception {
        if (lookupByHeight == null) {
            lookupByHeight = new NameLookupByBlockHeightHashCache(kit.chain(), lookupByHash);
        }
        
        return lookupByHeight;
    }
    
    private NameLookupLatest lookupLatest;
    
    // TODO: finish this logic
    @Bean
    public NameLookupLatest getLookupLatest(WalletAppKit kit, NameLookupByBlockHeight lookupByHeight) throws Exception {
        if (lookupLatest == null) {
            lookupLatest = new NameLookupLatestRestMerkleApiSingleTx(networkParameters(), "https://namecoin.webbtc.com/name/", ".json?with_height&with_rawtx&with_mrkl_branch&with_tx_idx&raw", kit.chain(), kit.store(), (NameLookupByBlockHeightHashCache)lookupByHeight);
        }
        
        return lookupLatest;
    }
    
    @Bean
    public Module bitcoinJMapper() {
        return new RpcServerModule();
    }

    @Bean
    public NameLookupService nameLookupService(NetworkParameters params, Context context, WalletAppKit kit, NameLookupByBlockHeight lookupByHeight, NameLookupLatest lookupLatest /*, PeerDiscovery peerDiscovery*/) {
        return new NameLookupService(params, context, kit, lookupByHeight, lookupLatest);
    }

    @Bean(name="/")
    public JsonServiceExporter namecoinServiceExporter(NameLookupService nameLookupService) {
        JsonServiceExporter exporter = new JsonServiceExporter();
        exporter.setService(nameLookupService);
        exporter.setServiceInterface(NamecoinJsonRpc.class);
        exporter.setBackwardsComaptible(true);
        return exporter;
    }
}
