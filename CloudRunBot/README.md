# Kraken Trading Bot

Lightweight, zero-framework Java trading bot for Kraken exchange.  
Supports **Spot** cross-currency arbitrage and **Futures** market data streaming with paper/live trading modes.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   TradingConfig                         │
│  (env vars: keys, mode, spot/futures toggles, params)   │
└──────────┬──────────────────────────┬───────────────────┘
           │                          │
    ┌──────▼──────┐           ┌───────▼────────┐
    │  SPOT Market │           │ FUTURES Market │
    │              │           │                │
    │ SpotTicker   │           │ FuturesTicker  │
    │  WebSocket   │           │  WebSocket     │
    │ (v2 ticker)  │           │ (v1 ticker)    │
    │      │       │           │                │
    │ Arbitrage    │           │ (Strategy      │
    │ Strategy     │           │  hooks ready)  │
    │      │       │           │                │
    │ OrderClient  │           │ Futures REST   │
    │ (WS orders)  │           │ (REST orders)  │
    └──────────────┘           └────────────────┘
```

## Quick Start

### Prerequisites
- Java 20 or higher
- Maven 3.6+

### Build
```bash
mvn clean package -q
```

### Run (Paper Trading — default, safe)
```bash
java -jar target/kraken-trading-bot-1.0.0.jar
```

### Run (Live Trading)
```bash
export KRAKEN_API_KEY=your_spot_key
export KRAKEN_API_SECRET=your_spot_secret
export TRADING_MODE=live
java -jar target/kraken-trading-bot-1.0.0.jar
```

### Run with Futures
```bash
export ENABLE_FUTURES=true
export KRAKEN_FUTURES_KEY=your_futures_key
export KRAKEN_FUTURES_SECRET=your_futures_secret
java -jar target/kraken-trading-bot-1.0.0.jar
```

## Configuration (Environment Variables)

| Variable | Default | Description |
|---|---|---|
| `TRADING_MODE` | `paper` | `paper` = log-only, `live` = real orders |
| `ENABLE_SPOT` | `true` | Enable spot market arbitrage |
| `ENABLE_FUTURES` | `false` | Enable futures market data + trading |
| `KRAKEN_API_KEY` | _(empty)_ | Kraken Spot API key |
| `KRAKEN_API_SECRET` | _(empty)_ | Kraken Spot API secret |
| `KRAKEN_FUTURES_KEY` | _(empty)_ | Kraken Derivatives API key |
| `KRAKEN_FUTURES_SECRET` | _(empty)_ | Kraken Derivatives API secret |
| `TRADE_SIZE_USD` | `50` | Trade size in USD equivalent |
| `MIN_PROFIT_PCT` | `0.10` | Minimum net profit % to trigger trade |
| `MAX_TICKER_AGE_MS` | `3000` | Max ticker staleness (ms) |
| `TRADE_COOLDOWN_MS` | `30000` | Per-asset cooldown between trades (ms) |

## Paper vs Live Trading

- **Paper mode** (default): All order operations are logged but never sent to Kraken. Ticker data is real. Safe for testing strategies.
- **Live mode**: Real orders are placed on Kraken. Requires valid API keys with trading permissions.

The mode is controlled by the `TRADING_MODE` environment variable. The bot defaults to `paper` for safety.

## Spot Arbitrage Strategy

Monitors the same crypto asset across different fiat quote currencies (USD, EUR, GBP, CAD, AUD, JPY, CHF):

1. Discovers all tradable pairs dynamically from Kraken REST API
2. Subscribes to real-time ticker via WebSocket v2 (`wss://ws.kraken.com/v2`)
3. Normalizes prices to USD using FX rates derived from stablecoin pairs
4. When one currency is cheaper (ask) and another is more expensive (bid) after fees → executes simultaneous BUY + SELL

## Futures Market Support

When `ENABLE_FUTURES=true`:

- Fetches all tradable futures instruments from Derivatives REST API
- Streams real-time futures ticker data via WebSocket (`wss://futures.kraken.com/ws/v1`)
- Includes funding rates, mark price, index price, open interest
- Order placement via authenticated Derivatives REST API (HMAC-SHA512 signing)
- Ready for custom futures strategies (hooks available in `FuturesTickerService`)

## VPS Deployment

```bash
# Build fat JAR
mvn clean package -q

# Run with low-latency JVM flags
java -server -XX:+UseZGC -XX:+AlwaysPreTouch \
     -Xms256m -Xmx512m \
     -jar target/kraken-trading-bot-1.0.0.jar
```

## Tech Stack

- **Java 20** — plain Java, no framework
- **Jackson** — JSON parsing
- **Java-WebSocket 1.5.7** — WebSocket client
- **Logback** — async logging
- **java.net.http.HttpClient** — built-in HTTP client (no external HTTP library)

## API Endpoints Used

| API | Endpoint | Purpose |
|---|---|---|
| Spot REST | `api.kraken.com/0/public/AssetPairs` | Discover tradable pairs |
| Spot WS v2 | `wss://ws.kraken.com/v2` | Real-time ticker + order execution |
| Futures REST | `futures.kraken.com/derivatives/api/v3` | Instruments, order placement |
| Futures WS v1 | `wss://futures.kraken.com/ws/v1` | Real-time futures ticker |
