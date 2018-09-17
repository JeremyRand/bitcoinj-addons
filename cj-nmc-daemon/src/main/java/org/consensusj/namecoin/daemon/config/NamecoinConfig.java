package org.consensusj.namecoin.daemon.config;

import com.fasterxml.jackson.databind.Module;
import com.googlecode.jsonrpc4j.spring.JsonServiceExporter;
import com.msgilligan.bitcoinj.json.conversion.RpcServerModule;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.consensusj.namecoin.jsonrpc.rpcserver.NamecoinJsonRpc;
import org.libdohj.params.NamecoinMainNetParams;
import org.namecoin.bitcoinj.spring.service.NameLookupService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public Module bitcoinJMapper() {
        return new RpcServerModule();
    }

    @Bean
    public NameLookupService nameLookupService(NetworkParameters params/*, PeerDiscovery peerDiscovery*/) {
        //return new PeerGroupService(params, peerDiscovery);
        return new NameLookupService(params);
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
