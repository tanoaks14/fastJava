package com.fastjava.bench.comparison.jfr;

import com.fastjava.bench.comparison.WebServerComparisonBenchmark;

/**
 * Delegates to WebServerComparisonBenchmark's single-server mode.
 * This is a simple wrapper that keeps the server alive for JFR profiling.
 */
public class JfrProfileableServerRunner {

    public static void main(String[] args) throws Exception {
        // Simply delegate to WebServerComparisonBenchmark which handles server startup
        // in single-server mode and runs the benchmark load
        WebServerComparisonBenchmark.main(args);
    }
}

// Note: WebServerComparisonBenchmark handles all server startup logic
