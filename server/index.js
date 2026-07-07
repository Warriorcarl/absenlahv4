const express = require('express');
const cors = require('cors');
const sqlite3 = require('sqlite3').verbose();
const path = require('path');
const morgan = require('morgan');
require('dotenv').config();

const app = express();
const PORT = process.env.PORT || 5000;

// Middleware
app.use(cors());
app.use(express.json());
app.use(morgan('dev'));

// SQLite DB Initialization
const dbPath = path.resolve(__dirname, 'database.sqlite');
const db = new sqlite3.Database(dbPath, (err) => {
    if (err) {
        console.error('Database connection failed:', err.message);
    } else {
        console.log('Connected to self-hosted SQLite Database.');
        initializeDatabase();
    }
});

function initializeDatabase() {
    db.serialize(() => {
        // Users Table with roles, positions, divisions, and active device bindings
        db.run(`CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            email TEXT UNIQUE,
            role TEXT DEFAULT 'pekerja',
            division TEXT DEFAULT 'Logistics',
            position TEXT DEFAULT 'Courier',
            deviceId TEXT UNIQUE,
            lat DOUBLE,
            lon DOUBLE,
            radius DOUBLE DEFAULT 150.0,
            shiftStart TEXT DEFAULT '08:00',
            shiftEnd TEXT DEFAULT '17:00'
        )`);

        // Geolocation Check-in log Table
        db.run(`CREATE TABLE IF NOT EXISTS checkins (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            userId TEXT,
            timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
            lat DOUBLE,
            lon DOUBLE,
            status TEXT,
            photoPath TEXT,
            FOREIGN KEY(userId) REFERENCES users(id)
        )`);

        // Seed initial admin account
        db.run(`INSERT OR IGNORE INTO users (id, name, email, role, division, position, deviceId)
                VALUES ('admin-01', 'Super Admin', 'warriorcarl@yahoo.com', 'admin', 'Management', 'Director', NULL)`);
    });
}

// REST API Endpoints
app.get('/api/health', (req, res) => {
    res.json({ status: 'healthy', timestamp: new Date() });
});

// Get All Workers
app.get('/api/pekerja', (req, res) => {
    db.all('SELECT * FROM users', [], (err, rows) => {
        if (err) {
            return res.status(500).json({ error: err.message });
        }
        res.json(rows);
    });
});

// Update Employee Role, Position, and Geofence Coordinates
app.put('/api/pekerja/:id', (req, res) => {
    const { id } = req.params;
    const { division, position, role, lat, lon, radius, shiftStart, shiftEnd } = req.body;
    
    const query = `
        UPDATE users 
        SET division = ?, position = ?, role = ?, lat = ?, lon = ?, radius = ?, shiftStart = ?, shiftEnd = ?
        WHERE id = ?
    `;
    
    db.run(query, [division, position, role, lat, lon, radius, shiftStart, shiftEnd, id], function(err) {
        if (err) {
            return res.status(500).json({ error: err.message });
        }
        res.json({ message: 'Worker profile updated successfully', changes: this.changes });
    });
});

// Unbind Device
app.post('/api/pekerja/:id/unbind', (req, res) => {
    const { id } = req.params;
    db.run('UPDATE users SET deviceId = NULL WHERE id = ?', [id], function(err) {
        if (err) {
            return res.status(500).json({ error: err.message });
        }
        res.json({ message: 'Device binding successfully reset' });
    });
});

// Logging Geofenced Check-in
app.post('/api/checkin', (req, res) => {
    const { userId, lat, lon, status, photoPath } = req.body;
    db.run('INSERT INTO checkins (userId, lat, lon, status, photoPath) VALUES (?, ?, ?, ?, ?)',
        [userId, lat, lon, status, photoPath],
        function(err) {
            if (err) {
                return res.status(500).json({ error: err.message });
            }
            res.json({ message: 'Check-in registered successfully', checkinId: this.lastID });
        }
    );
});

// Get Check-in logs
app.get('/api/logs', (req, res) => {
    db.all('SELECT checkins.*, users.name FROM checkins JOIN users ON checkins.userId = users.id ORDER BY timestamp DESC',
        [],
        (err, rows) => {
            if (err) {
                return res.status(500).json({ error: err.message });
            }
            res.json(rows);
        }
    );
});

// Server listener
app.listen(PORT, () => {
    console.log(`Absenlah Backend server running on port ${PORT}`);
});
