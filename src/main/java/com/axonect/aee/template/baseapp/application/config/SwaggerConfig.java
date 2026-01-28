/**
 * Copyrights 2023 Axiata Digital Labs Pvt Ltd.
 * All Rights Reserved.
 * <p>
 * These material are unpublished, proprietary, confidential source
 * code of Axiata Digital Labs Pvt Ltd (ADL) and constitute a TRADE
 * SECRET of ADL.
 * <p>
 * ADL retains all title to and intellectual property rights in these
 * materials.
 */

package com.axonect.aee.template.baseapp.application.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
public class SwaggerConfig {

    @Value("${base-url.context}")
    private String context;

    @Value("${app.host}")
    private String host;


    @Bean
    public OpenAPI springShopOpenAPI() {
        OpenAPI openAPI = new OpenAPI();
        openAPI.info(apiV3Info());
        Server server = new Server();
        server.setUrl(host);
        openAPI.setServers(Collections.singletonList(server));
        return openAPI;
    }

    private Info apiV3Info() {
        return new Info().title("Business-Template")
                .description("Welcome to MIFE REST API Integration Platform")
                .version("1.0")
                .license(new License().name("Apache 2.0").url("http://springdoc.org"));
    }

}
