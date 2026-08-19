package com.ace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AceBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(AceBeApplication.class, args);
	}

}
