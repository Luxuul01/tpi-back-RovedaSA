package com.rovedasa.catalogo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.rovedasa.catalogo", "com.rovedasa.commons"})
public class CatalogoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogoApplication.class, args);
    }
}
