package com.enova.notifications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class NotificationsApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationsApplication.class, args);
		System.out.println("🚀 Service de Notification Centralisé (SSE) démarré avec succès !");
	}

}
