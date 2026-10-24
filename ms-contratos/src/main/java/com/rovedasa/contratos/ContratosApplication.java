package com.rovedasa.contratos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.rovedasa.contratos", "com.rovedasa.commons"})
public class ContratosApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContratosApplication.class, args);
    }
}
