package com.hotel.workload.notification;

import java.util.ArrayList;
import java.util.List;

/**
 * The Taskotel look shared by every notification email: a header, a heading, a short list of facts, a closing line and a footer that says
 * where the notification settings are. Every dynamic value is escaped for HTML; subjects never contain line breaks. Nothing secret is ever
 * passed in (no passwords, codes or tokens).
 */
public final class NotificationEmailTemplates {

    private static final int MAX_SUBJECT_TITLE = 80;
    private static final int MAX_DESCRIPTION = 300;

    private NotificationEmailTemplates() {}

    /** A label and its value, shown as one line of the email. */
    public record Fact(String label, String value) {}

    /** The facts of a task a notification talks about (captured before the transaction ends, so nothing is lazily loaded later). */
    public record TaskInfo(String title, String description, String priority, String dueDate, String deadlineTime,
                           Integer estimatedMinutes, String departmentName) {}

    public static EmailContent taskAssigned(TaskInfo task) {
        return compose("Taskotel - New task assigned: " + shorten(task.title()),
                "A task has been assigned to you",
                "A new task is waiting for you in Taskotel.",
                taskFacts(task));
    }

    public static EmailContent taskUnassigned(TaskInfo task) {
        return compose("Taskotel - Task reassigned: " + shorten(task.title()),
                "A task is no longer assigned to you",
                "The following task was reassigned to someone else. You do not need to do anything.",
                taskFacts(task));
    }

    /** A task's status changed. No link is included: no application address is configured. */
    public static EmailContent taskStatusChanged(TaskInfo task, String previousStatus, String newStatus, String changedBy) {
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("Task", task.title()));
        facts.add(new Fact("Previous status", previousStatus));
        facts.add(new Fact("New status", newStatus));
        if (present(task.priority())) facts.add(new Fact("Priority", task.priority()));
        if (present(task.departmentName())) facts.add(new Fact("Department", task.departmentName()));
        if (present(task.dueDate())) facts.add(new Fact("Due", task.dueDate() + (present(task.deadlineTime()) ? " " + task.deadlineTime() : "")));
        if (present(changedBy)) facts.add(new Fact("Changed by", changedBy));
        return compose("Taskotel - Task status updated: " + shorten(task.title()) + " is now " + oneLine(newStatus),
                "A task's status was updated",
                "The status of a task you follow in Taskotel has changed.",
                facts);
    }

    /** To the Staff member: their own (global) workload. No task titles. */
    public static EmailContent workloadAlertForStaff(String level, long minutes, long tasks) {
        String shown = levelName(level);
        return compose("Taskotel - Your workload is now " + shown,
                "Your workload has increased",
                "Your active workload has increased to " + shown + ".",
                List.of(new Fact("Workload level", shown),
                        new Fact("Active workload", minutes + " minutes"),
                        new Fact("Active tasks", String.valueOf(tasks))));
    }

    /** To a Manager of the home department: the figures are those of the department's own work only. No task titles. */
    public static EmailContent workloadAlertForManager(String staffName, String departmentName, String level, long minutes, long tasks) {
        String shown = levelName(level);
        return compose("Taskotel - Workload alert: " + oneLine(staffName) + " is now " + shown,
                "A team member's workload has increased",
                "The active workload of a member of your department has increased to " + shown + ".",
                List.of(new Fact("Staff member", staffName),
                        new Fact("Department", departmentName),
                        new Fact("Workload level", shown),
                        new Fact("Active workload (department tasks)", minutes + " minutes"),
                        new Fact("Active tasks (department)", String.valueOf(tasks))));
    }

    private static String levelName(String level) {
        return level == null ? "" : level.replace('_', ' ');
    }

    /**
     * The welcome email for a new account. It carries the username and the TEMPORARY password (sensitive: only ever put in this one
     * message, never logged), a prominent link to the existing login page, and the first-login steps. No ids and no internal details.
     */
    public static EmailContent accountInvitation(String username, String temporaryPassword, String loginUrl) {
        List<Fact> facts = List.of(new Fact("Username", username), new Fact("Temporary password", temporaryPassword));
        List<String> steps = List.of(
                "Open the Taskotel login page with the button below.",
                "Sign in with the username and temporary password above.",
                "Taskotel emails you a verification code: enter it to confirm your email address.",
                "Choose your own permanent password. The temporary password stops working once you have.");
        return compose("Welcome to Taskotel \u2014 Your Account Details",
                "Welcome to Taskotel | Hotel Operations",
                "An account has been created for you. Use the details below to sign in for the first time.",
                facts, ACCOUNT_FOOTER, "Open Taskotel Login", loginUrl, steps);
    }

    private static List<Fact> taskFacts(TaskInfo task) {
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("Task", task.title()));
        if (present(task.description())) facts.add(new Fact("Description", truncate(task.description(), MAX_DESCRIPTION)));
        if (present(task.priority())) facts.add(new Fact("Priority", task.priority()));
        if (present(task.departmentName())) facts.add(new Fact("Department", task.departmentName()));
        if (present(task.dueDate())) facts.add(new Fact("Due", task.dueDate() + (present(task.deadlineTime()) ? " " + task.deadlineTime() : "")));
        if (task.estimatedMinutes() != null) facts.add(new Fact("Estimated duration", task.estimatedMinutes() + " minutes"));
        return facts;
    }

    private static final String PREFERENCES_FOOTER = "You receive this email because of your notification settings (Settings > Notification Preferences).";
    private static final String ACCOUNT_FOOTER = "You are receiving this email because an account was created for you in Taskotel. "
            + "The temporary password is valid for a limited time and must be changed at your first sign-in. Do not share it. "
            + "If you were not expecting this, contact your administrator.";
    private static final String SECURITY_FOOTER = "This is a required security notice from Taskotel. It cannot be turned off.";
    private static final String CONTACT_ADVICE = "Contact an administrator immediately";

    /** Security notices: nothing in them is secret (no password, code, token, username, id, address, time or device), and they have no link. */
    public static EmailContent passwordChanged() {
        return composeSecurity("Taskotel - Your password was changed",
                "Your password was changed",
                "The password of your Taskotel account was changed. Everywhere you were signed in before, you have been signed out.",
                List.of(new Fact("If this was not you", CONTACT_ADVICE)));
    }

    public static EmailContent passwordResetCompleted() {
        return composeSecurity("Taskotel - Your password was reset",
                "Your password was reset",
                "The password of your Taskotel account was reset using a verification code that was sent to your email address.",
                List.of(new Fact("If this was not you", CONTACT_ADVICE)));
    }

    /** @param actorRole "Administrator" or "Manager": the role of whoever deactivated the account, never a name */
    public static EmailContent accountDeactivated(String actorRole) {
        List<Fact> facts = new ArrayList<>();
        if (present(actorRole)) facts.add(new Fact("Deactivated by", actorRole));
        facts.add(new Fact("If this is unexpected", CONTACT_ADVICE));
        return composeSecurity("Taskotel - Your account was deactivated",
                "Your account was deactivated",
                "Your Taskotel account was deactivated. You can no longer sign in.",
                facts);
    }

    /**
     * Worded for every account state: it never assumes the owner already has a password (an account can be reactivated while its
     * first-time setup is still pending).
     */
    public static EmailContent accountReactivated() {
        return composeSecurity("Taskotel - Your account was reactivated",
                "Your account was reactivated",
                "Your Taskotel account is active again. You can use your account as before. If you had not finished setting up your account yet, complete the setup steps when you next sign in.",
                List.of(new Fact("If this is unexpected", CONTACT_ADVICE)));
    }

    private static EmailContent composeSecurity(String subject, String heading, String intro, List<Fact> facts) {
        return compose(subject, heading, intro, facts, SECURITY_FOOTER);
    }

    /** The common layout: plain text and HTML with the same content. */
    public static EmailContent compose(String subject, String heading, String intro, List<Fact> facts) {
        return compose(subject, heading, intro, facts, PREFERENCES_FOOTER);
    }

    private static EmailContent compose(String subject, String heading, String intro, List<Fact> facts, String footer) {
        return compose(subject, heading, intro, facts, footer, null, null, List.of());
    }

    /** The layout, optionally with a prominent action button (label and an http(s) link) and numbered steps. */
    private static EmailContent compose(String subject, String heading, String intro, List<Fact> facts, String footer,
                                        String actionLabel, String actionUrl, List<String> steps) {
        StringBuilder text = new StringBuilder();
        text.append("Taskotel\n\n").append(heading).append("\n\n").append(intro).append("\n\n");
        StringBuilder rows = new StringBuilder();
        for (Fact fact : facts) {
            text.append(fact.label()).append(": ").append(oneLine(fact.value())).append('\n');
            rows.append("<tr><td style=\"padding:4px 12px 4px 0;color:#6b7280;vertical-align:top;white-space:nowrap;\">")
                    .append(escape(fact.label()))
                    .append("</td><td style=\"padding:4px 0;color:#111827;\">")
                    .append(escape(fact.value()))
                    .append("</td></tr>");
        }
        StringBuilder extra = new StringBuilder();
        if (steps != null && !steps.isEmpty()) {
            text.append("\nHow to get started:\n");
            extra.append("<p style=\"margin:18px 0 6px;font-weight:bold;\">How to get started</p><ol style=\"margin:0 0 0 18px;padding:0;font-size:14px;\">");
            int number = 1;
            for (String step : steps) {
                text.append(number++).append(". ").append(oneLine(step)).append('\n');
                extra.append("<li style=\"margin:0 0 4px;\">").append(escape(step)).append("</li>");
            }
            extra.append("</ol>");
        }
        // The button's link must be a plain http(s) address (the caller validates it); it is escaped for the attribute as well
        if (actionLabel != null && actionUrl != null && (actionUrl.startsWith("http://") || actionUrl.startsWith("https://"))) {
            text.append('\n').append(actionLabel).append(": ").append(oneLine(actionUrl)).append('\n');
            extra.append("<p style=\"margin:22px 0 6px;\"><a href=\"").append(escape(actionUrl))
                    .append("\" style=\"display:inline-block;background:#059669;color:#ffffff;padding:12px 24px;border-radius:6px;text-decoration:none;font-weight:bold;\">")
                    .append(escape(actionLabel)).append("</a></p>")
                    .append("<p style=\"margin:0;font-size:12px;color:#6b7280;word-break:break-all;\">").append(escape(actionUrl)).append("</p>");
        }
        text.append("\nRegards,\nTaskotel Team\n\n")
                .append(footer).append('\n');

        String html = "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:560px;margin:0 auto;color:#111827;\">"
                + "<div style=\"background:#059669;color:#ffffff;padding:14px 20px;font-size:18px;font-weight:bold;border-radius:8px 8px 0 0;\">Taskotel</div>"
                + "<div style=\"border:1px solid #e5e7eb;border-top:0;padding:20px;border-radius:0 0 8px 8px;\">"
                + "<h2 style=\"margin:0 0 12px;font-size:18px;\">" + escape(heading) + "</h2>"
                + "<p style=\"margin:0 0 16px;\">" + escape(intro) + "</p>"
                + "<table style=\"border-collapse:collapse;font-size:14px;\">" + rows + "</table>"
                + extra
                + "<p style=\"margin:20px 0 0;\">Regards,<br>Taskotel Team</p>"
                + "<p style=\"margin:16px 0 0;font-size:12px;color:#6b7280;\">" + escape(footer) + "</p>"
                + "</div></div>";
        return new EmailContent(oneLine(subject), text.toString(), html);
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** No line breaks or control characters (a subject or a text line must never be split). */
    static String oneLine(String value) {
        if (value == null) return "";
        return value.replaceAll("\\p{Cntrl}+", " ").trim();
    }

    private static String shorten(String title) {
        return truncate(oneLine(title), MAX_SUBJECT_TITLE);
    }

    private static String truncate(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max - 1).trim() + "…";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
