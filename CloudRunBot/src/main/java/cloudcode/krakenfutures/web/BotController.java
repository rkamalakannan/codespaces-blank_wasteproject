/*
 * Click nbfs://nbhost/SystemFileS/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nSystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package cloudcode.krakenfutures.web;

import cloudcode.krakenfutures.models.Root;
import cloudcode.krakenfutures.weblayer.KrakenFutureConfiguration;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.ExecutionException;


/**
 * @author vscode
 */
@RestController
public class BotController {

    @Autowired
    KrakenFutureConfiguration krakenConfiguration;

    @GetMapping("/v1/execute/{asset}/{originalAmount}")
    public void executeInstrument(@PathVariable String asset, @PathVariable BigDecimal originalAmount)
            throws IOException {
        Instrument instrument = new CurrencyPair(asset, "USD");
        krakenConfiguration.placeOrder(instrument, originalAmount, null, null, null);

    }


    @GetMapping("/v2/execute/{asset}/{originalAmount}")
    public void findAveragePrice(@PathVariable String asset, @PathVariable BigDecimal originalAmount) {
        Instrument instrument = new CurrencyPair(asset, "USD");
//        averagePricingStragegy.execution(instrument, originalAmount);
    }


    @GetMapping("/v3/execute/{asset}/{originalAmount}")
    public Root getKrakenFuturesCandle(@PathVariable String asset, @PathVariable BigDecimal originalAmount)
            throws IOException {
        Instrument instrument = new CurrencyPair(asset, "USD");
        return krakenConfiguration.getKrakenFuturesOHLCs(instrument);
    }

}