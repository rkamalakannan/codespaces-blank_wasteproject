package ta4jexamples.bots;

import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class Solution {

  public static void main(String[] args) throws IOException {

    KrakenExchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
    KrakenMarketDataService krakenMarketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
    LocalDateTime time = LocalDateTime.now().minusWeeks(1);
    ZoneId zoneId = ZoneId.systemDefault();
    long epoch = time.atZone(zoneId).toEpochSecond();
    KrakenOHLCs krakenOHLCs = krakenMarketDataService.getKrakenOHLC(new CurrencyPair("BTC", "USD"), 15, epoch);
    Ticker ticker = krakenExchange.getMarketDataService().getTicker(new CurrencyPair("BTC", "USD"));
//            .getKr(new CurrencyPair("BTC", "USD"), 15, epoch);
    System.out.println("Inside Solution"+ ticker.toString());
    System.out.println("Inside OHLC"+ ticker);
  }
}
