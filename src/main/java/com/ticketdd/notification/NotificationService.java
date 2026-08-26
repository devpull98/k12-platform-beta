package com.ticketdd.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fans an alert out to every configured {@link NotificationChannel}. Adding a
 * new destination (Slack, Discord, ...) means adding one more
 * {@code @Component implements NotificationChannel} — nothing here changes.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final List<NotificationChannel> channels;

    public NotificationService(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    /**
     * @return per-channel send result, keyed by {@link NotificationChannel#name()}.
     *         Empty if no channel is configured.
     */
    public Map<String, Boolean> broadcast(ContainerAlert alert) {
        List<NotificationChannel> enabled = channels.stream().filter(NotificationChannel::isEnabled).toList();

        if (enabled.isEmpty()) {
            log.warn("No notification channel is configured. Skipping alert for {}", alert.containerName());
            return Map.of();
        }

        return enabled.stream().collect(Collectors.toMap(NotificationChannel::name, c -> c.send(alert)));
    }

    /**
     * @return per-channel send result, keyed by {@link NotificationChannel#name()}.
     *         Empty if no channel is configured.
     */
    public Map<String, Boolean> broadcast(AlertGroupNotification group) {
        List<NotificationChannel> enabled = channels.stream().filter(NotificationChannel::isEnabled).toList();

        if (enabled.isEmpty()) {
            log.warn("No notification channel is configured. Skipping alert group for {}", group.alertname());
            return Map.of();
        }

        return enabled.stream().collect(Collectors.toMap(NotificationChannel::name, c -> c.send(group)));
    }
}
