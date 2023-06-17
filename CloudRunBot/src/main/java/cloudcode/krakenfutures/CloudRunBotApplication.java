package cloudcode.krakenfutures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * This class serves as an entry point for the Spring Boot app
 * Here, we check to ensure all required environment variables are set
 */
@SpringBootApplication

@EnableScheduling
public class CloudRunBotApplication {

    private static final Logger logger = LoggerFactory.getLogger(CloudRunBotApplication.class);

    public static void main(final String[] args) throws Exception {
        String port = System.getenv("PORT");
        if (port == null) {
            logger.warn("$PORT environment variable not set");
        }
        SpringApplication.run(CloudRunBotApplication.class, args);
    }
}
