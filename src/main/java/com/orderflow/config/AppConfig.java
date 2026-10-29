package com.orderflow.config;

import java.util.Map;

public record AppConfig(int port, String databaseUrl, String databaseUser, String databasePassword) {
    @Override
    public String toString() {
        return "AppConfig[port=" + port + ", databaseUrl=" + databaseUrl
                + ", databaseUser=" + databaseUser + ", databasePassword=***]";
    }

    public static AppConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static AppConfig fromEnvironment(Map<String, String> environment) {
        String portText = environment.getOrDefault("PORT", "8080");
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT deve ser um número entre 1 e 65535.", e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("PORT deve ser um número entre 1 e 65535.");
        }
        String password = environment.get("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Defina DB_PASSWORD no ambiente.");
        }
        return new AppConfig(
                port,
                environment.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/orderflow"),
                environment.getOrDefault("DB_USER", "orderflow"),
                password
        );
    }
}
