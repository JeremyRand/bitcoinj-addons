package org.namecoin.bitcoinj.spring.service;

import com.msgilligan.bitcoinj.json.conversion.RpcClientModule;
import com.msgilligan.bitcoinj.json.pojo.ServerInfo;
import org.consensusj.namecoin.jsonrpc.pojo.NameData;
import org.consensusj.namecoin.jsonrpc.rpcserver.NamecoinJsonRpc;

import org.libdohj.core.NameScript;
import org.libdohj.core.SpecificNameLookupWallet;
import org.libdohj.kits.CustomWalletAppKit;

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
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.script.Script;
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
    
    protected CustomWalletAppKit kit;
    
    // TODO: use per-identity PeerGroups 
    protected PeerGroup namePeerGroup;
    
    protected ConcurrentHashMap<Integer, Sha256Hash> blockHashCache;
    
    private int timeOffset = 0;
    private BigDecimal difficulty = new BigDecimal(0);

    @Inject
    public NameLookupService(NetworkParameters params /*,
                       PeerDiscovery peerDiscovery */) {
        this.netParams = params;
        this.context = new Context(params);
        
        this.filePrefix = "LibdohjNameLookupDaemon";
        
        // Setup kit, which is used to sync the blockchain.
        LevelDBBlockStore store = null;
        try {
            store = new LevelDBBlockStore(context, new File("./" + filePrefix + ".leveldbspvchain"));
        } catch ( BlockStoreException e ) {
            System.out.println("Error opening block store!  Aborting!");
            System.exit(1);
        }
        
        kit = new CustomWalletAppKit(context, new File("."), filePrefix, null, store, null) {
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
        
        try {
            initBlockHashCache();
        } catch (BlockStoreException e) {
            // TODO: log something here, since this shouldn't happen and it would be bad.
        }
        
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
    }
    
    protected void initBlockHashCache() throws BlockStoreException {
        blockHashCache = new ConcurrentHashMap<Integer, Sha256Hash>(72000);
        
        StoredBlock blockPointer = kit.chain().getChainHead();
        
        int headHeight = blockPointer.getHeight();
        int reorgSafety = 120;
        int newestHeight = headHeight - reorgSafety;
        int oldestHeight = headHeight - 36000 - reorgSafety; // 36000 = name expiration
        
        while (blockPointer.getHeight() >= oldestHeight) {
            
            if (blockPointer.getHeight() <= newestHeight) {
                blockHashCache.put(new Integer(blockPointer.getHeight()), blockPointer.getHeader().getHash());
            }
        
            blockPointer = blockPointer.getPrev(kit.store());
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
    
    @Override
    public NameData name_show(String name) throws Exception {
    
        // TODO: let the user decide which scheme to use
        
        if (false) {
            return name_showFilteredBlock(name);
        }
        else {
            return name_showFullBlock(name);
        }
    }
    
    protected NameData name_showFullBlock(String name) throws Exception {
        int headHeight = getblockcount();
        
        // Note: this will fail if our head is at a different height than the API server's.
        // TODO: try again if the value is bad.
        int targetNameHeight = getExpiresInOfName(name) + headHeight - 36000;
        
        System.out.println("Name is at block height: " + targetNameHeight);
        
        // TODO: test all of these cases
        System.out.println("Making sure the block is unexpired and confirmed...");
        
        if (targetNameHeight < 1) {
            System.out.println("Nonpositive block height; not trustworthy!");
            return null;
        }
        
        int nameBlockConfirmations = headHeight - targetNameHeight;
        
        if (nameBlockConfirmations < 12) {
            System.out.println("Block does not yet have 12 confirmations; not trustworthy!");
            return null;
        }
        else if (nameBlockConfirmations >= 36000) {
            System.out.println("Block has expired; not trustworthy!");
            return null;
        }
        
        Sha256Hash targetNameBlockHash = getblockhash(targetNameHeight);
        
        System.out.println("Found hash for the requested height.");
        
        Block nameFullBlock = namePeerGroup.getDownloadPeer().getBlock(targetNameBlockHash).get();
        
        // The full block hasn't been verified in any way!
        // So let's do that now.
        
        final EnumSet<Block.VerifyFlag> flags = EnumSet.noneOf(Block.VerifyFlag.class);
        nameFullBlock.verify(-1, flags);
        
        // Now we know that the block is internally valid (including the merkle root).
        // We haven't verified signature validity, but our threat model is SPV.
        
        for (Transaction tx : nameFullBlock.getTransactions()) {
            for (TransactionOutput output : tx.getOutputs()) {
                try {
                    Script scriptPubKey = output.getScriptPubKey();
                    NameScript ns = new NameScript(scriptPubKey);
                    if(ns.isNameOp() && ns.isAnyUpdate() && new String(ns.getOpName().data, "ISO-8859-1").equals(name)) {
                        System.out.println("Found requested name operation.");
                        
                        try {
                            return new NameData(
                                name, 
                                new String(ns.getOpValue().data, "ISO-8859-1"), 
                                tx.getHash(), 
                                ns.getAddress().getToAddress(netParams), 
                                targetNameHeight - headHeight + 36000
                            );
                        } catch (UnsupportedEncodingException e) {
                            throw (new Exception("ERROR: Name value wasn't in supported encoding.") );
                        }
                    }
                } catch (ScriptException e) {
                    System.out.println("ScriptException while checking name transactions: " + e.toString());
                    continue;
                } catch (UnsupportedEncodingException e) {
                    continue;
                }
            }
        }
        
        throw (new Exception("Matching name transaction not found in full block."));
    }
    
    protected NameData name_showFilteredBlock(String name) throws Exception {
        // TODO: remove this line
        //StoredBlock nameBlockPointer = kit.chain().getChainHead();
        
        //int headHeight = nameBlockPointer.getHeight();
        int headHeight = getblockcount();
        
        // Note: this will fail if our head is at a different height than the API server's.
        // TODO: try again if the value is bad.
        int targetNameHeight = getExpiresInOfName(name) + headHeight - 36000;
        
        System.out.println("Name is at block height: " + targetNameHeight);
        
        // TODO: test all of these cases
        System.out.println("Making sure the block is unexpired and confirmed...");
        
        /*
        StoredBlock nameSBlock = nameKit.store().get(targetNameBlockHash);
        if (nameSBlock == null) {
            System.out.println("Nonexistent or unknown block!");
            return;
        }
        */
        
        if (targetNameHeight < 1) {
            System.out.println("Nonpositive block height; not trustworthy!");
            return null;
        }
        
        int nameBlockConfirmations = headHeight - targetNameHeight;
        
        if (nameBlockConfirmations < 12) {
            System.out.println("Block does not yet have 12 confirmations; not trustworthy!");
            return null;
        }
        else if (nameBlockConfirmations >= 36000) {
            System.out.println("Block has expired; not trustworthy!");
            return null;
        }
        
        // TODO: remove this
        /*
        while (nameBlockPointer.getHeight() != targetNameHeight) {
            nameBlockPointer = nameBlockPointer.getPrev(kit.store());
        }
        
        // nameBlockPointer now holds the StoredBlock for the requested name.
        
        Sha256Hash targetNameBlockHash = nameBlockPointer.getHeader().getHash();
        */
        Sha256Hash targetNameBlockHash = getblockhash(targetNameHeight);
        
        System.out.println("Found StoredBlock header for the requested height.");
        
        System.out.println("Creating name wallet...");
        
        SpecificNameLookupWallet nameWallet = new SpecificNameLookupWallet(context);
        try {
            nameWallet.addWatchedTag(name);
        } catch (UnsupportedEncodingException e) {
            System.out.println("ERROR: unsupported encoding in name");
            return null;
        }
        
        System.out.println("Creating name kit...");
        
        CustomWalletAppKit nameKit = new CustomWalletAppKit(context, new File("."), filePrefix, nameWallet, kit.store(), namePeerGroup) {
        
            // Run nameKit's startUp() method in the current thread to avoid latency
            @Override
            protected Executor executor() {
                return MoreExecutors.directExecutor();
            }
        
            /*
            @Override
            protected void onSetupCompleted() {
                vPeerGroup.setMinRequiredProtocolVersion(netParams.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER));
            }
            */
        };
        
        nameKit.startAsync();
        nameKit.awaitRunning();
        
        System.out.println("Constructing name lookup request...");
        
        GetDataMessage filterRequest = new GetDataMessage(netParams);
        filterRequest.addFilteredBlock(targetNameBlockHash);
        
        String resultNameValue = null;
        Sha256Hash resultTxid = null;
        Address resultAddress = null;
        
        NameListener resultListener = new NameListener(name, targetNameBlockHash);
        
        nameKit.peerGroup().addBlocksDownloadedEventListener(MoreExecutors.directExecutor(), resultListener);
        
        Peer nameDownloadPeer = null;
        
        while (nameDownloadPeer == null) {
        
            System.out.println("Finding a peer that supports bloom filters...");
            
            nameKit.peerGroup().waitForPeersOfVersion(1, netParams.getProtocolVersionNum(NetworkParameters.ProtocolVersion.BLOOM_FILTER));
        
            nameDownloadPeer = nameKit.peerGroup().getDownloadPeer();
            
        }
        
        System.out.println("Sending name lookup request...");
        
        // TODO: getDownloadPeer() sometimes returns a null pointer.
        // Shoule be fixed, need to test.
        nameDownloadPeer.sendMessage(filterRequest);
        
        System.out.println("Waiting for response...");
        
        while( ! resultListener.getReady()) {
            Thread.sleep(1);
        }
        
        // Prevent leaks, Richard Nixon style!  (Actually, prevent *memory* leaks.)
        nameKit.peerGroup().removeWallet(nameWallet);
        nameKit.chain().removeWallet(nameWallet);
        nameKit.peerGroup().removeBlocksDownloadedEventListener(resultListener);

        return new NameData(name, resultListener.getValue(), resultListener.getTxid(), resultListener.getAddress(), targetNameHeight - headHeight + 36000);
    }
    
    // This method produces untrustworthy output, and should not be used for anything critical.
    protected int getExpiresInOfName(String name) throws MalformedURLException, IOException{
        URL nameUrl = new URL("https://namecoin.webbtc.com/name/" + name + ".json");
        
        ObjectMapper mapper = new ObjectMapper();
        
        mapper.registerModule(new RpcClientModule(netParams));
        
        NameData untrustedNameData = mapper.readValue(nameUrl, NameData.class);
        
        int expiresIn = untrustedNameData.getExpires_in();
        
        return expiresIn;
    }
    
    protected class NameListener implements BlocksDownloadedEventListener {
        
        String name;
        Sha256Hash blockHash;
        
        String resultValue;
        Sha256Hash resultTxid;
        Address resultAddress;
        
        boolean ready = false;
    
        public NameListener(String name, Sha256Hash blockHash) {
            this.name = name;
            this.blockHash = blockHash;
            
            this.resultValue = null;
            this.resultTxid = null;
            this.resultAddress = null;
            
            this.ready = false;
        }
    
        public String getValue() {
            return this.resultValue;
        }
        
        public Sha256Hash getTxid() {
            return this.resultTxid;
        }
        
        public Address getAddress() {
            return this.resultAddress;
        }
        
        public boolean getReady() {
            return this.ready;
        }
    
        public void onBlocksDownloaded(Peer peer, Block block, FilteredBlock filteredBlock, int blocksLeft) {
            //System.out.println("block: " + block.toString());
            if (filteredBlock != null) {
                System.out.println("filteredBlock: " + filteredBlock.toString());
                
                if (! filteredBlock.getHash().equals(blockHash)) {
                    System.out.println("Not the block we're looking for!\n");
                    return;
                }
                
                System.out.println("Checking the transactions...");
                
                for (Sha256Hash hash : filteredBlock.getTransactionHashes()) {
                    Transaction tx = filteredBlock.getAssociatedTransactions().get(hash);
                    if (tx != null) {
                        for (TransactionOutput output : tx.getOutputs()) {
                            try {
                                Script scriptPubKey = output.getScriptPubKey();
                                NameScript ns = new NameScript(scriptPubKey);
                                if(ns.isNameOp() && ns.isAnyUpdate() && new String(ns.getOpName().data, "ISO-8859-1").equals(name)) {
                                    System.out.println("Found requested name operation.");
                                    try {
                                        this.resultValue = new String(ns.getOpValue().data, "ISO-8859-1");
                                        this.resultTxid = tx.getHash();
                                        System.out.println("Name address: " + ns.getAddress().toString());
                                        this.resultAddress = ns.getAddress().getToAddress(netParams);
                                        this.ready = true;
                                    } catch (UnsupportedEncodingException e) {
                                        System.out.println("ERROR: Name value wasn't in supported encoding.");
                                        return;
                                    }
                                    
                                    System.out.println("Read name value.");
                                    return;
                                }
                            } catch (ScriptException e) {
                                System.out.println("ScriptException while checking name transactions: " + e.toString());
                                continue;
                            } catch (UnsupportedEncodingException e) {
                                continue;
                            }
                        }
                    }
                }
            }
        }
    }

}
