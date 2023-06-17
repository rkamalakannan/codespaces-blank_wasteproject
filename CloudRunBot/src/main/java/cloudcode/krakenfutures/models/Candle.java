package cloudcode.krakenfutures.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Candle {
    String close;
    String high;
    String low;
    String myopen;
    long time;
    int volume;

    @JsonProperty("close")
    public String getClose() {
        return this.close;
    }

    public void setClose(String close) {
        this.close = close;
    }

    @JsonProperty("high")
    public String getHigh() {
        return this.high;
    }

    public void setHigh(String high) {
        this.high = high;
    }

    @JsonProperty("low")
    public String getLow() {
        return this.low;
    }

    public void setLow(String low) {
        this.low = low;
    }

    @JsonProperty("open")
    public String getMyopen() {
        return this.myopen;
    }

    public void setMyopen(String myopen) {
        this.myopen = myopen;
    }

    @JsonProperty("time")
    public long getTime() {
        return this.time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    @JsonProperty("volume")
    public int getVolume() {
        return this.volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }
}
