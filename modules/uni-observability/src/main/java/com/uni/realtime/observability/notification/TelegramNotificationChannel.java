package com.uni.realtime.observability.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class TelegramNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationChannel.class);

    private final RestClient restClient;
    private final String botToken;
    private final String chatId;

    TelegramNotificationChannel(
            @Value("${telegram.bot-token:}") String botToken,
            @Value("${telegram.chat-id:}") String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.restClient = RestClient.create();
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public boolean isEnabled() {
        return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }

    @Override
    public boolean send(ContainerAlert alert) {
        String icon = alert.isCritical() ? "🔴" : "🟡";
        String text = String.format(
                "%s *CONTAINER ALERT: %s*%n*Container:* %s%n*Reason:* %s%n*Exit Code:* %d",
                icon, alert.status().toUpperCase(), alert.containerName(), alert.reason(), alert.exitCode()
        );

        return post(text, alert.containerName());
    }

    @Override
    public boolean send(AlertGroupNotification group) {
        String icon = group.isFiring() ? (group.isCritical() ? "🔴" : "🟡") : "🟢";
        String body = group.alerts().stream()
                .map(TelegramNotificationChannel::formatAlert)
                .collect(Collectors.joining("\n\n"));
        String text = String.format(
                "%s *%s: %s*%n%n%s",
                icon, group.status().toUpperCase(), group.alertname(), body
        );

        return post(text, group.alertname());
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
        sb.append(icon).append(" *").append(alert.summary()).append('*');

        if (alert.description() != null && !alert.description().isBlank()) {
            sb.append('\n').append(alert.description());
        }

        String context = alert.labels().entrySet().stream()
                .filter(e -> !LABEL_KEYS_ALREADY_SHOWN_ELSEWHERE.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        if (!context.isBlank()) {
            sb.append("\n_").append(context).append('_');
        }

        if (alert.startsAt() != null && !alert.startsAt().isBlank()) {
            sb.append("\n_since ").append(alert.startsAt()).append('_');
        }

        return sb.toString();
    }

    private boolean post(String text, String label) {
        try {
            // botToken can contain ':' — build the URI manually instead of a
            // template var so it isn't percent-encoded into an invalid path.
            restClient.post()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .body(Map.of("chat_id", chatId, "text", text, "parse_mode", "Markdown"))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Sent alert to Telegram: {}", label);
            return true;
        } catch (Exception e) {
            log.error("Failed to send alert to Telegram: {}", e.getMessage());
            return false;
        }
    }
}
