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

/**
 *
 * @author vscode
 */
public class KrakenSpotConfiguration {

    public Ticker krakenExchangeSettings(Instrument instrument) throws IOException {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
        return marketDataService.getTicker(instrument);
    }
}
