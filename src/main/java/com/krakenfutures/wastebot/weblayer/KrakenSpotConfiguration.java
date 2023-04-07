/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.weblayer;

import java.io.IOException;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenAssetPairs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.knowm.xchange.kraken.service.KrakenMarketDataServiceRaw;
import org.springframework.stereotype.Component;

/**
 *
 * @author vscode
 */
@Component
public class KrakenSpotConfiguration {

    private Exchange krakenSpotExchange = getKrakenSpotExchangeSettings();

    public Exchange getKrakenSpotExchangeSettings() {
        return ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
    }

    public KrakenTicker getKrakenSpotTicker(Instrument instrument) throws IOException {
        KrakenMarketDataServiceRaw marketDataService = (KrakenMarketDataServiceRaw) krakenSpotExchange
                .getMarketDataService();

        return marketDataService.getKrakenTicker(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USDT"));
    }

    public KrakenAssetPairs getKrakenAssetPairs(Instrument instrument) throws IOException {
        KrakenMarketDataServiceRaw krakenMarketDataService = (KrakenMarketDataServiceRaw) krakenSpotExchange
                .getMarketDataService();

        KrakenAssetPairs krakenAssetPairs = krakenMarketDataService.getKrakenAssetPairs();
        
        return krakenAssetPairs;
    }
}
