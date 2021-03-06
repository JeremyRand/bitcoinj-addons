package com.msgilligan.bitcoinj.money;

import org.javamoney.moneta.CurrencyUnitBuilder;

import javax.money.CurrencyContext;
import javax.money.CurrencyContextBuilder;
import javax.money.CurrencyQuery;
import javax.money.CurrencyUnit;
import javax.money.spi.CurrencyProviderSpi;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 *  A BitcoinCurrencyProvider based on work in the javamoney-shelter.
 *
 * @author Sean Gilligan
 * @author Werner Keil
 */
public class BitcoinCurrencyProvider implements CurrencyProviderSpi {

    final static int bitcoinFractionDigits = 8;

    // Not sure what to do here...
    private final CurrencyContext CONTEXT = CurrencyContextBuilder.of("BitcoinCurrencyContextProvider")
            .build();

    private Set<CurrencyUnit> bitcoinSet = new HashSet<>();

    public BitcoinCurrencyProvider() {
        CurrencyUnit btcUnit = CurrencyUnitBuilder.of("BTC", CONTEXT)
                .setDefaultFractionDigits(bitcoinFractionDigits)
                .build();
        bitcoinSet.add(btcUnit);
        bitcoinSet = Collections.unmodifiableSet(bitcoinSet);
    }

    @Override
    public String getProviderName(){
        return "bitcoin";
    }

    /**
     * Return a {@link CurrencyUnit} instances matching the given
     * {@link javax.money.CurrencyContext}.
     *
     * @param query the {@link javax.money.CurrencyQuery} containing the parameters determining the query. not null.
     * @return the corresponding {@link CurrencyUnit}s matching, never null.
     */
    @Override
    public Set<CurrencyUnit> getCurrencies(CurrencyQuery query){
        // only ensure BTC is the code, or it is a default query.
        if(query.getCurrencyCodes().contains("BTC") ||  query.getCurrencyCodes().isEmpty()){
            return bitcoinSet;
        }
        return Collections.emptySet();
    }

    @Override
    public boolean isCurrencyAvailable(CurrencyQuery query) {
        return !getCurrencies(query).isEmpty();
    }

}