package cloudcode.krakenfutures.models;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Candle{
    @JsonProperty("close")
    public String getClose() { 
		 return this.close; } 
    public void setClose(String close) { 
		 this.close = close; } 
    String close;
    @JsonProperty("high") 
    public String getHigh() { 
		 return this.high; } 
    public void setHigh(String high) { 
		 this.high = high; } 
    String high;
    @JsonProperty("low") 
    public String getLow() { 
		 return this.low; } 
    public void setLow(String low) { 
		 this.low = low; } 
    String low;
    @JsonProperty("open") 
    public String getMyopen() { 
		 return this.myopen; } 
    public void setMyopen(String myopen) { 
		 this.myopen = myopen; } 
    String myopen;
    @JsonProperty("time") 
    public long getTime() { 
		 return this.time; } 
    public void setTime(long time) { 
		 this.time = time; } 
    long time;
    @JsonProperty("volume") 
    public int getVolume() { 
		 return this.volume; } 
    public void setVolume(int volume) { 
		 this.volume = volume; } 
    int volume;
}
