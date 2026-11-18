package gsp.network;

import gsp.config.GSPApiConfig;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom retry strategy implementing exponential backoff with jitter and an overall time budget.
 */
public class ExponentialBackoffRetryStrategy extends DefaultHttpRequestRetryStrategy {

    private static final Logger logger = LogManager.getLogger(ExponentialBackoffRetryStrategy.class);

    private static final long DEFAULT_MAX_TIMEOUT_MS = 120_000L;
    private static final long DEFAULT_INITIAL_TIMEOUT_MS = 10_000L;
    private static final double MULTIPLIER = 2.0;
    private static final double RANDOMIZATION_FACTOR = 0.5;

    private static final String START_TIME_ATTR = "gsp.retry.startTimeMs";

    private final long initialIntervalMs;
    private final long maxTimeoutMs;

    public ExponentialBackoffRetryStrategy(GSPApiConfig config) {
        super(
        		// The number of attempts is limited by the total time budget maxTimeoutMs.
                Integer.MAX_VALUE, 
                TimeValue.ofMilliseconds(resolveInitialTimeout(config)),
                Set.of(
                        java.io.InterruptedIOException.class,
                        java.net.UnknownHostException.class,
                        java.net.ConnectException.class,
                        javax.net.ssl.SSLException.class
                ),
                Set.of(429, 500, 502, 503, 504)
        );
        this.initialIntervalMs = resolveInitialTimeout(config);
        this.maxTimeoutMs = resolveMaxTimeout(config);
    }

    private static long resolveInitialTimeout(GSPApiConfig config) {
        return (config != null && config.getInitialTimeoutMs() > 0)
                ? config.getInitialTimeoutMs()
                : DEFAULT_INITIAL_TIMEOUT_MS;
    }

    private static long resolveMaxTimeout(GSPApiConfig config) {
        return (config != null && config.getMaxTimeoutMs() > 0)
                ? config.getMaxTimeoutMs()
                : DEFAULT_MAX_TIMEOUT_MS;
    }

    @Override
    public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
        if (!hasRemainingTimeBudget(execCount, context)) {
            return false;
        }
        boolean shouldRetry = super.retryRequest(request, exception, execCount, context);
        if (shouldRetry) {
            logger.warn("Network I/O error on attempt #{}: {}. Scheduling retry...", execCount, exception.getMessage());
        }
        return shouldRetry;
    }

    @Override
    public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
        if (!hasRemainingTimeBudget(execCount, context)) {
            return false;
        }
        boolean shouldRetry = super.retryRequest(response, execCount, context);
        if (shouldRetry) {
            logger.warn("Retryable HTTP status {} received on attempt #{}. Scheduling retry...",
                    response.getCode(), execCount);
        }
        return shouldRetry;
    }

    @Override
    public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
    	// If the server itself sent the Retry-After header, we respect it (within the maxTimeoutMs limit)
        TimeValue serverRetryAfter = super.getRetryInterval(response, execCount, context);
        if (response != null && response.containsHeader("Retry-After")) {
            long cappedServerDelay = Math.min(serverRetryAfter.toMilliseconds(), maxTimeoutMs);
            return TimeValue.ofMilliseconds(cappedServerDelay);
        }

        long delayMs = calculateBackoffWithJitter(initialIntervalMs, execCount, maxTimeoutMs);
        logger.info("Exponential backoff delay before retry #{}: {} ms", execCount, delayMs);
        return TimeValue.ofMilliseconds(delayMs);
    }

    /**
     * Tracks elapsed execution time in HttpContext and aborts retries if maxTimeoutMs budget is exhausted.
     */
    private boolean hasRemainingTimeBudget(int execCount, HttpContext context) {
        long now = System.currentTimeMillis();
        Long startTime = (Long) context.getAttribute(START_TIME_ATTR);
        if (startTime == null) {
            startTime = now;
            context.setAttribute(START_TIME_ATTR, startTime);
        }

        long elapsedMs = now - startTime;
        if (elapsedMs >= maxTimeoutMs) {
            logger.error("Total retry time budget ({} ms) exhausted after {} attempt(s) (elapsed: {} ms). Aborting retries.",
                    maxTimeoutMs, execCount, elapsedMs);
            return false;
        }
        return true;
    }

    /**
     * Safe calculation of exponential backoff with jitter without long overflow risks.
     */
    private long calculateBackoffWithJitter(long initialIntervalMs, int attempt, long maxCapMs) {
        /* Limit the exponent from above by the value of maxCapMs to avoid overflow of the long type 
         * when the number of attempts is large.
         */
        double rawInterval = initialIntervalMs * Math.pow(MULTIPLIER, Math.min(attempt - 1, 30));
        long cappedInterval = (long) Math.min(rawInterval, maxCapMs);

        long delta = (long) (cappedInterval * RANDOMIZATION_FACTOR);
        long minDelay = Math.max(0L, cappedInterval - delta);
        long maxDelay = Math.max(minDelay, cappedInterval + delta);

        return ThreadLocalRandom.current().nextLong(minDelay, maxDelay + 1);
    }
}
