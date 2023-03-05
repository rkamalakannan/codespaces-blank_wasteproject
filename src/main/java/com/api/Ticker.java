
package com.api;

import java.util.LinkedHashMap;
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
    "tag",
    "pair",
    "symbol",
    "markPrice",
    "vol24h",
    "volumeQuote",
    "openInterest",
    "indexPrice",
    "last",
    "lastTime",
    "lastSize",
    "suspended",
    "postOnly",
    "bid",
    "bidSize",
    "ask",
    "askSize",
    "fundingRate",
    "fundingRatePrediction",
    "open24h"
})
@Generated("jsonschema2pojo")
public class Ticker {

    @JsonProperty("tag")
    private String tag;
    @JsonProperty("pair")
    private String pair;
    @JsonProperty("symbol")
    private String symbol;
    @JsonProperty("markPrice")
    private Double markPrice;
    @JsonProperty("vol24h")
    private Integer vol24h;
    @JsonProperty("volumeQuote")
    private Integer volumeQuote;
    @JsonProperty("openInterest")
    private Integer openInterest;
    @JsonProperty("indexPrice")
    private Double indexPrice;
    @JsonProperty("last")
    private Double last;
    @JsonProperty("lastTime")
    private String lastTime;
    @JsonProperty("lastSize")
    private Integer lastSize;
    @JsonProperty("suspended")
    private Boolean suspended;
    @JsonProperty("postOnly")
    private Boolean postOnly;
    @JsonProperty("bid")
    private Double bid;
    @JsonProperty("bidSize")
    private Integer bidSize;
    @JsonProperty("ask")
    private Double ask;
    @JsonProperty("askSize")
    private Integer askSize;
    @JsonProperty("fundingRate")
    private Integer fundingRate;
    @JsonProperty("fundingRatePrediction")
    private Integer fundingRatePrediction;
    @JsonProperty("open24h")
    private Integer open24h;
    @JsonIgnore
    private Map<String, Object> additionalProperties = new LinkedHashMap<String, Object>();

    @JsonProperty("tag")
    public String getTag() {
        return tag;
    }

    @JsonProperty("tag")
    public void setTag(String tag) {
        this.tag = tag;
    }

    @JsonProperty("pair")
    public String getPair() {
        return pair;
    }

    @JsonProperty("pair")
    public void setPair(String pair) {
        this.pair = pair;
    }

    @JsonProperty("symbol")
    public String getSymbol() {
        return symbol;
    }

    @JsonProperty("symbol")
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    @JsonProperty("markPrice")
    public Double getMarkPrice() {
        return markPrice;
    }

    @JsonProperty("markPrice")
    public void setMarkPrice(Double markPrice) {
        this.markPrice = markPrice;
    }

    @JsonProperty("vol24h")
    public Integer getVol24h() {
        return vol24h;
    }

    @JsonProperty("vol24h")
    public void setVol24h(Integer vol24h) {
        this.vol24h = vol24h;
    }

    @JsonProperty("volumeQuote")
    public Integer getVolumeQuote() {
        return volumeQuote;
    }

    @JsonProperty("volumeQuote")
    public void setVolumeQuote(Integer volumeQuote) {
        this.volumeQuote = volumeQuote;
    }

    @JsonProperty("openInterest")
    public Integer getOpenInterest() {
        return openInterest;
    }

    @JsonProperty("openInterest")
    public void setOpenInterest(Integer openInterest) {
        this.openInterest = openInterest;
    }

    @JsonProperty("indexPrice")
    public Double getIndexPrice() {
        return indexPrice;
    }

    @JsonProperty("indexPrice")
    public void setIndexPrice(Double indexPrice) {
        this.indexPrice = indexPrice;
    }

    @JsonProperty("last")
    public Double getLast() {
        return last;
    }

    @JsonProperty("last")
    public void setLast(Double last) {
        this.last = last;
    }

    @JsonProperty("lastTime")
    public String getLastTime() {
        return lastTime;
    }

    @JsonProperty("lastTime")
    public void setLastTime(String lastTime) {
        this.lastTime = lastTime;
    }

    @JsonProperty("lastSize")
    public Integer getLastSize() {
        return lastSize;
    }

    @JsonProperty("lastSize")
    public void setLastSize(Integer lastSize) {
        this.lastSize = lastSize;
    }

    @JsonProperty("suspended")
    public Boolean getSuspended() {
        return suspended;
    }

    @JsonProperty("suspended")
    public void setSuspended(Boolean suspended) {
        this.suspended = suspended;
    }

    @JsonProperty("postOnly")
    public Boolean getPostOnly() {
        return postOnly;
    }

    @JsonProperty("postOnly")
    public void setPostOnly(Boolean postOnly) {
        this.postOnly = postOnly;
    }

    @JsonProperty("bid")
    public Double getBid() {
        return bid;
    }

    @JsonProperty("bid")
    public void setBid(Double bid) {
        this.bid = bid;
    }

    @JsonProperty("bidSize")
    public Integer getBidSize() {
        return bidSize;
    }

    @JsonProperty("bidSize")
    public void setBidSize(Integer bidSize) {
        this.bidSize = bidSize;
    }

    @JsonProperty("ask")
    public Double getAsk() {
        return ask;
    }

    @JsonProperty("ask")
    public void setAsk(Double ask) {
        this.ask = ask;
    }

    @JsonProperty("askSize")
    public Integer getAskSize() {
        return askSize;
    }

    @JsonProperty("askSize")
    public void setAskSize(Integer askSize) {
        this.askSize = askSize;
    }

    @JsonProperty("fundingRate")
    public Integer getFundingRate() {
        return fundingRate;
    }

    @JsonProperty("fundingRate")
    public void setFundingRate(Integer fundingRate) {
        this.fundingRate = fundingRate;
    }

    @JsonProperty("fundingRatePrediction")
    public Integer getFundingRatePrediction() {
        return fundingRatePrediction;
    }

    @JsonProperty("fundingRatePrediction")
    public void setFundingRatePrediction(Integer fundingRatePrediction) {
        this.fundingRatePrediction = fundingRatePrediction;
    }

    @JsonProperty("open24h")
    public Integer getOpen24h() {
        return open24h;
    }

    @JsonProperty("open24h")
    public void setOpen24h(Integer open24h) {
        this.open24h = open24h;
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
