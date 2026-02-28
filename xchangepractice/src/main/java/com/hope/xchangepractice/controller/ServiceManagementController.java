package com.hope.xchangepractice.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.*;

/**
 * Service Management Controller — provides endpoints to stop the application.
 * 
 * These endpoints are useful for Railway deployments where you need to manage the service lifecycle.
 * 
 * Base path: /api/manage
 */
@RestController
@RequestMapping("/api/manage")
public class ServiceManagementController {

    private final ConfigurableApplicationContext context;

    public ServiceManagementController(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * GET /api/manage/status — Check if the service is running
     */
    @GetMapping("/status")
    public StatusResponse getStatus() {
        return new StatusResponse(
            "RUNNING",
            context.getApplicationName(),
            "Crypto Sipper service is running"
        );
    }

    /**
     * POST /api/manage/stop — Stop the service gracefully
     * Note: This will stop the entire application!
     */
    @PostMapping("/stop")
    public StatusResponse stopService() {
        if (context.isRunning()) {
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // Give time for response to be sent
                    System.exit(0); // Force stop the application
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
            return new StatusResponse(
                "STOPPING",
                context.getApplicationName(),
                "Service is stopping..."
            );
        } else {
            return new StatusResponse(
                "STOPPED",
                context.getApplicationName(),
                "Service is already stopped"
            );
        }
    }

    /**
     * Response record for status endpoints
     */
    public record StatusResponse(String status, String service, String message) {}
}
