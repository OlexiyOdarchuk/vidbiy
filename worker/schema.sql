-- Очікування, за якими сервер стежить замість призупиненої сторінки (push-сповіщення).
CREATE TABLE IF NOT EXISTS watches (
  id TEXT PRIMARY KEY,              -- SHA-256 адреси підписки push
  endpoint TEXT NOT NULL,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  session INTEGER NOT NULL,         -- коли сторінка ввімкнула очікування
  config TEXT NOT NULL,             -- JSON: місце, будильник на час, правила нічної тривоги
  saw_alert INTEGER NOT NULL DEFAULT 0,
  clear_since INTEGER NOT NULL DEFAULT 0,
  schedule_at INTEGER NOT NULL DEFAULT 0,
  shifted INTEGER NOT NULL DEFAULT 0,
  failing_since INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
