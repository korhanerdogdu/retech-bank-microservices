package com.banking.notificationservice.service;

import com.banking.notificationservice.model.Contact;
import com.banking.notificationservice.model.Notification;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Renders each banking event as plain text, HTML email and (where useful) SMS. */
@Component
public class NotificationTemplates {

    // Turkish number symbols: '.' groups thousands, ',' separates decimals
    private static final DecimalFormatSymbols TURKISH_NUMBERS =
            DecimalFormatSymbols.getInstance(Locale.forLanguageTag("tr-TR"));

    public Notification debit(Contact to, Object amount, String receiverAccount) {
        String text = String.format("%s debited from account %s to account %s.",
                money(amount), to.accountNumber(), receiverAccount);
        return plain("Debit alert: " + money(amount), to, "Money sent", text);
    }

    public Notification credit(Contact to, Object amount, String senderAccount) {
        String text = String.format("%s credited to account %s from account %s.",
                money(amount), to.accountNumber(), senderAccount);
        return plain("Credit alert: " + money(amount), to, "Money received", text);
    }

    public Notification refund(Contact to, Object amount, String reason) {
        String text = String.format("Your transfer of %s was cancelled. Reason: %s. "
                        + "%s has been refunded to account %s.",
                money(amount), reason, money(amount), to.accountNumber());
        return plain("Refund processed: " + money(amount), to, "Transfer cancelled and refunded", text);
    }

    public Notification accountBlocked(Contact to, String reason) {
        String text = String.format("Your account %s has been blocked. Reason: %s. "
                + "Please contact your bank immediately.", to.accountNumber(), reason);
        String html = layout("Your account has been blocked",
                paragraph("Hello " + to.name() + ",")
                        + paragraph(text));
        String sms = "Retech Bank: your account " + mask(to.accountNumber())
                + " has been blocked for security. Contact the bank.";
        return new Notification("Your account has been blocked", text, html, sms, false);
    }

    /**
     * The code only — no links. Customers confirm or report inside the Retech Bank
     * app, so an email with a link is never "how we do it", which helps them spot phishing.
     */
    public Notification verificationCode(Contact to, String transactionId, String otp, Object amount, String reason) {
        String text = String.format("""
                Hello %s,

                We paused a transfer of %s from account %s because it looked unusual (%s).

                Your verification code is %s. It expires in 5 minutes.
                Enter it in the Retech Bank app to confirm the transfer.

                Didn't make this transfer? Don't share the code. Open the app and tap
                "I didn't make this transfer" - we'll cancel it and secure your account.
                If you do nothing, the transfer is cancelled and the money refunded automatically.

                Retech Bank will never send you a link to enter this code.
                """, to.name(), money(amount), to.accountNumber(), reason, otp);

        String html = layout("Confirm your transfer",
                paragraph("Hello " + to.name() + ",")
                        + htmlParagraph("We paused a transfer of <strong>" + esc(money(amount)) + "</strong> from account "
                        + esc(to.accountNumber()) + " because it looked unusual: " + esc(reason) + ".")
                        + "<p style=\"margin:24px 0 8px;color:#5b5f78;font-size:14px\">Your verification code</p>"
                        + "<p style=\"margin:0 0 8px;font-size:36px;font-weight:800;letter-spacing:8px;color:#1b1840\">"
                        + esc(otp) + "</p>"
                        + "<p style=\"margin:0 0 24px;color:#5b5f78;font-size:14px\">Expires in 5 minutes. "
                        + "Enter it in the Retech Bank app.</p>"
                        + paragraph("Didn't make this transfer? Don't share the code. Open the app and tap "
                        + "\"I didn't make this transfer\" - we'll cancel it and secure your account.")
                        + paragraph("If you do nothing, the transfer is cancelled and the money refunded automatically.")
                        + paragraph("Retech Bank will never send you a link to enter this code."));

        String sms = String.format("Retech Bank: %s is your code to confirm a %s transfer in the app. Expires in 5 min. "
                + "Not you? Don't share it.", otp, money(amount));

        return new Notification("Your verification code for a " + money(amount) + " transfer", text, html, sms, true);
    }

    // ---------- helpers ----------

    private Notification plain(String subject, Contact to, String heading, String text) {
        String html = layout(heading, paragraph("Hello " + to.name() + ",") + paragraph(text));
        return new Notification(subject, text, html, null, false);
    }

    /** Turkish lira in Turkish style, as in the app: ₺1.234,56. */
    static String money(Object amount) {
        if (amount == null) {
            return "₺0,00";
        }
        try {
            BigDecimal value = new BigDecimal(amount.toString()).setScale(2, RoundingMode.HALF_UP);
            // DecimalFormat isn't thread-safe; a new one per call keeps the templates stateless
            return "₺" + new DecimalFormat("#,##0.00", TURKISH_NUMBERS).format(value);
        } catch (NumberFormatException e) {
            return "₺" + amount;
        }
    }

    private static String mask(String accountNumber) {
        return accountNumber.length() <= 4 ? accountNumber : "****" + accountNumber.substring(accountNumber.length() - 4);
    }

    private static String paragraph(String text) {
        return htmlParagraph(esc(text));
    }

    private static String htmlParagraph(String safeHtml) {
        return "<p style=\"margin:0 0 16px;font-size:16px;line-height:1.5;color:#1b1840\">" + safeHtml + "</p>";
    }

    private static String layout(String heading, String bodyHtml) {
        return "<!doctype html><html><body style=\"margin:0;background:#f5f5fa;font-family:Arial,Helvetica,sans-serif\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\" "
                + "style=\"padding:32px 16px\"><table role=\"presentation\" width=\"100%\" style=\"max-width:520px;"
                + "background:#ffffff;border:1px solid #dcdce8;border-radius:10px\"><tr><td style=\"padding:32px\">"
                + "<p style=\"margin:0 0 4px;font-size:14px;color:#4b3fd1;font-weight:700\">Retech Bank</p>"
                + "<h1 style=\"margin:0 0 24px;font-size:24px;color:#1b1840\">" + esc(heading) + "</h1>"
                + bodyHtml
                + "</td></tr></table></td></tr></table></body></html>";
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
