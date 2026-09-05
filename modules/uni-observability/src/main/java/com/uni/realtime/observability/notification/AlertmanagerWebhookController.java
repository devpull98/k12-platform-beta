package com.uni.realtime.observability.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Receives Alertmanager's native webhook_configs payload and relays it
 * through {@link NotificationService} — Alertmanager can't POST directly to
 * Google Chat (its payload schema doesn't match what Chat's webhook expects),
 * so this endpoint is the adapter in between.
 *
 * Runs in the same JVM as the app being monitored: if this instance itself
 * is down, AppDown notifications for it obviously can't relay through it
 * either. Acceptable for this project's dev/demo setup, not a substitute for
 * an out-of-process relay in a real deployment.
 */
@RestController
public class AlertmanagerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookController.class);

    private final NotificationService notificationService;

    public AlertmanagerWebhookController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping("/internal/alertmanager-webhook")
    public Map<String, Object> receive(@RequestBody AlertmanagerPayload payload) {
        String alertname = payload.groupLabels() != null ? payload.groupLabels().getOrDefault("alertname", "unknown") : "unknown";
        String severity = payload.commonLabels() != null ? payload.commonLabels().get("severity") : null;

        List<AlertGroupNotification.AlertDetail> alerts = payload.alerts() == null
                ? List.of()
                : payload.alerts().stream().map(this::toDetail).toList();

        AlertGroupNotification group = new AlertGroupNotification(
                payload.receiver(), payload.status(), alertname, severity, alerts
        );

        log.info("Received Alertmanager webhook: receiver={} status={} alertname={} alerts={}",
                group.receiver(), group.status(), group.alertname(), alerts.size());
        Map<String, Boolean> sent = notificationService.broadcast(group);
        return Map.of("alertname", alertname, "sent", sent);
    }

    private AlertGroupNotification.AlertDetail toDetail(AlertmanagerPayload.AlertItem item) {
        Map<String, String> annotations = item.annotations() != null ? item.annotations() : Map.of();
        Map<String, String> labels = item.labels() != null ? item.labels() : Map.of();
        return new AlertGroupNotification.AlertDetail(
                item.status(),
                annotations.getOrDefault("summary", "(no summary)"),
                annotations.get("description"),
                labels,
                item.startsAt()
        );
    }

    /** Alertmanager's webhook_configs payload — only the fields we use. */
    public record AlertmanagerPayload(
            String receiver,
            String status,
            List<AlertItem> alerts,
            Map<String, String> groupLabels,
            Map<String, String> commonLabels,
            Map<String, String> commonAnnotations
    ) {
        public record AlertItem(String status, Map<String, String> labels, Map<String, String> annotations, String startsAt) {
        }
    }
}
