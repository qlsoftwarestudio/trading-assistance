package com.trading.assistant.strategy.backtest;

import com.trading.assistant.binance.ExchangeClient;
import com.trading.assistant.binance.model.Kline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orquesta descarga de datos historicos y ejecucion de backtests.
 */
@Service
public class BacktestService {

    private static final Logger logger = LoggerFactory.getLogger(BacktestService.class);

    @Autowired
    private ExchangeClient binanceClient;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.timeframe:5m}")
    private String timeframe;

    @Value("${trading.strategy.rsi-length:5}")
    private int rsiLength;

    @Value("${trading.strategy.rsi-oversold:30}")
    private double rsiOversold;

    @Value("${trading.strategy.rsi-overbought:70}")
    private double rsiOverbought;

    @Value("${trading.strategy.lookback-bars:12}")
    private int lookbackBars;

    @Value("${trading.strategy.killzone-threshold:30.0}")
    private double killzoneThreshold;

    @Value("${trading.strategy.min-momentum:0.05}")
    private double minMomentum;

    @Value("${trading.strategy.stop-loss-pct:0.6}")
    private double stopLossPct;

    @Value("${trading.strategy.take-profit-pct:1.2}")
    private double takeProfitPct;

    @Value("${trading.strategy.vwap-band-pct:0.5}")
    private double vwapBandPct;

    @Value("${trading.strategy.use-ema-filter:true}")
    private boolean useEmaFilter;

    @Value("${trading.strategy.ema-period:9}")
    private int emaPeriod;

    @Value("${trading.strategy.trailing-stop-pct:0.6}")
    private double trailingStopPct;

    /**
     * Ejecuta un backtest con los parametros actuales de la estrategia.
     * Descarga N klines historicas del simbolo configurado.
     *
     * @param limit cantidad de velas a descargar (ej: 1000 = ~10 dias en 15m)
     * @return resultado del backtest
     */
    public BacktestResult runBacktest(int limit) {
        logger.info("Starting backtest for {} {} (limit={})", symbol, timeframe, limit);
        List<Kline> klines = binanceClient.getKlines(symbol, timeframe, limit);
        if (klines == null || klines.size() < 100) {
            logger.warn("Not enough data for backtest. Got {} klines", klines != null ? klines.size() : 0);
            return null;
        }

        BacktestEngine.BacktestParams params = new BacktestEngine.BacktestParams();
        params.rsiLength = rsiLength;
        params.rsiOversold = rsiOversold;
        params.rsiOverbought = rsiOverbought;
        params.lookbackBars = lookbackBars;
        params.killzoneThreshold = killzoneThreshold;
        params.minMomentum = minMomentum;
        params.stopLossPct = stopLossPct;
        params.takeProfitPct = takeProfitPct;
        params.vwapBandPct = vwapBandPct;
        params.useEmaFilter = useEmaFilter;
        params.emaPeriod = emaPeriod;
        params.trailingStopPct = trailingStopPct;

        BacktestEngine engine = new BacktestEngine();
        BacktestResult result = engine.run(symbol, timeframe, klines, params);
        logger.info("Backtest result: {}", result);
        return result;
    }

    /**
     * Ejecuta un backtest walk-forward: entrena en primer 70% y testea en 30% final.
     */
    public BacktestResult runWalkForwardBacktest(int limit) {
        logger.info("Starting walk-forward backtest for {} {}", symbol, timeframe);
        List<Kline> klines = binanceClient.getKlines(symbol, timeframe, limit);
        if (klines == null || klines.size() < 200) {
            logger.warn("Not enough data for walk-forward backtest");
            return null;
        }

        int splitIndex = (int) (klines.size() * 0.7);
        List<Kline> trainData = klines.subList(0, splitIndex);
        List<Kline> testData = klines.subList(splitIndex, klines.size());

        BacktestEngine.BacktestParams params = new BacktestEngine.BacktestParams();
        // Simplified: use same params for both phases; in a full system you would optimize on trainData
        params.rsiLength = rsiLength;
        params.rsiOversold = rsiOversold;
        params.rsiOverbought = rsiOverbought;
        params.lookbackBars = lookbackBars;
        params.killzoneThreshold = killzoneThreshold;
        params.minMomentum = minMomentum;
        params.stopLossPct = stopLossPct;
        params.takeProfitPct = takeProfitPct;
        params.vwapBandPct = vwapBandPct;
        params.useEmaFilter = useEmaFilter;
        params.emaPeriod = emaPeriod;
        params.trailingStopPct = trailingStopPct;

        BacktestEngine engine = new BacktestEngine();
        BacktestResult trainResult = engine.run(symbol, timeframe, trainData, params);
        logger.info("Train result ({}%): {}", 70, trainResult);

        BacktestResult testResult = engine.run(symbol, timeframe, testData, params);
        logger.info("Test result ({}%): {}", 30, testResult);
        return testResult;
    }
}
