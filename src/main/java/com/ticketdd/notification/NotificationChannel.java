package com.ticketdd.notification;

/**
 * One outbound alert destination (Google Chat, Telegram, ...). Implementations
 * self-report {@link #isEnabled()} based on their own config so
 * {@link NotificationService} can fan out without knowing which channels
 * are actually configured.
 */
public interface NotificationChannel {

    String name();

    boolean isEnabled();

    boolean send(ContainerAlert alert);

    boolean send(AlertGroupNotification group);
}
