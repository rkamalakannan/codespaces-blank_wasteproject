# region imports
# AlgorithmImports is provided by the QuantConnect LEAN runtime.
# It is NOT available in standard Python environments — this is intentional.
# To run locally with LEAN CLI: `lean backtest quantconnect/`
# To run in the cloud: paste into the QuantConnect Algorithm Lab.
from AlgorithmImports import *  # noqa: F401, F403  (LEAN runtime import)
# endregion

# =============================================================================
# Combined Arbitrage + Momentum Strategy — QuantConnect LEAN Python
# =============================================================================
#
# Mirrors the Java implementation in:
#   src/main/java/com/krakenfutures/wastebot/service/ScheduledTradingService.java
#   src/main/java/com/krakenfutures/wastebot/service/MomentumTrendStrategy.java
#
# STRATEGY LOGIC (same as "combined" mode in the Java bot):
#   1. ARBITRAGE SIGNAL — compare spot vs perpetual-futures price.
#      - If spot > futures by >= MIN_SPREAD_PCT  → arbitrage says LONG futures
#      - If futures > spot by >= MIN_SPREAD_PCT  → arbitrage says SHORT futures
#      - Spread too small                         → skip (no edge after fees)
#
#   2. MOMENTUM CONFIRMATION — EMA(20) crossover on hourly closes.
#      - Previous bar BELOW EMA, current bar ABOVE EMA → momentum LONG
#      - Previous bar ABOVE EMA, current bar BELOW EMA → momentum SHORT
#      - No crossover                                   → no signal
#
#   3. AGREEMENT GATE — only trade when both agree on direction.
#      Conflict or missing signal → skip.
#
# RISK MANAGEMENT:
#   - Stop-loss  : entry ± ATR(14) × ATR_STOP_MULT  (default 2×)
#   - Take-profit: entry ± risk × RISK_REWARD        (default 2:1)
#   - Max concurrent positions: MAX_POSITIONS (default 3)
#   - Min time between re-entry on same symbol: COOLDOWN_HOURS (default 1h)
#
# ASSETS:
#   Spot  — Coinbase (GDAX) crypto spot  — used for spread & indicator data
#   Perp  — Binance Crypto Future        — traded instrument
#
# HOW TO USE IN QUANTCONNECT CLOUD:
#   1. Create a new Python project in the Algorithm Lab.
#   2. Paste this file as main.py.
#   3. Set the backtest parameters via the CONFIGURATION block below.
#   4. Run backtest from 2021-01-01 onwards (Binance perp data availability).
# =============================================================================


# ---------------------------------------------------------------------------
# CONFIGURATION — tweak these without touching the logic
# ---------------------------------------------------------------------------

# Assets to trade (base currency; quote is always USD)
ACTIVE_ASSETS = ["BTC", "ETH", "SOL"]

# Spread threshold: minimum abs(spot - futures) / futures as a percentage
# Must cover round-trip fees (~0.1% Binance futures + ~0.1% spot = ~0.2%)
MIN_SPREAD_PCT = 0.5          # 0.5%

# EMA period for trend identification (number of 1h bars)
EMA_PERIOD = 20

# ATR period for volatility / stop sizing (number of 1h bars)
ATR_PERIOD = 14

# ATR multiplier for stop-loss distance from entry
ATR_STOP_MULT = 2.0

# Risk/reward ratio for take-profit
# take_profit distance = stop_loss distance × RISK_REWARD
RISK_REWARD = 2.0

# Position sizing: fraction of portfolio to risk per trade
POSITION_SIZE_PCT = 0.05      # 5% of portfolio per trade

# Maximum concurrent open positions across all assets
MAX_POSITIONS = 3

# Minimum hours between re-entries on the same symbol (rate-limiting)
COOLDOWN_HOURS = 1

# Hourly bar resolution for indicators
BAR_RESOLUTION = Resolution.Hour

# ---------------------------------------------------------------------------


class CombinedArbitrageMomentumAlgorithm(QCAlgorithm):
    """
    Combined spot-futures arbitrage + EMA momentum strategy for crypto.

    Per-asset state is stored in AssetState objects keyed by base currency.
    All indicator management, signal generation and order placement is
    delegated to helper methods to keep OnData() readable.
    """

    def Initialize(self):
        # ----- Backtest window -----
        self.SetStartDate(2022, 1, 1)
        self.SetEndDate(2024, 1, 1)
        self.SetCash(10_000)

        # ----- Brokerage / fee model -----
        self.SetBrokerageModel(BrokerageName.Binance, AccountType.Margin)

        # ----- Per-asset state -----
        self._assets: dict[str, AssetState] = {}

        for base in ACTIVE_ASSETS:
            self._setup_asset(base)

        # ----- Warm-up: enough bars for EMA + ATR to be ready -----
        warmup_bars = max(EMA_PERIOD, ATR_PERIOD) + 5
        self.SetWarmUp(warmup_bars, BAR_RESOLUTION)

        self.Log(f"[INIT] Combined Arbitrage+Momentum strategy initialized. "
                 f"Assets={ACTIVE_ASSETS}, EMA={EMA_PERIOD}, ATR={ATR_PERIOD}, "
                 f"MinSpread={MIN_SPREAD_PCT}%, ATRMult={ATR_STOP_MULT}, RR={RISK_REWARD}")

    # ------------------------------------------------------------------
    # Setup
    # ------------------------------------------------------------------

    def _setup_asset(self, base: str):
        """Subscribe to spot + perp data and create indicators for one asset."""
        pair = f"{base}USD"

        # Spot — Coinbase (GDAX) — for spread calculation and indicator data
        # QC provides Coinbase crypto data under the "GDAX" market
        spot_symbol = self.AddCrypto(pair, BAR_RESOLUTION, Market.GDAX).Symbol

        # Perpetual futures — Binance — the instrument we actually trade
        # AddCryptoFuture adds the perpetual contract for the given pair
        try:
            perp_symbol = self.AddCryptoFuture(pair, BAR_RESOLUTION, Market.Binance).Symbol
        except Exception as e:
            self.Log(f"[SETUP] {base} — Could not add CryptoFuture ({e}). "
                     f"Falling back to spot-only (no perp trading).")
            perp_symbol = None

        # EMA and ATR registered on the SPOT symbol (reliable OHLC data)
        ema = self.EMA(spot_symbol, EMA_PERIOD, BAR_RESOLUTION)
        atr = self.ATR(spot_symbol, ATR_PERIOD, MovingAverageType.Simple, BAR_RESOLUTION)

        self._assets[base] = AssetState(
            base=base,
            spot_symbol=spot_symbol,
            perp_symbol=perp_symbol,
            ema=ema,
            atr=atr,
        )
        self.Log(f"[SETUP] {base} — spot={spot_symbol}, perp={perp_symbol}")

    # ------------------------------------------------------------------
    # Main event loop
    # ------------------------------------------------------------------

    def OnData(self, data: Slice):
        if self.IsWarmingUp:
            return

        open_positions = sum(
            1 for s in self._assets.values() if self._has_open_position(s)
        )

        for base, state in self._assets.items():
            try:
                self._process_asset(state, data, open_positions)
            except Exception as e:
                self.Error(f"[CYCLE] {base} — unhandled exception: {e}")

    # ------------------------------------------------------------------
    # Per-asset processing
    # ------------------------------------------------------------------

    def _process_asset(self, state: "AssetState", data: Slice, open_positions: int):
        base = state.base

        # Skip if already in a position for this asset
        if self._has_open_position(state):
            return

        # Global position cap
        if open_positions >= MAX_POSITIONS:
            self.Debug(f"[{base}] Max positions ({MAX_POSITIONS}) reached. Skipping.")
            return

        # Per-asset cooldown (rate-limiting, mirrors MIN_TRADE_INTERVAL_MS)
        if state.last_trade_time is not None:
            elapsed_hours = (self.Time - state.last_trade_time).total_seconds() / 3600
            if elapsed_hours < COOLDOWN_HOURS:
                self.Debug(f"[{base}] In cooldown ({elapsed_hours:.1f}h / {COOLDOWN_HOURS}h). Skipping.")
                return

        # Indicators must be ready
        if not state.ema.IsReady or not state.atr.IsReady:
            self.Debug(f"[{base}] Indicators not ready yet.")
            return

        # Need price data for both spot and perp in this slice
        if not data.ContainsKey(state.spot_symbol):
            return
        if state.perp_symbol is None or not data.ContainsKey(state.perp_symbol):
            # No perp data — can't trade but can still log
            self.Debug(f"[{base}] No perp data in slice. Skipping.")
            return

        spot_bar = data[state.spot_symbol]
        perp_bar = data[state.perp_symbol]

        spot_price = float(spot_bar.Close)
        perp_price = float(perp_bar.Close)

        # ---------------------------------------------------------------
        # STEP 1: Arbitrage signal from spot-futures spread
        # ---------------------------------------------------------------
        if perp_price == 0:
            return

        spread_pct = (spot_price - perp_price) / perp_price * 100.0

        if abs(spread_pct) < MIN_SPREAD_PCT:
            self.Debug(f"[{base}] Spread {spread_pct:.3f}% < min {MIN_SPREAD_PCT}%. No arb signal.")
            return

        arb_direction = "LONG" if spread_pct > 0 else "SHORT"
        self.Log(f"[{base}] Spot={spot_price:.4f}, Perp={perp_price:.4f}, "
                 f"Spread={spread_pct:.3f}% → Arb signal: {arb_direction}")

        # ---------------------------------------------------------------
        # STEP 2: EMA crossover confirmation
        # ---------------------------------------------------------------
        ema_value = float(state.ema.Current.Value)
        prev_close = float(state.prev_close) if state.prev_close is not None else spot_price

        current_above = spot_price > ema_value
        previous_above = prev_close > ema_value

        # Update previous close for next bar
        state.prev_close = spot_price

        if current_above and not previous_above:
            momentum_direction = "LONG"   # bullish crossover
        elif not current_above and previous_above:
            momentum_direction = "SHORT"  # bearish crossover
        else:
            self.Debug(f"[{base}] No EMA crossover. Price {'above' if current_above else 'below'} "
                       f"EMA({EMA_PERIOD})={ema_value:.4f}. No momentum signal.")
            return

        self.Log(f"[{base}] EMA({EMA_PERIOD})={ema_value:.4f}, "
                 f"PrevClose={prev_close:.4f}, CurClose={spot_price:.4f} "
                 f"→ Momentum signal: {momentum_direction}")

        # ---------------------------------------------------------------
        # STEP 3: Agreement gate
        # ---------------------------------------------------------------
        if arb_direction != momentum_direction:
            self.Log(f"[{base}] CONFLICT: Arb={arb_direction}, Momentum={momentum_direction}. Skipping.")
            return

        self.Log(f"[{base}] AGREEMENT: Both signals = {arb_direction}. Placing trade.")

        # ---------------------------------------------------------------
        # STEP 4: Compute stop-loss and take-profit from ATR
        # ---------------------------------------------------------------
        atr_value = float(state.atr.Current.Value)
        stop_distance = atr_value * ATR_STOP_MULT
        tp_distance   = stop_distance * RISK_REWARD

        if arb_direction == "LONG":
            entry_price  = perp_price
            stop_price   = entry_price - stop_distance
            target_price = entry_price + tp_distance
        else:  # SHORT
            entry_price  = perp_price
            stop_price   = entry_price + stop_distance
            target_price = entry_price - tp_distance

        self.Log(f"[{base}] {arb_direction} Entry≈{entry_price:.4f}, "
                 f"SL={stop_price:.4f} (-{stop_distance:.4f}), "
                 f"TP={target_price:.4f} (+{tp_distance:.4f}), "
                 f"ATR={atr_value:.4f}")

        # ---------------------------------------------------------------
        # STEP 5: Size the position and place orders
        # ---------------------------------------------------------------
        portfolio_value = float(self.Portfolio.TotalPortfolioValue)
        risk_dollars     = portfolio_value * POSITION_SIZE_PCT
        quantity         = risk_dollars / entry_price

        if quantity <= 0:
            self.Log(f"[{base}] Computed quantity <= 0. Skipping.")
            return

        # Round to 6 dp (sufficient for most crypto on Binance)
        quantity = round(quantity, 6)

        if arb_direction == "LONG":
            self.MarketOrder(state.perp_symbol, quantity)
            self.StopMarketOrder(state.perp_symbol, -quantity, stop_price)
            # Take-profit via a limit order on the close side
            self.LimitOrder(state.perp_symbol, -quantity, target_price)
        else:
            self.MarketOrder(state.perp_symbol, -quantity)
            self.StopMarketOrder(state.perp_symbol, quantity, stop_price)
            self.LimitOrder(state.perp_symbol, quantity, target_price)

        state.last_trade_time = self.Time
        state.last_direction  = arb_direction

        open_positions += 1  # update local count for this cycle

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _has_open_position(self, state: "AssetState") -> bool:
        """Return True if we hold a non-zero position in the perp contract."""
        if state.perp_symbol is None:
            return False
        holding = self.Portfolio[state.perp_symbol]
        return holding.Invested

    def OnOrderEvent(self, order_event: OrderEvent):
        if order_event.Status in (OrderStatus.Filled, OrderStatus.PartiallyFilled):
            self.Log(f"[ORDER] {order_event.Symbol} {order_event.Direction} "
                     f"qty={order_event.FillQuantity:.6f} @ {order_event.FillPrice:.4f} "
                     f"status={order_event.Status}")


# ---------------------------------------------------------------------------
# Per-asset state container
# ---------------------------------------------------------------------------

class AssetState:
    """Holds all per-asset data: symbols, indicators, and trade tracking."""

    def __init__(self, base: str, spot_symbol, perp_symbol,
                 ema: ExponentialMovingAverage,
                 atr: AverageTrueRange):
        self.base         = base
        self.spot_symbol  = spot_symbol
        self.perp_symbol  = perp_symbol
        self.ema          = ema
        self.atr          = atr

        # Crossover detection needs the previous bar's close
        self.prev_close: float | None = None

        # Trade rate-limiting
        self.last_trade_time: datetime | None = None
        self.last_direction: str | None = None
