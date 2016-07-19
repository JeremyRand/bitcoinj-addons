package org.namecoin.bitcoinj.spring.service;

import com.msgilligan.bitcoinj.json.pojo.ServerInfo;
import com.msgilligan.namecoinj.json.pojo.NameData;
import org.namecoin.bitcoinj.rpcserver.NamecoinJsonRpc;

import org.libdohj.names.NameLookupByBlockHeight;
import org.libdohj.names.NameLookupLatest;
import org.libdohj.names.NameTransactionUtils;
import org.libdohj.script.NameScript;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.LevelDBBlockStore;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;

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
    
    //protected NameLookupByBlockHashOneFullBlock lookupByHash;
    protected NameLookupByBlockHeight lookupByHeight;
    protected NameLookupLatest lookupLatest;
    
    private int timeOffset = 0;
    private BigDecimal difficulty = new BigDecimal(0);

    @Inject
    public NameLookupService(NetworkParameters params, Context context, WalletAppKit kit, NameLookupByBlockHeight lookupByHeight, NameLookupLatest lookupLatest /*,
                       PeerDiscovery peerDiscovery */) {
        this.netParams = params;
        this.context = context;
        
        this.filePrefix = "LibdohjNameLookupDaemon";
        
        this.kit = kit;
        
        this.lookupByHeight = lookupByHeight;
        this.lookupLatest = lookupLatest;
    }

    @PostConstruct
    public void start() {
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
