package com.krakenfutures.wastebot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataServiceRaw;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service to retrieve OHLC (Open-High-Low-Close) candlestick data from Kraken spot market.
 * This service is designed for backtesting purposes to support future trading strategy development.
 * 
 * Kraken OHLC intervals: 1m, 5m, 15m, 1h, 4h, 1D, 1W, 1M
 */
@Service
public class KrakenOHLCService {

    private static final Logger logger = LoggerFactory.getLogger(KrakenOHLCService.class);

    // Kraken OHLC interval definitions (in minutes)
    // Possible values: [1, 5, 15, 30, 60, 240, 1440, 10080, 21600]
    public enum Interval {
        ONE_MINUTE(1, "1m"),
        FIVE_MINUTES(5, "5m"),
        FIFTEEN_MINUTES(15, "15m"),
        THIRTY_MINUTES(30, "30m"),
        ONE_HOUR(60, "1h"),
        FOUR_HOURS(240, "4h"),
        ONE_DAY(1440, "1D"),
        ONE_WEEK(10080, "1W"),
        FIFTEEN_DAYS(21600, "15D");

        private final int minutes;
        private final String krakenCode;

        Interval(int minutes, String krakenCode) {
            this.minutes = minutes;
            this.krakenCode = krakenCode;
        }

        public int getMinutes() {
            return minutes;
        }

        public String getKrakenCode() {
            return krakenCode;
        }

        public static Interval fromString(String interval) {
            // Try parsing as integer first
            try {
                int minutes = Integer.parseInt(interval);
                for (Interval i : values()) {
                    if (i.minutes == minutes) {
                        return i;
                    }
                }
            } catch (NumberFormatException e) {
                // Not an integer, try string matching
            }
            
            // Try matching by code or name
            for (Interval i : values()) {
                if (i.krakenCode.equalsIgnoreCase(interval) || i.name().equalsIgnoreCase(interval)) {
                    return i;
                }
            }
            return ONE_HOUR; // Default to 1 hour
        }
    }

    private Exchange krakenExchange;

    private Exchange getKrakenExchange() {
        if (krakenExchange == null) {
            krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class.getName());
        }
        return krakenExchange;
    }

    /**
     * Get OHLC data for a trading pair.
     * 
     * @param pair     The currency pair (e.g., BTC/USD)
     * @param interval The OHLC interval (1m, 5m, 15m, 1h, 4h, 1D, 1W, 1M)
     * @param since    Unix timestamp in seconds (null for last 720 candles)
     * @return List of OHLC data points
     */
    public List<OHLCData> getOHLCData(CurrencyPair pair, Interval interval, Long since) throws IOException {
        MarketDataService marketDataService = getKrakenExchange().getMarketDataService();
        KrakenMarketDataServiceRaw krakenService = (KrakenMarketDataServiceRaw) marketDataService;

        // Convert pair to Kraken format (e.g., BTC/USD -> XBT/USD)
        var krakenPair = convertToKrakenPair(pair);

        KrakenOHLCs krakenOHLCs = krakenService.getKrakenOHLC(krakenPair, interval.getMinutes(), since);

        List<OHLCData> ohlcDataList = new ArrayList<>();
        for (KrakenOHLC ohlc : krakenOHLCs.getOHLCs()) {
            ohlcDataList.add(convertToOHLCData(ohlc));
        }

        logger.info("Retrieved {} OHLC candles for {} with interval {}", 
                ohlcDataList.size(), pair, interval.getKrakenCode());

        return ohlcDataList;
    }

    /**
     * Get OHLC data for the past year.
     * 
     * @param pair     The currency pair (e.g., BTC/USD)
     * @param interval The OHLC interval
     * @return List of OHLC data points for the past year
     */
    public List<OHLCData> getOHLCDataForPastYear(CurrencyPair pair, Interval interval) throws IOException {
        // Calculate Unix timestamp for 1 year ago
        long oneYearAgo = Instant.now().minusSeconds(365L * 24 * 60 * 60).getEpochSecond();
        return getOHLCData(pair, interval, oneYearAgo);
    }

    /**
     * Get OHLC data for a custom date range.
     * 
     * @param pair        The currency pair
     * @param interval    The OHLC interval
     * @param startTime  Start time as LocalDateTime
     * @param endTime    End time as LocalDateTime
     * @return List of OHLC data points
     */
    public List<OHLCData> getOHLCDataForDateRange(CurrencyPair pair, Interval interval, 
            LocalDateTime startTime, LocalDateTime endTime) throws IOException {
        
        long startTimestamp = startTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        long endTimestamp = endTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        
        return getOHLCData(pair, interval, startTimestamp);
    }

    /**
     * Get the latest OHLC candle for a pair (most recent data point).
     */
    public OHLCData getLatestOHLC(CurrencyPair pair, Interval interval) throws IOException {
        List<OHLCData> data = getOHLCData(pair, interval, null);
        if (data.isEmpty()) {
            return null;
        }
        return data.get(data.size() - 1);
    }

    /**
     * Convert Kraken OHLC to our OHLCData object.
     */
    private OHLCData convertToOHLCData(KrakenOHLC ohlc) {
        return new OHLCData(
                ohlc.getTime(),
                ohlc.getOpen(),
                ohlc.getHigh(),
                ohlc.getLow(),
                ohlc.getClose(),
                ohlc.getVwap(),
                ohlc.getVolume(),
                (int) ohlc.getCount()
        );
    }

    /**
     * Convert standard CurrencyPair to Kraken pair format.
     * Kraken uses XBT instead of BTC.
     */
    private CurrencyPair convertToKrakenPair(CurrencyPair pair) {
        String base = pair.getBase().getCurrencyCode();
        String quote = pair.getCounter().getCurrencyCode();
        
        // Kraken uses XBT instead of BTC
        if (base.equals("BTC")) {
            base = "XBT";
        }
        return new CurrencyPair(base, quote);
    }

    /**
     * Data class to hold OHLC candlestick information.
     */
    public static class OHLCData {
        private final long timestamp;
        private final BigDecimal open;
        private final BigDecimal high;
        private final BigDecimal low;
        private final BigDecimal close;
        private final BigDecimal vwap;
        private final BigDecimal volume;
        private final int trades;

        public OHLCData(long timestamp, BigDecimal open, BigDecimal high, BigDecimal low, 
                BigDecimal close, BigDecimal vwap, BigDecimal volume, int trades) {
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.vwap = vwap;
            this.volume = volume;
            this.trades = trades;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public BigDecimal getOpen() {
            return open;
        }

        public BigDecimal getHigh() {
            return high;
        }

        public BigDecimal getLow() {
            return low;
        }

        public BigDecimal getClose() {
            return close;
        }

        public BigDecimal getVwap() {
            return vwap;
        }

        public BigDecimal getVolume() {
            return volume;
        }

        public int getTrades() {
            return trades;
        }

        public LocalDateTime getDateTime() {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
        }

        @Override
        public String toString() {
            return String.format("OHLC[timestamp=%d, open=%s, high=%s, low=%s, close=%s, volume=%s]",
                    timestamp, open, high, low, close, volume);
        }
    }
}