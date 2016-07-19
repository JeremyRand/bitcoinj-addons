package org.consensusj.namecoin.jsonrpc.rpcserver;

import com.msgilligan.bitcoinj.rpcserver.BitcoinJsonRpc;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.store.BlockStoreException;

import org.consensusj.namecoin.jsonrpc.pojo.NameData;

/**
 * Standard Bitcoin JSON-RPC service
 */
public interface NamecoinJsonRpc extends BitcoinJsonRpc {
    NameData name_show(String name) throws Exception;
    NameData name_show_at_height(String name, int height) throws Exception;
}
