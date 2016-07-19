package com.msgilligan.namecoinj.json.pojo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Sha256Hash;

import java.io.IOException;
import java.util.Map;

/**
 * Namecoin name data
 *
 * example format:
 * [name:d/beelin,
 * value:{"alias":"beelin.github.io"},
 * txid:5737868f7044ade9b0c04698c563955d9b49db841a4e575bc384873073b956ed,
 * address:NAPwebo2VLvGdBFC4cHrLRPS6ZXPKddx9Z,
 * expires_in:31874]
 */
public class NameData {
    private static ObjectMapper mapper = new ObjectMapper();

    private final   String name;
    private final   String value;
    private final   Map<String, Object> valueParsed;     // Deserialized from escape JSON string
    private final   Sha256Hash txid;
    private final   Integer vout;
    private final   Address address;
    private final   Integer height;
    private final   Integer expires_in;
    private final   Boolean expired;

    @JsonCreator
    public NameData(@JsonProperty("name")       String name,
                    @JsonProperty("value")      String value,
                    @JsonProperty("txid")       Sha256Hash txid,
                    @JsonProperty("vout")       Integer vout,
                    @JsonProperty("address")    Address address,
                    @JsonProperty("height")     Integer height,
                    @JsonProperty("expires_in") Integer expires_in,
                    @JsonProperty("expired")    Boolean expired) throws IOException {
        this.name = name;
        this.value = value;
        
        Map<String, Object> tempValueParsed;
        
        try {
            tempValueParsed = (Map<String, Object>) mapper.readValue(value, Map.class);
        }
        catch (JsonParseException e) {
            tempValueParsed = null;
        }
        
        this.valueParsed = tempValueParsed;
        
        this.txid = txid;
        this.vout = vout;
        this.address = address;
        this.height = height;
        this.expires_in = expires_in;
        this.expired = expired;
    }

    /**
     *
     * @return namespace/name
     */
    public String getName() {
        return name;
    }

    /**
     *
     * @return raw value
     */
    public String getValue() {
        return value;
    }
    
    /**
     *
     * @return JSONNode
     */
    public Map<String, Object> getValueParsed() {
        return valueParsed;
    }

    public Sha256Hash getTxid() {
        return txid;
    }
    
    public Integer getVout() {
        return vout;
    }

    /**
     *
     * @return Address as String (for now since bitcoinj won't allow N... addresses)
     */
    public Address getAddress() {
        return address;
    }
    
    public Integer getHeight() {
        return height;
    }

    public Integer getExpires_in() {
        return expires_in;
    }
    
    public Boolean getExpired() {
        return expired;
    }
}
