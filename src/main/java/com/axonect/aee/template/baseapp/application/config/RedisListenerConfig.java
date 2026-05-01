//package com.axonect.aee.template.baseapp.application.config;
//
//import com.axonect.aee.template.baseapp.application.listner.KeyExpirationListener;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.data.redis.connection.RedisConnectionFactory;
//import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
//import org.springframework.data.redis.listener.PatternTopic;
//import org.springframework.data.redis.listener.RedisMessageListenerContainer;
//
//import java.util.ArrayList;
//import java.util.List;
//
///**
// * Configures Redis key-expiry listener infrastructure.
// *
// * Strategy:
// *  - If cluster nodes are provided → create one container per node
// *    (each node independently fires expiry events)
// *  - Otherwise → delegate to the auto-configured RedisConnectionFactory
// *    which already handles Standalone / Sentinel from application.yaml
// *
// * Prerequisite — enable keyspace notifications in Redis:
// *   redis.conf:  notify-keyspace-events Ex
// *   or at runtime:  CONFIG SET notify-keyspace-events Ex
// */
//@Slf4j
//@Configuration
//public class RedisListenerConfig {
//
//    @Value("${spring.data.redis.cluster.nodes:}")
//    private List<String> clusterNodes;
//
//    private static final String EXPIRY_PATTERN = "__keyevent@0__:expired";
//
//    @Bean
//    public List<RedisMessageListenerContainer> redisListenerContainers(
//            RedisConnectionFactory autoConfiguredFactory,
//            KeyExpirationListener keyExpirationListener
//    ) {
//        //  THIS IS WHAT WAS MISSING — add this line
//        enableKeyspaceNotifications(autoConfiguredFactory);
//
//        List<RedisMessageListenerContainer> containers = new ArrayList<>();
//
//        boolean isCluster = clusterNodes != null
//                && !clusterNodes.isEmpty()
//                && !clusterNodes.get(0).isBlank();
//
//        if (isCluster) {
//            log.info("Redis cluster mode detected — creating one listener container per node");
//            for (String node : clusterNodes) {
//                String[] parts = node.split(":");
//                String host = parts[0].trim();
//                int port    = Integer.parseInt(parts[1].trim());
//
//                LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
//                factory.afterPropertiesSet();
//                containers.add(buildContainer(factory, keyExpirationListener, "cluster-node-" + node));
//            }
//        } else {
//            log.info("Using auto-configured RedisConnectionFactory for key-expiry listener");
//            containers.add(buildContainer(autoConfiguredFactory, keyExpirationListener, "default"));
//        }
//
//        return containers;
//    }
//
//    /**
//     * Tells Redis to start firing key expiry events.
//     * Without this, Redis silently discards all expiry events.
//     * Equivalent to: redis-cli CONFIG SET notify-keyspace-events Ex
//     */
//    private void enableKeyspaceNotifications(RedisConnectionFactory factory) {
//        try {
//            factory.getConnection()
//                    .serverCommands()
//                    .setConfig("notify-keyspace-events", "Ex");
//            log.info("Redis keyspace notifications enabled (notify-keyspace-events=Ex)");
//        } catch (Exception e) {
//            log.error("Failed to enable Redis keyspace notifications — expiry events will NOT fire!", e);
//        }
//    }
//
//    private RedisMessageListenerContainer buildContainer(
//            RedisConnectionFactory factory,
//            KeyExpirationListener listener,
//            String label
//    ) {
//        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
//        container.setConnectionFactory(factory);
//        container.addMessageListener(listener, new PatternTopic(EXPIRY_PATTERN));
//        container.afterPropertiesSet();
//        container.start();
//
//        log.info("RedisMessageListenerContainer started [{}] on pattern '{}'", label, EXPIRY_PATTERN);
//        return container;
//    }
//}