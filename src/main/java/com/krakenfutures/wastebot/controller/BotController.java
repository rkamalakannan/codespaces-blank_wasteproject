/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.controller;

import java.io.IOException;
import java.math.BigDecimal;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.krakenfutures.wastebot.weblayer.KrakenFutureConfiguration;

/**
 *
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
        krakenConfiguration.placeOrder(instrument, originalAmount);

    }

}
