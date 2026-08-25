package art.insightai.chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * [Purpose] Application entry point.
 * [Rationale] {@code @ConfigurationPropertiesScan} registers every
 *   {@code @ConfigurationProperties} type under this package, so new property records do
 *   not each need an explicit {@code @EnableConfigurationProperties} entry.
 * [Caution] Component scanning is rooted at this package. Anything placed outside
 *   {@code art.insightai.chatbot} compiles but is never registered, which surfaces as a
 *   404 rather than a startup error.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
