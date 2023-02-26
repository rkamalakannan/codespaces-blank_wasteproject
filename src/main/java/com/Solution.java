package com;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class Solution {

  public static void main(String[] args) throws IOException {

    Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
    KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange;
    LocalDateTime time = LocalDateTime.now().minusWeeks(1);
    ZoneId zoneId = ZoneId.systemDefault();
    long epoch = time.atZone(zoneId).toEpochSecond();
    KrakenOHLCs krakenOHLCs = marketDataService.getKrakenOHLC(new CurrencyPair("BTC", "USD"), 15, epoch);
  }
}
