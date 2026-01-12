# Copilot Instructions
- **App shape**: Single-file Flask app in [app.py](app.py) with SQLite DB at app.db and Jinja templates under [templates/](templates/). `init_db()` runs on import, creating tables and a default admin from env.
- **Routes & auth**: Login stores `user_id`, `email`, `is_admin` in `session`; `@login_required`/`@admin_required` wrap protected routes. Home redirects authenticated users to `/dashboard`.
- **Registration flow**: `/register` writes an invitation `code` for an email, stores `register_code` + `register_email` in `session`, then `/set-password` creates the user and marks invitation used. If `register_email` missing, `/set-password` redirects back to register.
- **Invitation admin flow**: `/invite` (admin-only) is supposed to insert invitations and notify the admin; schema currently only has `email`, `code`, `used` columns, so inserting `temp_password` will fail until aligned.
- **Thread tracking**: Threads belong to a user; list pulled via `parse_threads(user_id, edit_url)` which flags the editing target using `session['edit_target']` ([app.py](app.py)). Add threads via `/add` with title+URL and optional color prefs; duplicate URLs per user are blocked; `/toggle` flips `paused`; `/edit-title` sets edit target; `/save-title` updates title and clears edit target.
- **Admin tools**: `/admin` lists non-admin users; `/admin/<id>` shows their threads with pause/remove controls and uses the same DB helpers. Admin can disable a user (`status='disabled'`) which is enforced at login query only.
- **Email delivery**: `send_email()` uses SMTP settings (`SMTP_SERVER`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`) and sends plain text via TLS. Missing SMTP config prints a warning and skips sending.
- **Env config**: Expected vars in `.env`: `SECRET_KEY`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, SMTP vars above. Default admin fallback is `admin@example.com` / `admin123` if env absent.
- **UI patterns**: RTL Hebrew UI; inline CSS per template. Dashboard ([templates/index.html](templates/index.html)) includes client-side URL cleaning/edit state and color pickers; profile dropdown shows first letter of email and admin link.
- **Flash/messages**: Error/success cues use `flash` and `get_flashed_messages()` in most forms; messages are Hebrew strings.
- **Data model**: Tables: `users(id,email,password_hash,is_admin,status,created_at)`, `threads(id,user_id,title,url,color_message,color_quote,color_spoiler,paused,created_at)`, `invitations(id,email,code,used,created_at)`, `sent_emails` for dedupe of future sends. Foreign keys not enforced explicitly.
- **Running locally**: Install deps (`flask`, `python-dotenv`) and run `python app.py`; debug is False by default. `init_db()` auto-creates `app.db` if missing; delete the file to reset data.
- **Session keys to mind**: `user_id`, `email`, `is_admin`, `edit_target`, `register_code`, `register_email` (registration flow). Clear or set appropriately when adding flows.
- **Static assets**: `static_folder` is configured but directory is absent; add files there if introducing JS/CSS instead of inlining.
- **Validation gaps**: Minimal server-side validation (e.g., password strength, URL normalization); duplicates checked only on URL per user.
- **Internationalization**: Keep RTL direction and Hebrew copy consistent when adding templates; emojis used in headings.
- **Database threading**: Uses per-request SQLite connections via `get_db()` with `row_factory`; no teardown hooks—close connections manually on new routes.
- **Admin safety**: Login query filters out users with `status='disabled'`; admin toggle/remove routes trust POSTed `id` scoped by user; keep that guard when extending.
- **Email failure tolerance**: Errors in SMTP are printed but do not block DB writes; wrap new email sends similarly to avoid crashing the request.
- **Edge note**: Dashboard template contains a stray pasted SMTP snippet near the stats bar; clean if editing that file.
- **Testing**: No automated tests; manual verification flows: register → set password → login → add thread → toggle/edit → admin disable.
- **Extending**: When adding models, remember `init_db()` runs at import; adjust schema there and consider migrations if growth continues.

Feel free to refine any unclear items; tell me what to adjust or expand.
