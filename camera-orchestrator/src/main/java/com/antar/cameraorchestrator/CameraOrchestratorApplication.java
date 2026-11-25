package com.antar.cameraorchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CameraOrchestratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(CameraOrchestratorApplication.class, args);
	}

}
