package org.namecoin.bitcoinj.spring.service;

// TODO: remove unneeded imports

import com.msgilligan.bitcoinj.json.conversion.RpcClientModule;
import com.msgilligan.bitcoinj.json.pojo.ServerInfo;
import org.consensusj.namecoin.jsonrpc.pojo.NameData;
import org.consensusj.namecoin.jsonrpc.rpcserver.NamecoinJsonRpc;

import org.libdohj.names.NameLookupByBlockHashOneFullBlock;
import org.libdohj.names.NameLookupByBlockHeightHashCache;
import org.libdohj.names.NameLookupLatest;
import org.libdohj.names.NameLookupLatestRestHeightApi;
import org.libdohj.names.NameLookupLatestRestMerkleApi;
import org.libdohj.names.NameLookupLatestRestMerkleApiSingleTx;
import org.libdohj.names.NameTransactionUtils;
import org.libdohj.script.NameScript;

//import org.libdohj.core.SpecificNameLookupWallet;
//import org.libdohj.kits.CustomWalletAppKit;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.FilteredBlock;
import org.bitcoinj.core.GetDataMessage;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.listeners.BlocksDownloadedEventListener;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.LevelDBBlockStore;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.IllegalStateException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.EnumSet;

/*

TODO: fix this error that shows up in the logs:

2016-07-19 10:53:06.820 ERROR 4744 --- [nio-8080-exec-1] org.bitcoinj.core.Context                : Performing thread fixup: you are accessing bitcoinj via a thread that has not had any context set on it.
2016-07-19 10:53:06.821 ERROR 4744 --- [nio-8080-exec-1] org.bitcoinj.core.Context                : This error has been corrected for, but doing this makes your app less robust.
2016-07-19 10:53:06.821 ERROR 4744 --- [nio-8080-exec-1] org.bitcoinj.core.Context                : You should use Context.propagate() or a ContextPropagatingThreadFactory.
2016-07-19 10:53:06.821 ERROR 4744 --- [nio-8080-exec-1] org.bitcoinj.core.Context                : Please refer to the user guide for more information about this.
2016-07-19 10:53:06.821 ERROR 4744 --- [nio-8080-exec-1] org.bitcoinj.core.Context                : Thread name is http-nio-8080-exec-1.

It doesn't seem to be causing obvious issues, but still should be fixed.

*/

/**
 * Implement a subset of Bitcoin JSON RPC using only a PeerGroup
 */
@Named
public class NameLookupService implements NamecoinJsonRpc {
    private static final String userAgentName = "LibdohjNameLookupDaemon";
    private static final String appVersion = "0.1";
    private static final int version = 1;
    private static final int protocolVersion = 1;
    private static final int walletVersion = 0;

    protected NetworkParameters netParams;
    protected Context context;
    
    protected String filePrefix;
    
    protected WalletAppKit kit;
    
    // TODO: use per-identity PeerGroups 
    protected PeerGroup namePeerGroup;
    
    protected NameLookupByBlockHashOneFullBlock lookupByHash;
    protected NameLookupByBlockHeightHashCache lookupByHeight;
    protected NameLookupLatest lookupLatest;
    
    private int timeOffset = 0;
    private BigDecimal difficulty = new BigDecimal(0);

    @Inject
    public NameLookupService(NetworkParameters params /*,
                       PeerDiscovery peerDiscovery */) {
        this.netParams = params;
        this.context = new Context(params);
        
        this.filePrefix = "LibdohjNameLookupDaemon";
        
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
    }

    @PostConstruct
    public void start() {
        // Start downloading the block chain and wait until it's done.
        kit.startAsync();
        kit.awaitRunning();
        
        namePeerGroup = new PeerGroup(netParams, kit.chain()) {
            // TODO: remove this override since it's not needed with full block mode
            @Override
            public ListenableFuture startAsync() {
                try {
                    return super.startAsync();
                }
                catch (IllegalStateException e) {
                    return executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("Skipping PeerGroup start because we already started.");
                        }
                    });
                }
            }
        };
        
        // TODO: look into allowing non-bloom, non-headers peers since we don't use filtered blocks at the moment
        namePeerGroup.setMinRequiredProtocolVersion(netParams.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER));
        namePeerGroup.addPeerDiscovery(new DnsDiscovery(netParams));
        namePeerGroup.startAsync();
        
        try {
            lookupByHash = new NameLookupByBlockHashOneFullBlock(namePeerGroup);
            lookupByHeight = new NameLookupByBlockHeightHashCache(kit.chain(), lookupByHash);
            
            // Height History API + P2P block
            //lookupLatest = new NameLookupLatestRestHeightApi("https://namecoin.webbtc.com/name/heights/", ".json?raw", kit.chain(), lookupByHeight);
            
            // Merkle Branch History API
            //lookupLatest = new NameLookupLatestRestMerkleApi(netParams, "https://namecoin.webbtc.com/name/", ".json?history&with_height&with_rawtx&with_mrkl_branch&with_tx_idx&raw", kit.chain(), kit.store(), lookupByHeight);
            
            // Merkle Branch Single-Transaction API (broken because the API doesn't return an array as expected)
            lookupLatest = new NameLookupLatestRestMerkleApiSingleTx(netParams, "https://namecoin.webbtc.com/name/", ".json?with_height&with_rawtx&with_mrkl_branch&with_tx_idx&raw", kit.chain(), kit.store(), lookupByHeight);
        } catch (Exception e) {
            System.out.println("Error initializing name lookups!");
            System.out.println(e.toString());
            System.out.println("Aborting!");
            System.exit(1);
        }
    }

    public NetworkParameters getNetworkParameters() {
        return this.netParams;
    }

    @Override
    public Integer getblockcount() {
        return kit.chain().getChainHead().getHeight();
    }
    
    @Override
    public Integer getconnectioncount() {
        return kit.peerGroup().numConnectedPeers();
    }

    @Override
    public ServerInfo getinfo() {
        // Dummy up a response for now.
        // Since ServerInfo is immutable, we have to build it entirely with the constructor.
        Coin balance = Coin.valueOf(0);
        //boolean testNet = !netParams.getId().equals(NetworkParameters.ID_MAINNET);
        boolean testNet = false;
        int keyPoolOldest = 0;
        int keyPoolSize = 0;
        return new ServerInfo(
                version,
                protocolVersion,
                walletVersion,
                balance,
                getblockcount(),
                timeOffset,
                getconnectioncount(),
                "proxy",
                difficulty,
                testNet,
                keyPoolOldest,
                keyPoolSize,
                Transaction.REFERENCE_DEFAULT_MIN_TX_FEE,
                Transaction.REFERENCE_DEFAULT_MIN_TX_FEE, // relayfee
                "no errors"                               // errors
        );
    }
    
    protected NameData name_show (Transaction tx, String name) throws IOException, UnsupportedEncodingException {
        
        NameScript ns = NameTransactionUtils.getNameAnyUpdateScript(tx, name);
    
        // TODO: fill in more of the fields
        // TODO: move the expiration period to libdohj's NetworkParameters
        return new NameData(
            name, 
            new String(ns.getOpValue().data, "ISO-8859-1"), 
            tx.getHash(), 
            null,
            ns.getAddress().getToAddress(netParams), 
            tx.getConfidence().getAppearedAtChainHeight(),
            36000 - tx.getConfidence().getDepthInBlocks() + 1, // tested for correctness against webbtc API
            null
        );
        
    }
    
    // TODO: document warning that this doesn't support identity isolation
    @Override
    public NameData name_show(String name) throws Exception {
    
        Transaction tx = lookupLatest.getNameTransaction(name, "Default identity");
        
        return name_show(tx, name);
        
    }
    
    // TODO: document warning that this doesn't support identity isolation
    @Override
    public NameData name_show_at_height(String name, int height) throws Exception {
    
        Transaction tx = lookupByHeight.getNameTransaction(name, height, "Default identity");
        
        return name_show(tx, name);
        
    }

}
