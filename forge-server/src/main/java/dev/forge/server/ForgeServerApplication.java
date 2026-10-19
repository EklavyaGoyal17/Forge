package dev.forge.server;

import dev.forge.server.config.LabDatabaseProperties;
import dev.forge.server.config.RunnerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({RunnerProperties.class, LabDatabaseProperties.class})
public class ForgeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeServerApplication.class, args);
    }
}
