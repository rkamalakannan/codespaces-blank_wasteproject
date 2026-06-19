package com.krakenfutures.wastebot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krakenfutures.wastebot.agent.client.AnthropicClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class PerformanceAnalyzerAgent {
    private static final Logger log = LoggerFactory.getLogger(PerformanceAnalyzerAgent.class);

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    public PerformanceAnalyzerAgent(ObjectMapper objectMapper, AnthropicClient anthropicClient) {
        this.objectMapper = objectMapper;
        this.anthropicClient = anthropicClient;
    }

    public String analyzeLogs(List<String> logLines) throws IOException {
        if (logLines == null || logLines.isEmpty()) {
            return "{}";
        }

        String systemPrompt = """
You are a trading bot performance analyst.
Review trading logs and identify improvements.

## INPUT FORMAT
Receive trading log entries:
[ENTER]  LONG | Entry=X | SL=X | TP1=X | TP2=X
[EXIT]   Price=X | P&L=+$X | Duration=Xmin
[SL-HIT] Price=X | P&L=-$X | Reason=X
[TP-HIT] TP1 | Price=X | P&L=+$X
[SKIP]   Reason=X | RSI=X

## ANALYSIS REQUIRED
Calculate and return:

PERFORMANCE METRICS:
  Total trades:        [X]
  Win rate:            [X%]
  Average win:         [€X]
  Average loss:        [€X]
  Profit factor:       [X] (total wins/total losses)
  Max drawdown:        [X%]
  Best trade:          [€X]
  Worst trade:         [€X]
  Average duration:    [X minutes]
  Monthly return:      [X%]

PATTERN ANALYSIS:
  Best performing hour:  [X:00–X:00]
  Worst performing hour: [X:00–X:00]
  Best RSI entry range:  [X–X]
  Most common skip reason: [X]
  SL hit frequency:      [X%]
  TP2 reach rate:        [X%]

RECOMMENDATIONS:
  1. [Specific improvement #1]
  2. [Specific improvement #2]
  3. [Specific improvement #3]

PARAMETER SUGGESTIONS:
  Current RSI range:    50–65
  Suggested RSI range:  [X–X] based on data
  Current ATR mult SL:  1.5
  Suggested ATR mult:   [X] based on data
  Current min R:R:      1.5
  Suggested min R:R:    [X] based on data

## RESPONSE FORMAT
Return structured analysis above
Plus this JSON summary:

{
  "win_rate": number,
  "profit_factor": number,
  "monthly_return_pct": number,
  "max_drawdown_pct": number,
  "recommendation_priority": "RSI|ATR|TIMING|ASSET",
  "bot_health": "EXCELLENT|GOOD|NEEDS_WORK|CRITICAL",
  "continue_trading": true|false,
  "suggested_changes": [
    {
      "parameter": "rsi_upper",
      "current": 65,
      "suggested": 63,
      "reason": "most winning trades entered below 63"
    }
  ]
}
""";

        StringBuilder userPayload = new StringBuilder();
        userPayload.append("Analyze the following compiled list of trading log lines:\n\n");
        for (String line : logLines) {
            userPayload.append(line).append("\n");
        }

        return anthropicClient.callClaude(systemPrompt, userPayload.toString());
    }
}
