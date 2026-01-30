package com.axonect.aee.template.baseapp.application.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Custom cache key generator that creates consistent cache keys
 * based on the class name, method name, and parameters.
 */
@Component("customKeyGenerator")
@Slf4j
public class CustomCacheKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        String key = buildKey(target, method, params);
        log.debug("Generated cache key: {}", key);
        return key;
    }

    private String buildKey(Object target, Method method, Object... params) {
        StringBuilder keyBuilder = new StringBuilder();

        // Add class name
        keyBuilder.append(target.getClass().getSimpleName());
        keyBuilder.append("::");

        // Add method name
        keyBuilder.append(method.getName());
        keyBuilder.append("::");

        // Add parameters
        if (params != null && params.length > 0) {
            String paramsString = Arrays.stream(params)
                    .map(param -> param != null ? param.toString() : "null")
                    .collect(Collectors.joining(","));
            keyBuilder.append(paramsString);
        } else {
            keyBuilder.append("noParams");
        }

        return keyBuilder.toString();
    }
}
