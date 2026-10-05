package com.attendance;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** SQLite persistence for students and attendance. One attendance row per student per day. */
public class Db implements AutoCloseable {
    public record Student(int id, String rollNo, String name) { }
    public record AttendanceRow(String rollNo, String name, String day, String time) { }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Connection conn;

    public Db(Path file) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("CREATE TABLE IF NOT EXISTS students ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "roll_no TEXT UNIQUE NOT NULL,"
                    + "name TEXT NOT NULL,"
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS attendance ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "student_id INTEGER NOT NULL REFERENCES students(id) ON DELETE CASCADE,"
                    + "day TEXT NOT NULL,"
                    + "time TEXT NOT NULL,"
                    + "UNIQUE(student_id, day))");
        }
    }

    public synchronized Student addStudent(String rollNo, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO students(roll_no, name) VALUES(?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rollNo);
            ps.setString(2, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return new Student(rs.getInt(1), rollNo, name);
            }
        }
    }

    public synchronized Student findStudent(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, roll_no, name FROM students WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Student(rs.getInt(1), rs.getString(2), rs.getString(3)) : null;
            }
        }
    }

    public synchronized List<Student> students() throws SQLException {
        List<Student> list = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, roll_no, name FROM students ORDER BY roll_no")) {
            while (rs.next()) {
                list.add(new Student(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        }
        return list;
    }

    public synchronized void deleteStudent(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM students WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    /** @return true if attendance was newly recorded, false if the student was already marked today. */
    public synchronized boolean markAttendance(int studentId) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO attendance(student_id, day, time) VALUES(?, ?, ?)")) {
            ps.setInt(1, studentId);
            ps.setString(2, now.toLocalDate().toString());
            ps.setString(3, now.format(TIME));
            return ps.executeUpdate() > 0;
        }
    }

    /** Inclusive date range, ISO format yyyy-MM-dd. */
    public synchronized List<AttendanceRow> attendanceBetween(String fromDay, String toDay) throws SQLException {
        List<AttendanceRow> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT s.roll_no, s.name, a.day, a.time FROM attendance a "
                        + "JOIN students s ON s.id = a.student_id "
                        + "WHERE a.day BETWEEN ? AND ? ORDER BY a.day DESC, a.time DESC")) {
            ps.setString(1, fromDay);
            ps.setString(2, toDay);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AttendanceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return list;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) { }
    }
}
