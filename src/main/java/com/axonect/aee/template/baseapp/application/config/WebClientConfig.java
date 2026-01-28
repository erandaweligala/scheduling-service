package com.axonect.aee.template.baseapp.application.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class WebClientConfig {

    @Value("${cache.api.connect-timeout:5000}")
    private Integer connectTimeout;

    @Value("${cache.api.read-timeout:10}")
    private Integer readTimeout;

    @Value("${cache.api.write-timeout:10}")
    private Integer writeTimeout;

    @Value("${cache.api.response-timeout:10}")
    private Integer responseTimeout;

    @Value("${cache.api.max-connections:100}")
    private Integer maxConnections;

    @Value("${cache.api.pending-acquire-timeout:45}")
    private Integer pendingAcquireTimeout;

    @Bean
    public WebClient cacheApiWebClient() {
        // Connection pool configuration
        ConnectionProvider connectionProvider = ConnectionProvider.builder("cache-api-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(Duration.ofSeconds(pendingAcquireTimeout))
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .responseTimeout(Duration.ofSeconds(responseTimeout))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(readTimeout, TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(writeTimeout, TimeUnit.SECONDS)))
                .doOnRequest((request, connection) ->
                        log.info("Outgoing request: {} {}", request.method(), request.resourceUrl()))
                .doOnResponse((response, connection) ->
                        log.info("Incoming response: Status {}", response.status()));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .filter(requestTimingFilter())
                .build();
    }

    /**
     * Comprehensive timing filter that logs request and response with duration
     */
    private ExchangeFilterFunction requestTimingFilter() {
        return (request, next) -> {
            long startTime = System.currentTimeMillis();
            String requestId = String.format("%d", System.nanoTime());

            log.info("[{}] Request: {} {}", requestId, request.method(), request.url());

            // Add request ID and start time to attributes
            ClientRequest modifiedRequest = ClientRequest.from(request)
                    .attribute("startTime", startTime)
                    .attribute("requestId", requestId)
                    .build();

            return next.exchange(modifiedRequest)
                    .doOnSuccess(response -> logResponse(response, startTime, requestId))
                    .doOnError(error -> logError(request, error, startTime, requestId));
        };
    }

    private void logResponse(ClientResponse response, long startTime, String requestId) {
        long duration = System.currentTimeMillis() - startTime;

        log.info("[{}] Response: {} {} - Status={}, Duration={}ms",
                requestId,
                response.request().getMethod(),
                response.request().getURI(),
                response.statusCode(),
                duration);

        // Log warning for slow requests
        if (duration > 5000) {
            log.warn("[{}] SLOW REQUEST: {} {} took {}ms (threshold: 5000ms)",
                    requestId,
                    response.request().getMethod(),
                    response.request().getURI(),
                    duration);
        }

        // Log error responses
        if (response.statusCode().isError()) {
            log.error("[{}] ERROR RESPONSE: {} {} - Status={}, Duration={}ms",
                    requestId,
                    response.request().getMethod(),
                    response.request().getURI(),
                    response.statusCode(),
                    duration);
        }
    }

    private void logError(ClientRequest request, Throwable error, long startTime, String requestId) {
        long duration = System.currentTimeMillis() - startTime;

        log.error("[{}] Request FAILED: {} {} - Duration={}ms, Error: {}",
                requestId,
                request.method(),
                request.url(),
                duration,
                error.getMessage(),
                error);
    }
}
