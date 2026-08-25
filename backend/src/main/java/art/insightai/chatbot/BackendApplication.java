package art.insightai.chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} 은 이 패키지 하위의
 * {@code @ConfigurationProperties} 클래스를 자동 등록한다.
 * (AppProperties 를 개별로 @EnableConfigurationProperties 하지 않아도 된다)
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
