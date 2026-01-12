# 📬 Forum Notifier - מערכת התראות לפורומים

מערכת Java שרצה ב-GitHub Actions כל 10 דקות, סורקת אשכולות פורום ושולחת התראות במייל למשתמשים מרובים.

## 🏗️ ארכיטקטורה

- **GitHub Actions** - מריץ את הקוד כל 10 דקות
- **Java 17** - קוד הסריקה והשליחה
- **PostgreSQL (Render)** - מסד נתונים למשתמשים, אשכולות והודעות שנשלחו
- **Jakarta Mail** - שליחת מיילים דרך SMTP

## 📁 מבנה הפרויקט

```
.
├── .github/
│   └── workflows/
│       └── forum-notifier.yml      # GitHub Actions workflow
├── src/
│   └── main/
│       └── java/
│           ├── ForumNotifier.java  # Main - לוגיקת סריקה ושליחה
│           └── DatabaseManager.java # ניהול חיבורים ל-DB
├── pom.xml                          # Maven dependencies
├── schema.sql                       # סכמת מסד נתונים
└── README.md
```

## 🗄️ מבנה מסד הנתונים

### טבלה: `users`
- `id` - מזהה ייחודי
- `email` - כתובת מייל
- `password_hash` - סיסמה מוצפנת (SHA-256)
- `is_admin` - האם מנהל
- `status` - active/disabled

### טבלה: `threads`
- `id` - מזהה ייחודי
- `user_id` - משתמש בעלים
- `title` - כותרת האשכול
- `url` - קישור לאשכול
- `color_message`, `color_quote`, `color_spoiler` - צבעי עיצוב
- `paused` - האם מושהה

### טבלה: `sent_messages`
**מחליף את last.txt - כל משתמש יש לו hash list נפרד**
- `id` - מזהה ייחודי
- `user_id` - למי נשלח
- `thread_id` - מאיזה אשכול
- `message_hash` - SHA-256 של ההודעה
- `sent_at` - מתי נשלח

## 🚀 הגדרת המערכת

### 1. הגדרת PostgreSQL ב-Render

1. צור מסד נתונים חדש ב-[Render](https://render.com)
2. העתק את ה-`External Database URL`
3. הרץ את [schema.sql](schema.sql) על המסד:
   ```bash
   psql <DB_URL> < schema.sql
   ```

### 2. הגדרת GitHub Secrets

עבור אל **Settings → Secrets and variables → Actions** והוסף:

| Secret Name | תיאור | דוגמה |
|------------|-------|-------|
| `DB_URL` | קישור חיבור ל-Postgres | `postgresql://user:pass@hostname/dbname` |
| `MAIL_USER` | כתובת Gmail | `your-email@gmail.com` |
| `MAIL_PASS` | App Password של Gmail | `abcd efgh ijkl mnop` |

#### 📧 קבלת App Password של Gmail:
1. עבור אל [Google Account Security](https://myaccount.google.com/security)
2. אפשר **2-Step Verification**
3. חפש **App passwords**
4. צור סיסמה חדשה ל-"Mail"

### 3. הפעלת GitHub Actions

1. העלה את הקוד ל-GitHub
2. GitHub Actions יתחיל לרוץ אוטומטית כל 10 דקות
3. ניתן גם להריץ ידנית דרך **Actions → Forum Notifier → Run workflow**

## 💻 הרצה מקומית (לבדיקות)

```bash
# Clone the repository
git clone <your-repo>
cd forum-notifier

# Set environment variables
export DB_URL="postgresql://user:pass@hostname/dbname"
export MAIL_USER="your-email@gmail.com"
export MAIL_PASS="your-app-password"

# Build and run
mvn clean package
java -jar target/forum-notifier-1.0.0.jar
```

## 🔧 הוספת משתמשים ואשכולות

### דרך Flask Web App (מומלץ)

אפשר להשתמש ב-[app.py](app.py) (Flask) כממשק ניהול:
1. מנהל יוצר הזמנות
2. משתמשים נרשמים
3. משתמשים מוסיפים threads עם צבעים

### ישירות דרך SQL

```sql
-- הוספת משתמש
INSERT INTO users (email, password_hash, status)
VALUES ('user@example.com', '...hash...', 'active');

-- הוספת אשכול
INSERT INTO threads (user_id, title, url, color_message, color_quote, color_spoiler, paused)
VALUES (1, 'כותרת האשכול', 'https://forum.com/threads/123', '#ffffff', '#e8f4f8', '#fff3cd', false);
```

## 🎨 סינונים קריטיים (אל תגע!)

הקוד כולל 4 סינונים חשובים ב-[ForumNotifier.java](src/main/java/ForumNotifier.java):
1. **סינון מס' 1**: רק הודעות ב-`article.message-body`
2. **סינון מס' 2**: לא חתימות
3. **סינון מס' 3**: לא כללים
4. **סינון מס' 4**: לא פרסומות (`.perek`)

## 📊 ניטור

- בדוק **Actions** ב-GitHub לראות היסטוריית ריצות
- כל ריצה מדפיסה:
  - כמה משתמשים עובדו
  - כמה threads נסרקו
  - כמה הודעות חדשות נמצאו
  - האם מיילים נשלחו בהצלחה

## ⚙️ התאמות אישיות

### שינוי תדירות הריצה
ערוך [.github/workflows/forum-notifier.yml](.github/workflows/forum-notifier.yml):
```yaml
schedule:
  - cron: '*/5 * * * *'  # כל 5 דקות
  - cron: '0 * * * *'    # כל שעה
  - cron: '0 */2 * * *'  # כל שעתיים
```

### שינוי מספר עמודים לסריקה
ב-[ForumNotifier.java](src/main/java/ForumNotifier.java):
```java
private static final int PAGES_TO_SCAN = 5;  // במקום 3
```

### שינוי מספר הודעות מקסימלי לשמירה
```java
private static final int MAX_STORED_MESSAGES = 10000;  // במקום 5000
```

## 🐛 פתרון בעיות

### הקוד לא רץ
- בדוק ש-GitHub Actions מופעל בהגדרות הrepo
- ודא שכל ה-Secrets מוגדרים נכון

### מיילים לא מגיעים
- בדוק את `MAIL_PASS` (App Password, לא סיסמה רגילה)
- בדוק spam folder
- ודא שהמייל פעיל ב-Gmail

### שגיאות חיבור ל-DB
- ודא ש-`DB_URL` נכון
- בדוק שה-DB ב-Render פעיל
- הרץ `schema.sql` אם טרם רצת

### לא מוצאות הודעות חדשות
- ודא ש-`paused=false` ב-threads
- בדוק שה-URL תקין
- הרץ עם debug: `java -jar target/forum-notifier-1.0.0.jar`

## 📜 רישיון

MIT License - השתמש בחופשיות!

## 🤝 תרומה

Pull requests מתקבלים בברכה! שמור על הסינונים הקריטיים בעת שינויים.

---

**שימו לב**: המערכת מתוכננת לעבוד עם פורומים מבוססי XenForo. להתאמה לפורומים אחרים יש לעדכן את ה-CSS selectors.
