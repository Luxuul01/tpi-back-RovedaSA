package com.rovedasa.catalogo;

import com.rovedasa.catalogo.client.georef.GeoRefProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {"com.rovedasa.catalogo", "com.rovedasa.commons"})
@EnableConfigurationProperties(GeoRefProperties.class)
public class CatalogoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogoApplication.class, args);
    }
}
