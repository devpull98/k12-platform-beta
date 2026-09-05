package com.ticketdd.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class GoogleChatNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatNotificationChannel.class);

    private final RestClient restClient;
    private final String webhookUrl;

    GoogleChatNotificationChannel(@Value("${google.chat.webhook-url:}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.create();
    }

    @Override
    public String name() {
        return "google-chat";
    }

    @Override
    public boolean isEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    @Override
    public boolean send(ContainerAlert alert) {
        String icon = alert.isCritical() ? "🔴" : "🟡";
        String title = String.format("%s CONTAINER ALERT: %s", icon, alert.status().toUpperCase());
        String cardText = String.format(
                "<b>Container Name:</b> %s<br><b>Status:</b> %s<br><b>Reason:</b> %s<br><b>Exit Code:</b> %d",
                alert.containerName(), alert.status(), alert.reason(), alert.exitCode()
        );

        Map<String, Object> payload = Map.of(
                "cardsV2", List.of(
                        Map.of(
                                "cardId", "container_alert_" + System.currentTimeMillis(),
                                "card", Map.of(
                                        "header", Map.of(
                                                "title", title,
                                                "subtitle", "System Environment: Demo/Local | Cluster: Spring Ticket DDD"
                                        ),
                                        "sections", List.of(
                                                Map.of(
                                                        "widgets", List.of(
                                                                Map.of("textParagraph", Map.of("text", cardText)),
                                                                Map.of("buttonList", Map.of(
                                                                        "buttons", List.of(
                                                                                Map.of("text", "📊 Open Grafana", "onClick", Map.of("openLink", Map.of("url", "http://localhost:3000"))),
                                                                                Map.of("text", "📕 View Runbook", "onClick", Map.of("openLink", Map.of("url", "http://localhost:8080/actuator/health")))
                                                                        )
                                                                ))
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        return post(payload, alert.containerName());
    }

    @Override
    public boolean send(AlertGroupNotification group) {
        String icon = group.isFiring() ? (group.isCritical() ? "🔴" : "🟡") : "🟢";
        String title = String.format("%s %s: %s", icon, group.status().toUpperCase(), group.alertname());
        String cardText = group.alerts().stream()
                .map(GoogleChatNotificationChannel::formatAlert)
                .collect(Collectors.joining("<br><br>"));

        Map<String, Object> payload = Map.of(
                "cardsV2", List.of(
                        Map.of(
                                "cardId", "alertmanager_" + System.currentTimeMillis(),
                                "card", Map.of(
                                        "header", Map.of(
                                                "title", title,
                                                "subtitle", "Receiver: " + group.receiver() + " | Spring Ticket DDD"
                                        ),
                                        "sections", List.of(
                                                Map.of(
                                                        "widgets", List.of(
                                                                Map.of("textParagraph", Map.of("text", cardText)),
                                                                Map.of("buttonList", Map.of(
                                                                        "buttons", List.of(
                                                                                Map.of("text", "📊 Open Grafana", "onClick", Map.of("openLink", Map.of("url", "http://localhost:3000"))),
                                                                                Map.of("text", "🔔 Open Alertmanager", "onClick", Map.of("openLink", Map.of("url", "http://localhost:9093")))
                                                                        )
                                                                ))
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        return post(payload, group.alertname());
    }

    private static final Set<String> LABEL_KEYS_ALREADY_SHOWN_ELSEWHERE = Set.of("alertname", "severity", "team");

    /**
     * One alert instance, rendered with enough context (which instance, what
     * exact value tripped the threshold, since when) that a dev can start
     * debugging from the chat message alone instead of pivoting to Grafana
     * first just to find out which service/pod is affected.
     */
    private static String formatAlert(AlertGroupNotification.AlertDetail alert) {
        String icon = "firing".equalsIgnoreCase(alert.status()) ? "🔴" : "✅";
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(icon).append(' ').append(alert.summary()).append("</b>");

        if (alert.description() != null && !alert.description().isBlank()) {
            sb.append("<br>").append(alert.description());
        }

        String context = alert.labels().entrySet().stream()
                .filter(e -> !LABEL_KEYS_ALREADY_SHOWN_ELSEWHERE.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        if (!context.isBlank()) {
            sb.append("<br><font color=\"#888888\">").append(context).append("</font>");
        }

        if (alert.startsAt() != null && !alert.startsAt().isBlank()) {
            sb.append("<br><font color=\"#888888\">since ").append(alert.startsAt()).append("</font>");
        }

        return sb.toString();
    }

    private boolean post(Map<String, Object> payload, String label) {
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Sent alert to Google Chat: {}", label);
            return true;
        } catch (Exception e) {
            log.error("Failed to send alert to Google Chat: {}", e.getMessage());
            return false;
        }
    }
}
