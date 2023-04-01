/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.weblayer;

import java.io.IOException;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
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

    public Ticker getKrakenSpotTicker(Instrument instrument) throws IOException {
        KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenSpotExchange.getMarketDataService();
        
        return marketDataService.getTicker(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USDT"));
    }
}
