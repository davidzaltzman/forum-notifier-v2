-- Schema for Forum Notifier with multi-user support
-- PostgreSQL Database Schema

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_admin BOOLEAN DEFAULT FALSE,
    status VARCHAR(50) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Threads table (each user can have multiple threads)
CREATE TABLE IF NOT EXISTS threads (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    url TEXT NOT NULL,
    color_message VARCHAR(50),
    color_quote VARCHAR(50),
    color_spoiler VARCHAR(50),
    paused BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sent messages table (tracks which messages were sent to which user)
-- This replaces the old last.txt file system
CREATE TABLE IF NOT EXISTS sent_messages (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    thread_id INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    message_hash VARCHAR(64) NOT NULL,  -- SHA-256 hash
    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, thread_id, message_hash)  -- Prevent duplicate entries
);

-- Invitations table (for registration system)
CREATE TABLE IF NOT EXISTS invitations (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    code VARCHAR(50) NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for better performance
CREATE INDEX IF NOT EXISTS idx_threads_user_id ON threads(user_id);
CREATE INDEX IF NOT EXISTS idx_threads_paused ON threads(paused);
CREATE INDEX IF NOT EXISTS idx_sent_messages_user_thread ON sent_messages(user_id, thread_id);
CREATE INDEX IF NOT EXISTS idx_sent_messages_hash ON sent_messages(message_hash);
CREATE INDEX IF NOT EXISTS idx_sent_messages_sent_at ON sent_messages(sent_at);

-- Sample admin user (change password in production!)
-- Password: admin123 (hashed with SHA-256)
INSERT INTO users (email, password_hash, is_admin, status)
VALUES ('admin@example.com', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', TRUE, 'active')
ON CONFLICT (email) DO NOTHING;

-- Sample user for testing
-- Password: test123 (hashed with SHA-256)
INSERT INTO users (email, password_hash, is_admin, status)
VALUES ('test@example.com', 'ecd71870d1963316a97e3ac3408c9835ad8cf0f3c1bc703527c30265534f75ae', FALSE, 'active')
ON CONFLICT (email) DO NOTHING;
