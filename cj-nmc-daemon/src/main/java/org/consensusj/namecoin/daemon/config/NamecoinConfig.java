package org.consensusj.namecoin.daemon.config;

import com.fasterxml.jackson.databind.Module;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Spring configuration for namecoinj, Namecoin services, and JSON-RPC server
 */
@Configuration
public class NamecoinConfig {
    @Bean
    public NetworkParameters networkParameters() {
        // TODO: We may also want to make this set from a configuration string
        // so a binary release can be configure via external string parameters
        // and NetworkParameters.fromID()
        return NamecoinMainNetParams.get();
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
    public NameLookupService nameLookupService(NetworkParameters params/*, PeerDiscovery peerDiscovery*/) {
        //return new PeerGroupService(params, peerDiscovery);
        return new NameLookupService(params);
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
}
