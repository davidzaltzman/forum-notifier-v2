import java.sql.*;
import java.util.*;

public class DatabaseManager {
    
    private Connection connection;
    
    public DatabaseManager(String dbUrl) throws SQLException {
        this.connection = DriverManager.getConnection(dbUrl);
    }
    
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
    
    /**
     * מחזיר רשימת משתמשים פעילים (status='active' ו-paused=false)
     */
    public List<User> getActiveUsers() throws SQLException {
        List<User> users = new ArrayList<>();
        String sql = "SELECT id, email FROM users WHERE status = 'active' ORDER BY id";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                User user = new User();
                user.id = rs.getInt("id");
                user.email = rs.getString("email");
                users.add(user);
            }
        }
        
        return users;
    }
    
    /**
     * מחזיר רשימת threads פעילים (paused=false) של משתמש
     */
    public List<ThreadConfig> getUserThreads(int userId) throws SQLException {
        List<ThreadConfig> threads = new ArrayList<>();
        String sql = "SELECT id, title, url, color_message, color_quote, color_spoiler " +
                     "FROM threads WHERE user_id = ? AND paused = false ORDER BY id";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ThreadConfig config = new ThreadConfig();
                    config.id = rs.getInt("id");
                    config.title = rs.getString("title");
                    config.url = rs.getString("url");
                    config.messageColor = rs.getString("color_message");
                    config.replyColor = rs.getString("color_quote");
                    config.spoilerColor = rs.getString("color_spoiler");
                    threads.add(config);
                }
            }
        }
        
        return threads;
    }
    
    /**
     * מחזיר set של message hashes שכבר נשלחו למשתמש מ-thread מסוים
     */
    public Set<String> getSentMessageHashes(int userId, int threadId) throws SQLException {
        Set<String> hashes = new HashSet<>();
        String sql = "SELECT message_hash FROM sent_messages WHERE user_id = ? AND thread_id = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, threadId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    hashes.add(rs.getString("message_hash"));
                }
            }
        }
        
        return hashes;
    }
    
    /**
     * שומר message hash חדש שנשלח למשתמש
     */
    public void saveMessageHash(int userId, int threadId, String messageHash) throws SQLException {
        String sql = "INSERT INTO sent_messages (user_id, thread_id, message_hash, sent_at) " +
                     "VALUES (?, ?, ?, NOW()) " +
                     "ON CONFLICT (user_id, thread_id, message_hash) DO NOTHING";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, threadId);
            stmt.setString(3, messageHash);
            stmt.executeUpdate();
        }
    }
    
    /**
     * מנקה message hashes ישנים - שומר רק את ה-5000 האחרונים לכל משתמש+thread
     */
    public void cleanupOldHashes(int userId, int threadId, int maxToKeep) throws SQLException {
        String sql = "DELETE FROM sent_messages WHERE id IN (" +
                     "  SELECT id FROM sent_messages " +
                     "  WHERE user_id = ? AND thread_id = ? " +
                     "  ORDER BY sent_at DESC OFFSET ?" +
                     ")";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, threadId);
            stmt.setInt(3, maxToKeep);
            stmt.executeUpdate();
        }
    }
    
    // Inner classes
    public static class User {
        public int id;
        public String email;
    }
    
    public static class ThreadConfig {
        public int id;
        public String title;
        public String url;
        public String messageColor;
        public String replyColor;
        public String spoilerColor;
    }
}
