package cloudcode.krakenfutures.spotfinder;

import cloudcode.krakenfutures.weblayer.KrakenSpotConfiguration;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.knowm.xchange.kraken.dto.trade.KrakenOrderFlags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Rule;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.ROCIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class SpotTradingStrategy {

    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;

    ExecutorService myExecutor = Executors.newFixedThreadPool(10);

    public void executor(BigDecimal originalAmount) {
        CompletableFuture.runAsync(() -> {
            try {
                findInstrumentsAll(originalAmount);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void findInstrumentsAll(BigDecimal originalAmount) throws IOException {
        List<String> assets = new ArrayList<>();
        List<Map.Entry<String, KrakenTicker>> assetMap = krakenSpotConfiguration.getKrakenAssets().entrySet().stream().filter(tickerEntry -> tickerEntry.getValue().getClose().getPrice().compareTo(BigDecimal.ONE) < 0)
                .filter(tickerEntry -> tickerEntry.getKey().endsWith("USD"))
                .collect(Collectors.toList());
        List<String> result = assetMap.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        result.forEach(s -> {
            String asset = s.replace("USD", "").trim();
            Instrument instrument = new CurrencyPair(asset, "USD");
            CompletableFuture.runAsync(() -> {
                try {
                    checkAndPlaceOrder(instrument, originalAmount);
                } catch (IOException | InterruptedException e) {
                    System.out.println("Failed for: " + asset);
                    throw new RuntimeException(e);
                }
            }, myExecutor);

        });
    }

    public void checkAndPlaceOrder(Instrument instrument, BigDecimal originalAmount) throws IOException, InterruptedException {
        BarSeries series = createBarSeries(instrument);
        boolean isSatisfied = isBuySatisfied(series, instrument, originalAmount);
        if (isSatisfied) {
            System.out.println("Condition Satisfied for Instrument:" + instrument.getBase());
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            Num price = closePrice.getValue(series.getEndIndex());
            String orderId = placeSIP(instrument, price);
        }

    }

    public BarSeries createBarSeries(Instrument instrument) throws InterruptedException {

        BarSeries series = new BaseBarSeriesBuilder().withMaxBarCount(Integer.MAX_VALUE).build();

        try {
            KrakenOHLCs krakenOHLCs = getOhlc(instrument);
            for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
                BaseBar bar = new BaseBar(Duration.ofMinutes(1440), ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()), ZoneId.systemDefault()), krakenOHLC.getOpen(), krakenOHLC.getHigh(), krakenOHLC.getLow(), krakenOHLC.getClose(), krakenOHLC.getVolume());
                series.addBar(bar);

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return series;
    }

    public KrakenOHLCs getOhlc(Instrument instrument) throws IOException {

        return krakenSpotConfiguration.getAssetsOHLCs(instrument);

    }

    public boolean isBuySatisfied(BarSeries series, Instrument instrument, BigDecimal originalAmount) throws IOException {
        if (series == null) {
            throw new IllegalArgumentException("Series cannot be null");
        }
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        MACDIndicator macd = new MACDIndicator(closePrice);

        EMAIndicator emaMacd = new EMAIndicator(macd, 9);

        RSIIndicator rsiIndicator = new RSIIndicator(closePrice, 14);

        ROCIndicator rocIndicator = new ROCIndicator(closePrice, 9);
        Rule entryRule = new UnderIndicatorRule(rsiIndicator, 20).and(new UnderIndicatorRule(rocIndicator, 0));
        return entryRule.isSatisfied(series.getEndIndex());

    }

    public String placeSIP(Instrument instrument, Num price) throws IOException {
        ExchangeSpecification spec = new KrakenExchange().getDefaultExchangeSpecification();
        spec.setSecretKey("qJ+TcfyXWA36m3k5SAX2KKdyWYV5r6ID1tqrpUX3Tn06O4v7T2gQ62F16oHpkNHcV0AVSx6I1ucTjwxMpH46lQ==");
        spec.setApiKey("RbnDnXwmFmdZhhfia3+Z7LFceefn1ZWeoYzpB8n2nGX7bDX8iuKMg1UI");
        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(spec);
        CurrencyPair currencyPair = new CurrencyPair(instrument.getBase(), instrument.getCounter());
        String orderId = "";
        try {
            BigDecimal volume = krakenSpotConfiguration.getKrakenAssetPairs(currencyPair).getAssetPairMap().values().stream().findFirst().get().getOrderMin();
            orderId = exchange.getTradeService()
                    .placeLimitOrder(new LimitOrder.Builder(Order.OrderType.BID, instrument)
                            .limitPrice(BigDecimal.valueOf(price.doubleValue()))
                            .flag(KrakenOrderFlags.POST)
                            .originalAmount(volume)
                            .build());
        } catch (Exception e) {
            System.out.println("Failed to place order for " + instrument.getBase().toString() + " " + e.getMessage());
        }
        System.out.println("Order Placed : " + orderId);
        return orderId;

    }

}
