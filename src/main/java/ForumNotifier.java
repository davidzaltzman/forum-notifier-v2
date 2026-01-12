// ForumNotifier.java

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import jakarta.mail.*;
import jakarta.mail.internet.*;

public class ForumNotifier {

    private static final int PAGES_TO_SCAN = 3;
    private static final int MAX_STORED_MESSAGES = 5000;

    public static void main(String[] args) {
        String dbUrl = System.getenv("DB_URL");
        String mailUser = System.getenv("MAIL_USER");
        String mailPass = System.getenv("MAIL_PASS");

        if (dbUrl == null || mailUser == null || mailPass == null) {
            System.err.println("❌ חסרים משתני סביבה: DB_URL, MAIL_USER, MAIL_PASS");
            return;
        }

        DatabaseManager dbManager = null;

        try {
            dbManager = new DatabaseManager(dbUrl);
            HttpClient client = HttpClient.newHttpClient();

            // לולאה על כל המשתמשים הפעילים
            List<DatabaseManager.User> users = dbManager.getActiveUsers();
            
            for (DatabaseManager.User user : users) {
                System.out.println("📧 מעבד משתמש: " + user.email);
                
                // לולאה על כל ה-threads של המשתמש
                List<DatabaseManager.ThreadConfig> threads = dbManager.getUserThreads(user.id);
                
                if (threads.isEmpty()) {
                    System.out.println("⚠️ אין threads פעילים עבור " + user.email);
                    continue;
                }

                for (DatabaseManager.ThreadConfig thread : threads) {
                    System.out.println("  🔍 סורק: " + thread.title);

                    List<String> allMessages = new ArrayList<>();

                    int lastPage = getLastPage(client, thread.url);
                    if (lastPage == 1) {
                        sendEmail(
                            Collections.singletonList("<div style='color: red; font-weight: bold;'>❌ לא הצלחתי לתפוס את מספר העמוד מהאשכול: " + thread.title + "</div>"), 
                            thread.title, 
                            user.email, 
                            mailUser, 
                            mailPass
                        );
                        continue;
                    }

                    for (int i = lastPage - PAGES_TO_SCAN + 1; i <= lastPage; i++) {
                        String url = thread.url + "/page-" + i;
                        HttpRequest request = HttpRequest.newBuilder().uri(new URI(url)).GET().build();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() / 100 == 3) {
                            String newUrl = response.headers().firstValue("Location").orElse(null);
                            if (newUrl != null) {
                                request = HttpRequest.newBuilder().uri(new URI(newUrl)).GET().build();
                                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            }
                        }

                        Document doc = Jsoup.parse(response.body());
                        Elements wrappers = doc.select("div.bbWrapper");

                        for (Element wrapper : wrappers) {

                            // ✅ סינון מס' 1
                            Element parent = wrapper.parent();
                            if (parent == null || !parent.is("article.message-body.js-selectToQuote")) {
                                continue;
                            }

                            // ✅ סינון מס' 2
                            if (wrapper.selectFirst("aside.message-signature") != null ||
                                    wrapper.closest("aside.message-signature") != null) {
                                continue;
                            }

                            // ✅ סינון מס' 3
                            if (wrapper.text().contains("כללים למשתתפים באשכול עדכונים זה")) {
                                continue;
                            }

                            // ✅ סינון מס' 4: אם קיימת תגית עם class בשם "perek"
                            if (!wrapper.select(".perek").isEmpty()) {
                                continue; // הודעה עם תגית perek => פרסומת
                            }

                            Element quote = wrapper.selectFirst("blockquote.bbCodeBlock--quote");
                            Element replyExpand = wrapper.selectFirst("div.bbCodeBlock-expandLink");
                            boolean hasQuote = quote != null && replyExpand != null;

                            Elements spoilers = wrapper.select("div.bbCodeBlock.bbCodeBlock--spoiler");

                            StringBuilder messageBuilder = new StringBuilder();

                            if (hasQuote) {
                                String quoteAuthor = quote.attr("data-quote");
                                Element quoteContent = quote.selectFirst(".bbCodeBlock-content");
                                String quoteText = quoteContent != null ? quoteContent.text().trim() : "";

                                messageBuilder.append("<div style='border: 1px solid #99d6ff; border-radius: 10px; padding: 10px; margin-bottom: 10px; background: ")
                                        .append(thread.replyColor)
                                        .append(";'>")
                                        .append("🌟 <b>ציטוט מאת</b> ").append(quoteAuthor).append(":<br>")
                                        .append("<i>").append(quoteText.replaceAll("\\n", "<br>")).append("</i>")
                                        .append("</div>");

                                quote.remove();
                                replyExpand.remove();
                                for (Element spoiler : spoilers) spoiler.remove();

                                String replyText = wrapper.text().trim();
                                if (!replyText.isEmpty()) {
                                    messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: ")
                                            .append(thread.messageColor)
                                            .append(";'>")
                                            .append("🗨️ <b>תגובה:</b><br>")
                                            .append(replyText.replaceAll("\\n", "<br>"))
                                            .append("</div>");
                                }

                            } else {
                                for (Element spoiler : spoilers) spoiler.remove();

                                String text = wrapper.text().trim();
                                if (!text.isEmpty()) {
                                    messageBuilder.append("<div style='border: 1px solid #a9dfbf; border-radius: 10px; padding: 10px; background: ")
                                            .append(thread.messageColor)
                                            .append(";'>")
                                            .append(text.replaceAll("\\n", "<br>"))
                                            .append("</div>");
                                }
                            }

                            for (Element spoiler : spoilers) {
                                Element spoilerTitle = spoiler.selectFirst(".bbCodeBlock-title");
                                Element spoilerContent = spoiler.selectFirst(".bbCodeBlock-content");

                                String title = spoilerTitle != null ? spoilerTitle.text().trim() : "ספוילר";
                                String content = spoilerContent != null ? spoilerContent.text().trim() : "";

                                if (!content.isEmpty()) {
                                    messageBuilder.append("<div style='margin-top: 10px; background: ")
                                            .append(thread.spoilerColor)
                                            .append("; border: 1px solid #f5b7b1; padding: 10px; border-radius: 10px;'>")
                                            .append("🤐 <b>").append(title).append(":</b><br>")
                                            .append("<span style='color: #333;'>").append(content.replaceAll("\\n", "<br>")).append("</span>")
                                            .append("</div>");
                                }
                            }

                            if (messageBuilder.length() > 0) {
                                allMessages.add(messageBuilder.toString());
                            }
                        }
                    }

                    // בדיקה אילו הודעות חדשות עבור המשתמש
                    List<String> newMessages = getNewMessages(allMessages, user.id, thread.id, dbManager);

                    if (!newMessages.isEmpty()) {
                        System.out.println("  ✉️ נמצאו " + newMessages.size() + " הודעות חדשות");
                        
                        // שליחת מייל
                        sendEmail(newMessages, thread.title, user.email, mailUser, mailPass);
                        
                        // שמירת ה-hashes של ההודעות החדשות
                        saveNewMessageHashes(newMessages, user.id, thread.id, dbManager);
                        
                        // ניקוי ישן
                        dbManager.cleanupOldHashes(user.id, thread.id, MAX_STORED_MESSAGES);
                    } else {
                        System.out.println("  ✅ אין הודעות חדשות");
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (dbManager != null) {
                try {
                    dbManager.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static List<String> getNewMessages(List<String> allMessages, int userId, int threadId, DatabaseManager dbManager) throws Exception {
        Set<String> previousHashes = dbManager.getSentMessageHashes(userId, threadId);
        List<String> newMessages = new ArrayList<>();
        
        for (String message : allMessages) {
            String messageHash = getMessageHash(message);
            if (!previousHashes.contains(messageHash)) {
                newMessages.add(message);
            }
        }
        
        return newMessages;
    }

    private static void saveNewMessageHashes(List<String> messages, int userId, int threadId, DatabaseManager dbManager) throws Exception {
        for (String message : messages) {
            String messageHash = getMessageHash(message);
            dbManager.saveMessageHash(userId, threadId, messageHash);
        }
    }

    private static String getMessageHash(String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(message.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 לא נתמך במערכת", e);
        }
    }

    private static void sendEmail(List<String> messages, String threadTitle, String toEmail, String fromEmail, String password) {
        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(fromEmail, password);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromEmail));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));
            message.setSubject("📬 הודעה מאשכול " + threadTitle);

            StringBuilder emailBody = new StringBuilder("<html><body style='font-family: Arial; direction: rtl;'>");
            for (String msg : messages) {
                emailBody.append("<div style='border: 1px solid #ccc; border-radius: 10px; padding: 10px; margin-bottom: 15px;'>")
                        .append(msg)
                        .append("</div>");
            }
            emailBody.append("</body></html>");

            message.setContent(emailBody.toString(), "text/html; charset=UTF-8");
            Transport.send(message);
            System.out.println("המייל נשלח בהצלחה ל-" + toEmail);

        } catch (MessagingException e) {
            System.err.println("שגיאה בשליחת מייל ל-" + toEmail + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int getLastPage(HttpClient client, String baseThreadUrl) throws Exception {
        String url = baseThreadUrl + "/page-9999";
        HttpRequest request = HttpRequest.newBuilder().uri(new URI(url)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() / 100 == 3) {
            String newUrl = response.headers().firstValue("Location").orElse(null);
            if (newUrl != null) {
                String[] parts = newUrl.split("page-");
                return Integer.parseInt(parts[1].split("/")[0]);
            }
        }
        return 1;
    }
}
