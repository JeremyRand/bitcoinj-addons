package org.namecoin.bitcoinj.rpcserver;

import com.msgilligan.bitcoinj.rpcserver.BitcoinJsonRpc;
import com.msgilligan.namecoinj.json.pojo.NameData;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.store.BlockStoreException;

/**
 * Standard Bitcoin JSON-RPC service
 */
public interface NamecoinJsonRpc extends BitcoinJsonRpc {
    NameData name_show(String name) throws Exception;
    NameData name_show_at_height(String name, int height) throws Exception;
    //Sha256Hash getblockhash(int height) throws BlockStoreException; // TODO: move this to BitcoinJsonRpc
}
