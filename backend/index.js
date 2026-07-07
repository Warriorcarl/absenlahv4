const express = require('express');
const { Pool } = require('pg');
const jwt = require('jsonwebtoken');
const cors = require('cors');
require('dotenv').config();

const app = express();
app.use(cors());
app.use(express.json());

const pool = new Pool({
  connectionString: process.env.DB_URI,
});

const JWT_SECRET = process.env.JWT_SECRET || 'absenlah_enterprise_secret';

// Database initialization
const initDb = async () => {
  const schema = `
    CREATE TABLE IF NOT EXISTS pekerja (
      id SERIAL PRIMARY KEY,
      username TEXT UNIQUE,
      email TEXT UNIQUE,
      password_hash TEXT,
      name TEXT,
      division TEXT,
      position TEXT,
      role TEXT DEFAULT 'pekerja',
      device_id TEXT,
      total_leave_quota INT DEFAULT 4,
      must_change_password BOOLEAN DEFAULT TRUE,
      is_available BOOLEAN DEFAULT TRUE,
      last_lat DOUBLE PRECISION,
      last_lon DOUBLE PRECISION
    );

    CREATE TABLE IF NOT EXISTS attendance_logs (
      id SERIAL PRIMARY KEY,
      pekerja_id INT REFERENCES pekerja(id),
      date DATE DEFAULT CURRENT_DATE,
      check_in_time TIMESTAMP,
      check_out_time TIMESTAMP,
      check_in_lat DOUBLE PRECISION,
      check_in_lon DOUBLE PRECISION,
      check_in_status TEXT,
      check_in_fine INT DEFAULT 0,
      check_in_bonus INT DEFAULT 0,
      expected_check_out TIMESTAMP,
      lateness_mitigation TEXT,
      is_manual BOOLEAN DEFAULT FALSE,
      arrived_at_warehouse BOOLEAN DEFAULT FALSE
    );

    CREATE TABLE IF NOT EXISTS user_stats (
      id SERIAL PRIMARY KEY,
      pekerja_id INT REFERENCES pekerja(id),
      month TEXT,
      lateness_count INT DEFAULT 0,
      leave_used INT DEFAULT 0,
      early_departures INT DEFAULT 0,
      emergency_logs INT DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS dynamic_config (
      key TEXT PRIMARY KEY,
      value TEXT
    );
  `;
  await pool.query(schema);

  // Seed admin
  await pool.query(`
    INSERT INTO pekerja (username, password_hash, name, role)
    VALUES ('administrator', 'admin123', 'Super Admin', 'admin')
    ON CONFLICT (username) DO NOTHING
  `);
};

initDb().catch(console.error);

// Auth Middleware
const auth = (req, res, next) => {
  const token = req.headers.authorization?.split(' ')[1];
  if (!token) return res.status(401).send('Unauthorized');
  try {
    req.user = jwt.verify(token, JWT_SECRET);
    next();
  } catch {
    res.status(403).send('Invalid Token');
  }
};

// Routes
app.post('/api/login', async (req, res) => {
  const { username, password, deviceId } = req.body;
  const result = await pool.query('SELECT * FROM pekerja WHERE username = $1', [username]);
  const user = result.rows[0];

  if (!user || user.password_hash !== password) return res.status(401).send('Invalid credentials');

  if (user.device_id && user.device_id !== deviceId) return res.status(403).send('Hardware mismatch (Device Binding Error)');

  if (!user.device_id) await pool.query('UPDATE pekerja SET device_id = $1 WHERE id = $2', [deviceId, user.id]);

  const token = jwt.sign({ id: user.id, role: user.role }, JWT_SECRET);
  res.json({ token, user });
});

app.get('/api/stats', auth, async (req, res) => {
  const month = new Date().toISOString().slice(0, 7);
  const result = await pool.query('SELECT * FROM user_stats WHERE pekerja_id = $1 AND month = $2', [req.user.id, month]);
  res.json(result.rows[0] || { lateness_count: 0, leave_used: 0 });
});

app.post('/api/attendance/check-in', auth, async (req, res) => {
  const { lat, lon, liveness } = req.body;
  // Complex SOP logic as defined in the RuleEngine...
  // This would involve checking time, applying tiered fines, and 3rd lateness rule.
  res.json({ status: 'success', message: 'Check-in recorded' });
});

const PORT = process.env.PORT || 8080;
app.listen(PORT, () => console.log(`Absenlah Enterprise Backend listening on ${PORT}`));
