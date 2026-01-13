from flask import Flask, render_template, request, redirect, session, url_for, flash
import os, sqlite3, hashlib, secrets, smtplib
import threading  # ✅ added (background email)
from email.mime.text import MIMEText
from functools import wraps
from datetime import datetime

from dotenv import load_dotenv
load_dotenv()  # טוען משתני סביבה מקובץ .env

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

app = Flask(
    __name__,
    template_folder=os.path.join(BASE_DIR, "templates"),
    static_folder=os.path.join(BASE_DIR, "static")
)

app.secret_key = os.getenv("SECRET_KEY", "default_secret_key")

# Database
DB_PATH = os.path.join(BASE_DIR, "app.db")

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY,
        email TEXT UNIQUE NOT NULL,
        password_hash TEXT NOT NULL,
        is_admin BOOLEAN DEFAULT FALSE,
        status TEXT DEFAULT 'active',
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )''')
    c.execute('''CREATE TABLE IF NOT EXISTS threads (
        id INTEGER PRIMARY KEY,
        user_id INTEGER NOT NULL,
        title TEXT NOT NULL,
        url TEXT NOT NULL,
        color_message TEXT,
        color_quote TEXT,
        color_spoiler TEXT,
        paused BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (user_id) REFERENCES users (id)
    )''')
    c.execute('''CREATE TABLE IF NOT EXISTS invitations (
        id INTEGER PRIMARY KEY,
        email TEXT UNIQUE NOT NULL,
        code TEXT NOT NULL,
        used BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )''')
    c.execute('''CREATE TABLE IF NOT EXISTS sent_emails (
        id INTEGER PRIMARY KEY,
        thread_id INTEGER NOT NULL,
        user_id INTEGER NOT NULL,
        message_hash TEXT NOT NULL,
        sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        FOREIGN KEY (thread_id) REFERENCES threads (id),
        FOREIGN KEY (user_id) REFERENCES users (id)
    )''')
    # Insert admin if not exists
    admin_email = os.getenv("ADMIN_EMAIL", "admin@example.com")
    admin_pass = os.getenv("ADMIN_PASSWORD", "admin123")
    c.execute("SELECT id FROM users WHERE email = ?", (admin_email,))
    if not c.fetchone():
        c.execute("INSERT INTO users (email, password_hash, is_admin) VALUES (?, ?, ?)",
                  (admin_email, hashlib.sha256(admin_pass.encode()).hexdigest(), True))
    conn.commit()
    conn.close()

init_db()

def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'user_id' not in session:
            return redirect(url_for('login'))
        return f(*args, **kwargs)
    return decorated_function

def admin_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'user_id' not in session or not session.get('is_admin', False):
            flash("אין לך הרשאה לגשת לעמוד זה")
            return redirect(url_for('index'))
        return f(*args, **kwargs)
    return decorated_function

def send_email(to, subject, body):
    smtp_server = os.getenv("SMTP_SERVER")
    smtp_port = int(os.getenv("SMTP_PORT", 587))
    smtp_user = os.getenv("SMTP_USER")
    smtp_pass = os.getenv("SMTP_PASS")
    if not all([smtp_server, smtp_user, smtp_pass]):
        print("SMTP not configured")
        return

    msg = MIMEText(body)
    msg['Subject'] = subject
    msg['From'] = smtp_user
    msg['To'] = to

    try:
        # ✅ added timeout so SMTP can't block forever
        server = smtplib.SMTP(smtp_server, smtp_port, timeout=10)
        server.starttls()
        server.login(smtp_user, smtp_pass)
        server.sendmail(smtp_user, to, msg.as_string())
        server.quit()
        print(f"Email sent to {to}")
    except Exception as e:
        print(f"Email error: {e}")

# ✅ added: fire-and-forget background send
def send_email_background(to, subject, body):
    threading.Thread(
        target=send_email,
        args=(to, subject, body),
        daemon=True
    ).start()

def parse_threads(user_id, edit_url=None):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT * FROM threads WHERE user_id = ?", (user_id,))
    threads = []
    for row in c.fetchall():
        threads.append({
            "id": row["id"],
            "title": row["title"],
            "url": row["url"],
            "color_message": row["color_message"],
            "color_quote": row["color_quote"],
            "color_spoiler": row["color_spoiler"],
            "paused": row["paused"],
            "edit": edit_url == row["url"]
        })
    conn.close()
    return threads

@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        email = request.form.get("email")
        password = request.form.get("password")
        conn = get_db()
        c = conn.cursor()
        c.execute("SELECT * FROM users WHERE email = ? AND status = 'active'", (email,))
        user = c.fetchone()
        conn.close()
        if user and hashlib.sha256(password.encode()).hexdigest() == user["password_hash"]:
            session["user_id"] = user["id"]
            session["email"] = user["email"]
            session["is_admin"] = user["is_admin"]
            return redirect(url_for("index"))
        flash("אימייל או סיסמה שגויים")
    return render_template("login.html")

@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))

@app.route("/invite", methods=["GET", "POST"])
@admin_required
def invite():
    if request.method == "POST":
        email = request.form.get("email")
        if not email:
            flash("אימייל נדרש")
            return redirect(url_for("invite"))
        temp_password = secrets.token_hex(4).upper()  # 8 chars
        conn = get_db()
        c = conn.cursor()
        try:
            c.execute("INSERT INTO invitations (email, temp_password) VALUES (?, ?)", (email, temp_password))
            conn.commit()
            # Send to admin (✅ background)
            admin_email = os.getenv("ADMIN_EMAIL")
            send_email_background(admin_email, "הזמנה חדשה", f"משתמש חדש: {email}\nסיסמה זמנית: {temp_password}")
            flash("הזמנה נשלחה למנהל")
        except sqlite3.IntegrityError:
            flash("הזמנה כבר קיימת לאימייל זה")
        conn.close()
    return render_template("invite.html")

@app.route("/register", methods=["GET", "POST"])
def register():
    if request.method == "POST":
        if 'send_code' in request.form:
            email = request.form.get("email")
            if not email:
                flash("אימייל נדרש")
                return redirect(url_for("register"))
            code = secrets.token_hex(4).upper()
            conn = get_db()
            c = conn.cursor()
            try:
                c.execute("INSERT INTO invitations (email, code) VALUES (?, ?)", (email, code))
                conn.commit()
                admin_email = os.getenv("ADMIN_EMAIL")
                # ✅ background send so request returns immediately
                send_email_background(admin_email, "קוד הרשמה חדש", f"משתמש חדש: {email}\nקוד: {code}")
                session["register_code"] = code
                session["register_email"] = email
                flash("קוד נשלח למנהל. הכנס את הקוד שקיבלת.")
            except sqlite3.IntegrityError:
                flash("הזמנה כבר קיימת לאימייל זה")
            conn.close()
            return redirect(url_for("register"))
        else:
            code = request.form.get("code")
            if code == session.get("register_code"):
                return redirect(url_for("set_password"))
            else:
                flash("קוד שגוי")
                return redirect(url_for("register"))
    return render_template("register.html")

@app.route("/set-password", methods=["GET", "POST"])
def set_password():
    if "register_email" not in session:
        return redirect(url_for("register"))
    if request.method == "POST":
        password = request.form.get("password")
        email = session["register_email"]
        conn = get_db()
        c = conn.cursor()
        c.execute("INSERT INTO users (email, password_hash) VALUES (?, ?)", (email, hashlib.sha256(password.encode()).hexdigest()))
        c.execute("UPDATE invitations SET used = TRUE WHERE email = ?", (email,))
        conn.commit()
        conn.close()
        session.pop("register_email")
        flash("הרשמה הצליחה, התחבר עכשיו")
        return redirect(url_for("login"))
    return render_template("set_password.html")

@app.route("/")
def home():
    if 'user_id' in session:
        return redirect(url_for('index'))
    return render_template("home.html")

@app.route("/dashboard")
@login_required
def index():
    user_id = session["user_id"]
    return render_template("index.html", threads=parse_threads(user_id, session.get("edit_target")), is_admin=session.get("is_admin", False), email=session["email"])

@app.route("/add", methods=["POST"])
@login_required
def add():
    user_id = session["user_id"]
    title = request.form.get("title", "").strip()
    url = request.form.get("url", "").strip()
    color_message = request.form.get("color_message")
    color_quote = request.form.get("color_quote")
    color_spoiler = request.form.get("color_spoiler")

    if not title:
        return "❌ חובה לתת שם לאשכול"

    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT id FROM threads WHERE user_id = ? AND url = ?", (user_id, url))
    if c.fetchone():
        conn.close()
        return "❌ האשכול כבר קיים"
    c.execute("INSERT INTO threads (user_id, title, url, color_message, color_quote, color_spoiler) VALUES (?, ?, ?, ?, ?, ?)",
              (user_id, title, url, color_message, color_quote, color_spoiler))
    conn.commit()
    conn.close()
    return redirect("/")

@app.route("/toggle", methods=["POST"])
@login_required
def toggle():
    user_id = session["user_id"]
    thread_id = request.form["id"]
    conn = get_db()
    c = conn.cursor()
    c.execute("UPDATE threads SET paused = NOT paused WHERE id = ? AND user_id = ?", (thread_id, user_id))
    conn.commit()
    conn.close()
    return redirect("/")

@app.route("/edit-title", methods=["POST"])
@login_required
def edit_title():
    session["edit_target"] = request.form["url"]
    return redirect("/")

@app.route("/save-title", methods=["POST"])
@login_required
def save_title():
    user_id = session["user_id"]
    new_title = request.form["new_title"].strip()
    target = request.form["url"]

    if not new_title:
        return "❌ שם לא יכול להיות ריק"

    conn = get_db()
    c = conn.cursor()
    c.execute("UPDATE threads SET title = ? WHERE url = ? AND user_id = ?", (new_title, target, user_id))
    conn.commit()
    conn.close()
    session.pop("edit_target", None)
    return redirect("/")

@app.route("/admin")
@admin_required
def admin():
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT id, email, status, created_at FROM users WHERE is_admin = FALSE")
    users = c.fetchall()
    conn.close()
    return render_template("admin.html", users=users)

@app.route("/admin/<int:user_id>")
@admin_required
def admin_user(user_id):
    conn = get_db()
    c = conn.cursor()
    c.execute("SELECT email FROM users WHERE id = ?", (user_id,))
    user = c.fetchone()
    if not user:
        conn.close()
        return "משתמש לא נמצא", 404
    threads = parse_threads(user_id)
    conn.close()
    return render_template("admin_user.html", threads=threads, email=user["email"], user_id=user_id)

@app.route("/admin/toggle/<int:user_id>", methods=["POST"])
@admin_required
def admin_toggle(user_id):
    thread_id = request.form["id"]
    conn = get_db()
    c = conn.cursor()
    c.execute("UPDATE threads SET paused = NOT paused WHERE id = ? AND user_id = ?", (thread_id, user_id))
    conn.commit()
    conn.close()
    return redirect(url_for("admin_user", user_id=user_id))

@app.route("/admin/remove/<int:user_id>", methods=["POST"])
@admin_required
def admin_remove(user_id):
    thread_id = request.form["id"]
    conn = get_db()
    c = conn.cursor()
    c.execute("DELETE FROM threads WHERE id = ? AND user_id = ?", (thread_id, user_id))
    conn.commit()
    conn.close()
    return redirect(url_for("admin_user", user_id=user_id))

@app.route("/admin/disable/<int:user_id>", methods=["POST"])
@admin_required
def admin_disable(user_id):
    conn = get_db()
    c = conn.cursor()
    c.execute("UPDATE users SET status = 'disabled' WHERE id = ?", (user_id,))
    conn.commit()
    conn.close()
    return redirect(url_for("admin"))

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 10000))
    app.run(host="0.0.0.0", port=port, debug=False)
