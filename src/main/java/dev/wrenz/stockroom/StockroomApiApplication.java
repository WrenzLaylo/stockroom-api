package dev.wrenz.stockroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockroomApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockroomApiApplication.class, args);
    }
}
