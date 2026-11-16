package com.hotel.workload.notification;

/** One email's subject and its two bodies (plain text and HTML). Built by {@link NotificationEmailTemplates}. */
public record EmailContent(String subject, String text, String html) {}
