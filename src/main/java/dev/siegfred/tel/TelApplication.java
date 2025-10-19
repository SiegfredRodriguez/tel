package dev.siegfred.tel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableFeignClients
public class TelApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelApplication.class, args);
    }

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }

}
