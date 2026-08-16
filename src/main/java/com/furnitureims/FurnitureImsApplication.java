package com.furnitureims;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot side of this app: owns dependency injection, transactions and (from
 * milestone M8) the backup scheduler. It is not the JavaFX entry point - see
 * {@link FurnitureImsFxApp}, which starts this context in its {@code init()} and hands
 * beans to FXML controllers via a Spring-aware controller factory.
 */
@SpringBootApplication
public class FurnitureImsApplication {
}
