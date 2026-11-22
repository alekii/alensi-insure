package io.alensi.insure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InsureApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsureApplication.class, args);
    }
}
