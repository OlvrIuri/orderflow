package com.orderflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.application.customer.CustomerService;
import com.orderflow.config.AppConfig;
import com.orderflow.infrastructure.database.JdbcCustomerRepository;
import com.orderflow.presentation.customer.CustomerHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OrderFlowApplication {
    private static final Logger LOG = LoggerFactory.getLogger(OrderFlowApplication.class);

    private OrderFlowApplication() {
    }

    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.fromEnvironment();
        Flyway.configure()
                .dataSource(config.databaseUrl(), config.databaseUser(), config.databasePassword())
                .load()
                .migrate();

        CustomerService service = new CustomerService(new JdbcCustomerRepository(config), Clock.systemUTC());
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        ExecutorService httpExecutor = Executors.newFixedThreadPool(8);
        server.setExecutor(httpExecutor);
        server.createContext("/customers", new CustomerHandler(service, new ObjectMapper()));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Encerrando OrderFlow");
            server.stop(5);
            httpExecutor.shutdown();
            try {
                if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    httpExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                httpExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "orderflow-shutdown"));

        server.start();
        LOG.info("OrderFlow disponível na porta {}", config.port());
    }
}
