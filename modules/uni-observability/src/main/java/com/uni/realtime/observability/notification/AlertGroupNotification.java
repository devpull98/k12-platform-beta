package com.uni.realtime.observability.notification;

import java.util.List;
import java.util.Map;

/**
 * One Alertmanager notification group (webhook_configs payload), flattened
 * down to what a channel needs to render — see
 * {@link AlertmanagerWebhookController} for the raw payload shape this is
 * built from.
 */
public record AlertGroupNotification(
        String receiver,
        String status,
        String alertname,
        String severity,
        List<AlertDetail> alerts
) {

    public boolean isFiring() {
        return "firing".equalsIgnoreCase(status);
    }

    public boolean isCritical() {
        return "critical".equalsIgnoreCase(severity);
    }

    /**
     * One alert instance inside the group — kept detailed (not just a
     * summary string) so a channel can render the exact instance/value that
     * fired, not just "AppHighErrorRate happened somewhere".
     */
    public record AlertDetail(
            String status,
            String summary,
            String description,
            Map<String, String> labels,
            String startsAt
    ) {
    }
}
