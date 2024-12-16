
package com.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Generated;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "result",
    "tickers",
    "serverTime"
})
@Generated("jsonschema2pojo")
public class Result {

    @JsonProperty("result")
    private String result;
    @JsonProperty("tickers")
    private List<Ticker> tickers;
    @JsonProperty("serverTime")
    private String serverTime;
    @JsonIgnore
    private Map<String, Object> additionalProperties = new LinkedHashMap<String, Object>();

    @JsonProperty("result")
    public String getResult() {
        return result;
    }

    @JsonProperty("result")
    public void setResult(String result) {
        this.result = result;
    }

    @JsonProperty("tickers")
    public List<Ticker> getTickers() {
        return tickers;
    }

    @JsonProperty("tickers")
    public void setTickers(List<Ticker> tickers) {
        this.tickers = tickers;
    }

    @JsonProperty("serverTime")
    public String getServerTime() {
        return serverTime;
    }

    @JsonProperty("serverTime")
    public void setServerTime(String serverTime) {
        this.serverTime = serverTime;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }

}
