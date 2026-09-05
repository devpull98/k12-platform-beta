package com.uni.realtime.observability.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class NotificationTestController {

    private final NotificationService notificationService;

    public NotificationTestController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/internal/test-alert/oom")
    public Map<String, Object> triggerOomAlert() {
        Map<String, Boolean> sent = notificationService.broadcast(new ContainerAlert(
                "uni-gateway",
                "OOMKilled",
                "Container Memory RSS (1.2GB) exceeded limit (1.0GB)",
                137
        ));
        return Map.of("alert", "OOMKilled", "sent", sent);
    }

    @GetMapping("/internal/test-alert/down")
    public Map<String, Object> triggerDownAlert() {
        Map<String, Boolean> sent = notificationService.broadcast(new ContainerAlert(
                "uni-engine",
                "CrashLoopBackOff",
                "Container failed health probe check 3 times consecutively",
                1
        ));
        return Map.of("alert", "CrashLoopBackOff", "sent", sent);
    }

    @GetMapping("/internal/test-alert/test")
    public Map<String, Object> triggerCustomAlert(
            @RequestParam(defaultValue = "uni-realtime") String container,
            @RequestParam(defaultValue = "OOMKilled") String status,
            @RequestParam(defaultValue = "Memory limit exceeded") String reason,
            @RequestParam(defaultValue = "137") int exitCode) {
        Map<String, Boolean> sent = notificationService.broadcast(new ContainerAlert(container, status, reason, exitCode));
        return Map.of("container", container, "status", status, "sent", sent);
    }
}
