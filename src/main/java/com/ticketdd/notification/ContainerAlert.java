package com.ticketdd.notification;

public record ContainerAlert(String containerName, String status, String reason, int exitCode) {

    public boolean isCritical() {
        return "OOMKilled".equalsIgnoreCase(status)
                || "CrashLoopBackOff".equalsIgnoreCase(status)
                || "DOWN".equalsIgnoreCase(status);
    }
}
