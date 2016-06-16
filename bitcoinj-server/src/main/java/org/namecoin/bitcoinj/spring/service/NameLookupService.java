package org.namecoin.bitcoinj.spring.service;

import com.msgilligan.bitcoinj.json.conversion.RpcClientModule;
import com.msgilligan.bitcoinj.json.pojo.ServerInfo;
import org.consensusj.namecoin.jsonrpc.pojo.NameData;
import org.consensusj.namecoin.jsonrpc.rpcserver.NamecoinJsonRpc;

import org.libdohj.names.NameLookupByBlockHashOneFullBlock;
import org.libdohj.names.NameLookupByBlockHeightHashCache;
import org.libdohj.names.NameLookupLatestRestHeightApi;
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
    protected NameLookupLatestRestHeightApi lookupLatest;
    
    private int timeOffset = 0;
    private BigDecimal difficulty = new BigDecimal(0);

    @Inject
    public NameLookupService(NetworkParameters params /*,
                       PeerDiscovery peerDiscovery */) {
        this.netParams = params;
        this.context = new Context(params);
        
        this.filePrefix = "LibdohjNameLookupDaemon";
        
        /*
        // Setup kit, which is used to sync the blockchain.
        LevelDBBlockStore store = null;
        try {
            store = new LevelDBBlockStore(context, new File("./" + filePrefix + ".leveldbspvchain"));
        } catch ( BlockStoreException e ) {
            System.out.println("Error opening block store!  Aborting!");
            System.exit(1);
        }
        */
        
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
        namePeerGroup.setMinRequiredProtocolVersion(netParams.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER));
        namePeerGroup.addPeerDiscovery(new DnsDiscovery(netParams));
        namePeerGroup.startAsync();
        
        try {
            lookupByHash = new NameLookupByBlockHashOneFullBlock(namePeerGroup);
            lookupByHeight = new NameLookupByBlockHeightHashCache(kit.chain(), lookupByHash);
            lookupLatest = new NameLookupLatestRestHeightApi("https://namecoin.webbtc.com/name/", kit.chain(), lookupByHeight);
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

    /*
    @Override
    public Sha256Hash getblockhash(int height) throws BlockStoreException {
        Sha256Hash maybeResult = blockHashCache.get(new Integer(height));
        
        if (maybeResult != null) {
            return maybeResult;
        }
        
        // If we got this far, the block height is uncached.
        // This could be because the block is immature, 
        // or it could be because the cache is only initialized on initial startup.
        
        StoredBlock blockPointer = kit.chain().getChainHead();
        
        while (blockPointer.getHeight() != height) {
            blockPointer = blockPointer.getPrev(kit.store());
        }
        
        return blockPointer.getHeader().getHash();
    }
    */
    
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
    
    // TODO: document warning that this doesn't support identity isolation
    @Override
    public NameData name_show(String name) throws Exception {
    
        // TODO: add identity isolation
        int height = lookupLatest.getHeight(name);
        
        return name_show_at_height(name, height);
    }
    
    // TODO: document warning that this doesn't support identity isolation
    @Override
    public NameData name_show_at_height(String name, int height) throws Exception {
    
        Transaction tx = lookupByHeight.getNameTransaction(name, height, "Default identity");
        
        NameScript ns = NameTransactionUtils.getNameAnyUpdateScript(tx, name);
    
        return new NameData(
            name, 
            new String(ns.getOpValue().data, "ISO-8859-1"), 
            tx.getHash(), 
            ns.getAddress().getToAddress(netParams), 
            height - getblockcount() + 36000
        );
    }

}
