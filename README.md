# 🪙 Crypto Sipper

> **Real-time Crypto Market Trend Analyser**  
> Fetches live OHLCV data from Binance via **XChange** and runs **Ta4j** technical indicators to generate BUY / SELL / HOLD signals.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Libraries Used](#libraries-used)
3. [Ta4j Indicators Explained](#ta4j-indicators-explained)
4. [REST API](#rest-api)
5. [Run Locally](#run-locally)
6. [Deploy to Railway (Free)](#deploy-to-railway-free)
7. [Project Structure](#project-structure)

---

## Architecture

```
HTTP Request
     │
     ▼
CryptoAnalysisController          ← Spring REST layer
     │
     ├─► MarketDataService         ← XChange: fetches live OHLCV from Binance
     │         │
     │         └─► Binance REST API (public, no key needed)
     │
     └─► TechnicalAnalysisService  ← Ta4j: computes indicators + signal
               │
               ├─ BarSeries        (Ta4j time-series container)
               ├─ RSIIndicator     (momentum)
               ├─ MACDIndicator    (trend + momentum)
               ├─ EMAIndicator     (trend)
               ├─ SMAIndicator     (trend)
               └─ BollingerBands   (volatility)
```

---

## Libraries Used

| Library | Version | Purpose |
|---------|---------|---------|
| **Ta4j** (`ta4j-core`) | 0.16 | Technical analysis indicators & strategy engine |
| **XChange** (`xchange-binance`) | 5.2.0 | Unified crypto exchange API — fetches live OHLCV |
| **Spring Boot** | 3.2.3 | REST API framework |
| **Lombok** | latest | Boilerplate reduction (`@Data`, `@Builder`) |

---

## Ta4j Indicators Explained

Ta4j works with a **`BarSeries`** — an ordered list of OHLCV bars.  
Each indicator is computed lazily on demand at a given bar index.

### EMA (Exponential Moving Average)
- **EMA 9** — short-term trend (reacts quickly to price changes)
- **EMA 21** — medium-term trend
- **Golden cross**: EMA9 > EMA21 → bullish signal
- **Death cross**: EMA9 < EMA21 → bearish signal

### RSI (Relative Strength Index, period 14)
- Measures momentum: `RSI = 100 − (100 / (1 + RS))`
- `RS = average gain / average loss` over 14 bars
- **< 35** → oversold (potential BUY)
- **> 65** → overbought (potential SELL)

### MACD (Moving Average Convergence/Divergence)
- **MACD line** = EMA(12) − EMA(26)
- **Signal line** = EMA(9) of MACD line
- **Histogram** = MACD line − Signal line
- Positive histogram → bullish momentum; negative → bearish

### Bollinger Bands (period 20, 2σ)
- **Middle** = SMA(20)
- **Upper** = Middle + 2 × standard deviation
- **Lower** = Middle − 2 × standard deviation
- Price near upper band → overbought; near lower band → oversold

### Signal Logic
```
BUY  when: RSI < 35  AND  EMA9 > EMA21  AND  MACD histogram > 0
SELL when: RSI > 65  AND  EMA9 < EMA21  AND  MACD histogram < 0
HOLD otherwise
```

---

## REST API

Base URL (local): `http://localhost:8080`  
Base URL (Railway): `https://<your-app>.up.railway.app`

### `GET /api/health`
Service liveness probe.
```json
{ "status": "UP", "service": "Crypto Sipper" }
```

### `GET /api/price/{symbol}`
Latest spot price from Binance.
```
GET /api/price/BTC-USDT
```
```json
{ "symbol": "BTC/USDT", "price": 67432.12000000 }
```

### `GET /api/bars/{symbol}`
Last 100 hourly OHLCV bars.
```
GET /api/bars/ETH-USDT
```
```json
[
  {
    "symbol": "ETH/USDT",
    "openTime": "2024-03-01T10:00:00Z",
    "open": 3450.00,
    "high": 3480.00,
    "low": 3440.00,
    "close": 3465.00,
    "volume": 12345.67
  },
  ...
]
```

### `GET /api/analyse/{symbol}`
Full Ta4j analysis with all indicators and a trading signal.
```
GET /api/analyse/BTC-USDT
```
```json
{
  "symbol": "BTC/USDT",
  "timestamp": "2024-03-01T14:00:00Z",
  "currentPrice": 67432.12000000,
  "ema9":  67100.45000000,
  "ema21": 66800.23000000,
  "sma50": 65000.00000000,
  "rsi14": 32.45000000,
  "macdLine":     300.22000000,
  "macdSignal":   250.10000000,
  "macdHistogram": 50.12000000,
  "bollingerUpper":  69000.00000000,
  "bollingerMiddle": 67000.00000000,
  "bollingerLower":  65000.00000000,
  "signal": "BUY",
  "signalReason": "RSI(32.45) is oversold (<35), EMA9(67100.45) > EMA21(66800.23) signals uptrend, MACD histogram(50.12) is positive — bullish confluence."
}
```

**Symbol format**: use hyphens in the URL (`BTC-USDT`, `ETH-USDT`, `SOL-USDT`).

---

## Run Locally

**Prerequisites**: Java 17+, Maven 3.8+

```bash
cd xchangepractice
./mvnw spring-boot:run
```

Test it:
```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/api/analyse/BTC-USDT
```

---

## Deploy to Railway (Free)

[Railway](https://railway.app) is a free-tier PaaS that auto-detects the `Dockerfile` and deploys on every `git push`.

### Steps

1. **Create a free Railway account** at https://railway.app

2. **Create a new project** → "Deploy from GitHub repo"

3. **Connect this repository** — Railway detects `xchangepractice/Dockerfile` automatically

4. **Set the root directory** in Railway settings to `xchangepractice`

5. **Deploy** — Railway builds the Docker image and starts the container

6. **Get your public URL** from the Railway dashboard  
   (format: `https://<random-name>.up.railway.app`)

7. **Test**:
   ```bash
   curl https://<your-app>.up.railway.app/api/health
   curl https://<your-app>.up.railway.app/api/analyse/BTC-USDT
   ```

### Environment Variables (Railway)
No environment variables are required — Binance public API needs no key.  
Railway automatically injects `PORT`; the app reads it via `${PORT:8080}`.

---

## Project Structure

```
xchangepractice/
├── Dockerfile                          ← Multi-stage Docker build
├── railway.toml                        ← Railway deployment config
├── pom.xml                             ← Maven dependencies (Ta4j, XChange)
└── src/main/java/com/hope/xchangepractice/
    ├── XchangepracticeApplication.java ← Spring Boot entry point
    ├── model/
    │   ├── CryptoBar.java              ← OHLCV bar (raw market data)
    │   └── AnalysisResult.java         ← All indicators + signal
    ├── service/
    │   ├── MarketDataService.java      ← XChange: live data from Binance
    │   └── TechnicalAnalysisService.java ← Ta4j: indicators + signal logic
    └── controller/
        └── CryptoAnalysisController.java ← REST endpoints
```
