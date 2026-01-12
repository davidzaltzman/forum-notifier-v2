import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import jakarta.mail.*;
import jakarta.mail.internet.*;

public class ForumNotifier {

    private static final int PAGES_TO_SCAN = 3;

    public static void main(String[] args) {
        try {
            String dbUrl = System.getenv("DB_URL");
            if (dbUrl == null) {
                System.err.println("DB_URL not set");
                return;
            }

            Connection conn = DriverManager.getConnection(dbUrl);
            HttpClient client = HttpClient.newHttpClient();

            PreparedStatement usersStmt = conn.prepareStatement(
                "SELECT id, email FROM users WHERE status='active'"
            );
            ResultSet users = usersStmt.executeQuery();

            while (users.next()) {
                int userId = users.getInt("id");
                String email = users.getString("email");

                PreparedStatement threadsStmt = conn.prepareStatement(
                    "SELECT * FROM threads WHERE user_id=? AND paused=false"
                );
                threadsStmt.setInt(1, userId);
                ResultSet threads = threadsStmt.executeQuery();

                while (threads.next()) {
                    int threadId = threads.getInt("id");
                    String title = threads.getString("title");
                    String url = threads.getString("url");
                    String msgColor = threads.getString("color_message");
                    String quoteColor = threads.getString("color_quote");
                    String spoilerColor = threads.getString("color_spoiler");

                    List<String> newMessages = fetchNewMessages(
                        client, conn, userId, threadId,
                        url, msgColor, quoteColor, spoilerColor
                    );

                    if (!newMessages.isEmpty()) {
                        sendEmail(email, title, newMessages);
                    }
                }
            }

            conn.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<String> fetchNewMessages(
            HttpClient client, Connection conn,
            int userId, int threadId,
            String baseUrl,
            String msgColor, String quoteColor, String spoilerColor
    ) throws Exception {

        List<String> collected = new ArrayList<>();
        int lastPage = getLastPage(client, baseUrl);

        for (int p = Math.max(1, lastPage - PAGES_TO_SCAN + 1); p <= lastPage; p++) {
            Document doc = Jsoup.parse(
                client.send(
                    HttpRequest.newBuilder(new URI(baseUrl + "/page-" + p)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                ).body()
            );

            for (Element wrapper : doc.select("div.bbWrapper")) {
                if (!wrapper.parent().is("article.message-body.js-selectToQuote")) continue;
                if (!wrapper.select(".perek").isEmpty()) continue;

                String html = "<div style='background:" + msgColor + ";padding:10px;border-radius:8px'>"
                        + wrapper.text() + "</div>";

                String hash = sha256(html);

                PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM sent_messages WHERE user_id=? AND thread_id=? AND message_hash=?"
                );
                check.setInt(1, userId);
                check.setInt(2, threadId);
                check.setString(3, hash);

                if (!check.executeQuery().next()) {
                    collected.add(html);

                    PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO sent_messages (user_id, thread_id, message_hash) VALUES (?,?,?)"
                    );
                    ins.setInt(1, userId);
                    ins.setInt(2, threadId);
                    ins.setString(3, hash);
                    ins.executeUpdate();
                }
            }
        }
        return collected;
    }

    private static void sendEmail(String to, String threadTitle, List<String> messages)
            throws MessagingException {

        String from = System.getenv("EMAIL_FROM");
        String pass = System.getenv("EMAIL_PASSWORD");

        Properties props = new Properties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props,
            new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(from, pass);
                }
            });

        Message msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        msg.setSubject("📬 הודעה חדשה – " + threadTitle);

        StringBuilder body = new StringBuilder("<html><body dir='rtl'>");
        for (String m : messages) body.append(m).append("<hr>");
        body.append("</body></html>");

        msg.setContent(body.toString(), "text/html; charset=UTF-8");
        Transport.send(msg);
    }

    private static int getLastPage(HttpClient client, String url) throws Exception {
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder(new URI(url + "/page-9999")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (r.statusCode() / 100 == 3) {
            String loc = r.headers().firstValue("Location").orElse("");
            if (loc.contains("page-")) {
                return Integer.parseInt(loc.split("page-")[1].split("/")[0]);
            }
        }
        return 1;
    }

    private static String sha256(String s) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        byte[] b = d.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}