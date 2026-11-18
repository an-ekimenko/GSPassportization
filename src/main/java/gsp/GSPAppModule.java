package gsp;

import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import gsp.config.ConfigPathResolver;
import gsp.config.GSPApiConfig;
import gsp.config.YAMLConfigLoader;
import gsp.jackson.GSP_DataDeserializer;
import gsp.model.GSP_Data;
import gsp.model.GSP_Entity;

import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

/**
 * Guice module responsible for configuring core application infrastructure dependencies.
 */
public class GSPAppModule extends AbstractModule {

    private static final Logger logger = LogManager.getLogger(GSPAppModule.class);

    private static final long DEFAULT_MAX_TIMEOUT_MS = 120_000L;
    private static final long DEFAULT_INITIAL_TIMEOUT_MS = 10_000L;
    private static final double MULTIPLIER = 2.0;
    private static final double RANDOMIZATION_FACTOR = 0.5;
    private static final String RETRY_START_TIME_ATTR = "gsp.retry.startTimeMs";

    @Provides
    @Singleton
    public GSPApiConfig provideConfig() {
        // Load application properties from the external YAML file at startup
        return YAMLConfigLoader.load("properties.yaml");
    }

    @Provides
    @Singleton
    public ObjectMapper provideObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Register the custom deserializer for GSP_Data mapping
        SimpleModule module = new SimpleModule();
        module.addDeserializer(GSP_Data.class, new GSP_DataDeserializer());
        mapper.registerModule(module);

        return mapper;
    }

    @Provides
    @Singleton
    public HttpClientBuilder provideHttpClientBuilder(GSPApiConfig config) {
        long initialIntervalMs = (config != null && config.getInitialTimeoutMs() > 0)
                ? config.getInitialTimeoutMs()
                : DEFAULT_INITIAL_TIMEOUT_MS;

        long maxTimeoutMs = (config != null && config.getMaxTimeoutMs() > 0)
                ? config.getMaxTimeoutMs()
                : DEFAULT_MAX_TIMEOUT_MS;

        DefaultHttpRequestRetryStrategy retryStrategy = new DefaultHttpRequestRetryStrategy(
                Integer.MAX_VALUE, // Attempt limits are governed by the overall maxTimeoutMs time budget
                TimeValue.ofMilliseconds(initialIntervalMs)
        ) {
            @Override
            public boolean retryRequest(HttpRequest request, IOException exception, int execCount, HttpContext context) {
                if (isBudgetExhausted(execCount, context, maxTimeoutMs)) {
                    return false;
                }
                boolean shouldRetry = super.retryRequest(request, exception, execCount, context);
                if (shouldRetry) {
                    logger.warn("Network I/O failure on attempt #{}: {}. Scheduling retry...",
                            execCount, exception.getMessage());
                }
                return shouldRetry;
            }

            @Override
            public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
                if (isBudgetExhausted(execCount, context, maxTimeoutMs)) {
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
            public TimeValue getRetryInterval(HttpRequest request, IOException exception, int execCount, HttpContext context) {
                long delayMs = calculateBackoffWithJitter(initialIntervalMs, execCount, maxTimeoutMs);
                logger.info("Exponential backoff delay before retry #{} (I/O error): {} ms", execCount, delayMs);
                return TimeValue.ofMilliseconds(delayMs);
            }

            @Override
            public TimeValue getRetryInterval(HttpResponse response, int execCount, HttpContext context) {
                // Respect server-provided Retry-After header if present, capped by maxTimeoutMs
                if (response != null && response.containsHeader("Retry-After")) {
                    TimeValue serverDelay = super.getRetryInterval(response, execCount, context);
                    long cappedMs = Math.min(serverDelay.toMilliseconds(), maxTimeoutMs);
                    logger.info("Respecting server Retry-After header before retry #{}: {} ms", execCount, cappedMs);
                    return TimeValue.ofMilliseconds(cappedMs);
                }
                long delayMs = calculateBackoffWithJitter(initialIntervalMs, execCount, maxTimeoutMs);
                logger.info("Exponential backoff delay before retry #{} (HTTP {}): {} ms",
                        execCount, response != null ? response.getCode() : "unknown", delayMs);
                return TimeValue.ofMilliseconds(delayMs);
            }
        };

        // Return the builder instance; timeouts and SSL components are attached by GSP_HttpClient
        return HttpClients.custom()
                .setRetryStrategy(retryStrategy);
    }

    /**
     * Checks whether the total allocated execution time budget (max_timeout_ms) has been exceeded.
     */
    private boolean isBudgetExhausted(int execCount, HttpContext context, long maxTimeoutMs) {
        long now = System.currentTimeMillis();
        Long startTime = (Long) context.getAttribute(RETRY_START_TIME_ATTR);
        if (startTime == null) {
            startTime = now;
            context.setAttribute(RETRY_START_TIME_ATTR, startTime);
        }

        long elapsedMs = now - startTime;
        if (elapsedMs >= maxTimeoutMs) {
            logger.error("Retry time budget ({} ms) exhausted after {} attempt(s) (elapsed: {} ms). Aborting retries.",
                    maxTimeoutMs, execCount, elapsedMs);
            return true;
        }
        return false;
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

    @Provides
    @Singleton
    public SessionFactory provideSessionFactory() throws FileNotFoundException {
        logger.info("Initializing Hibernate SessionFactory infrastructure...");
        StandardServiceRegistryBuilder registryBuilder = null;
        StandardServiceRegistry serviceRegistry = null;

        try {
            // Dynamically locate the external configuration file
            File externalFile = ConfigPathResolver.locateConfigFile("Hibernate.cfg.xml");

            Configuration configuration = new Configuration();
            configuration.configure(externalFile);

            logger.info("Registering annotated domain entity models dynamically...");
            configuration.addAnnotatedClass(GSP_Entity.class);

            logger.info("Building production ServiceRegistry container...");
            registryBuilder = new StandardServiceRegistryBuilder()
                    .applySettings(configuration.getProperties());
            serviceRegistry = registryBuilder.build();

            logger.info("MS SQL metadata loaded. Building SessionFactory connections pool...");
            return configuration.buildSessionFactory(serviceRegistry);

        } catch (Exception e) {
            logger.fatal("Database sub-system initialization failed due to configuration error.", e);

            // Gracefully destroy the service registry on failure to prevent resource and descriptor leaks
            if (serviceRegistry != null) {
                StandardServiceRegistryBuilder.destroy(serviceRegistry);
            }

            throw new RuntimeException("Fatal error during Hibernate architecture startup", e);
        }
    }
}
