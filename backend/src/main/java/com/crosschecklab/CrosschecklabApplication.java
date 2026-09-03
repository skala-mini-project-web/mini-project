package com.crosschecklab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CrosschecklabApplication {

	public static void main(String[] args) {
		SpringApplication.run(CrosschecklabApplication.class, args);
	}

}
