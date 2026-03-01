package com.krakenfutures.wastebot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        Server httpsServer = new Server();
        httpsServer.setUrl("https://{host}");
        httpsServer.setDescription("HTTPS Production Server");
        
        return new OpenAPI()
                .addServersItem(httpsServer)
                .info(new Info()
                        .title("Wastebot Trading API")
                        .description("API for executing trades on Kraken Futures and Spot markets")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Kraken Futures Team")
                                .email("support@krakenfutures.com")));
    }
}