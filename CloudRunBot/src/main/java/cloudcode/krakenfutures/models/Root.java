package cloudcode.krakenfutures.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;

public class Root {
    ArrayList<Candle> candles;
    boolean more_candles;

    @JsonProperty("candles")
    public ArrayList<Candle> getCandles() {
        return this.candles;
    }

    public void setCandles(ArrayList<Candle> candles) {
        this.candles = candles;
    }

    @JsonProperty("more_candles")
    public boolean getMore_candles() {
        return this.more_candles;
    }

    public void setMore_candles(boolean more_candles) {
        this.more_candles = more_candles;
    }
}
