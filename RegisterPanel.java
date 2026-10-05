package com.attendance;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Registers a student: stores details, captures face samples from the webcam, retrains the model. */
public class RegisterPanel extends JPanel implements MainFrame.Tab {
    private static final int SAMPLES = 30;
    private static final long INTERVAL_MS = 200;

    private final Db db;
    private final FaceEngine engine;
    private final CameraPanel camera;

    private final JTextField rollField = new JTextField(14);
    private final JTextField nameField = new JTextField(14);
    private final JButton captureBtn = new JButton("Capture face samples");
    private final JProgressBar progress = new JProgressBar(0, SAMPLES);
    private final JLabel status = new JLabel("Camera stopped");
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Roll No", "Name"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final List<Db.Student> listed = new ArrayList<>();

    private volatile int captureId = -1;
    private volatile int captured;
    private volatile long lastCapture;

    public RegisterPanel(Db db, FaceEngine engine, int cameraIndex) {
        this.db = db;
        this.engine = engine;
        this.camera = new CameraPanel(cameraIndex, this::process);
        camera.setStatusListener(s -> SwingUtilities.invokeLater(() -> status.setText(s)));

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(camera, BorderLayout.CENTER);

        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.setBorder(BorderFactory.createTitledBorder("New student"));
        form.add(new JLabel("Roll number / ID"));
        form.add(rollField);
        form.add(new JLabel("Full name"));
        form.add(nameField);
        form.add(captureBtn);
        form.add(progress);
        form.add(new JLabel("Face the camera; one face only. Vary angle slightly."));

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("Registered students"));
        JButton deleteBtn = new JButton("Delete selected");

        JPanel east = new JPanel(new BorderLayout(4, 4));
        east.setPreferredSize(new Dimension(340, 100));
        east.add(form, BorderLayout.NORTH);
        east.add(scroll, BorderLayout.CENTER);
        east.add(deleteBtn, BorderLayout.SOUTH);
        add(east, BorderLayout.EAST);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(status);
        add(bottom, BorderLayout.SOUTH);

        captureBtn.addActionListener(e -> startCapture());
        deleteBtn.addActionListener(e -> deleteSelected());
    }

    @Override
    public void onShow() {
        loadStudents();
        camera.start();
    }

    @Override
    public void onHide() {
        if (captureId >= 0) {
            cancelCapture();
        }
        camera.stop();
    }

    private void loadStudents() {
        try {
            listed.clear();
            listed.addAll(db.students());
            model.setRowCount(0);
            for (Db.Student s : listed) {
                model.addRow(new Object[]{s.rollNo(), s.name()});
            }
        } catch (SQLException e) {
            status.setText("DB error: " + e.getMessage());
        }
    }

    private void startCapture() {
        String roll = rollField.getText().trim();
        String name = nameField.getText().trim();
        if (roll.isEmpty() || name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter both roll number and name.");
            return;
        }
        if (!camera.isRunning()) {
            JOptionPane.showMessageDialog(this, "Camera is not running.");
            return;
        }
        try {
            Db.Student s = db.addStudent(roll, name);
            engine.deleteFaces(s.id());
            captured = 0;
            lastCapture = 0;
            progress.setValue(0);
            captureBtn.setEnabled(false);
            status.setText("Capturing samples for " + name + "...");
            captureId = s.id();
            loadStudents();
        } catch (SQLException ex) {
            String msg = String.valueOf(ex.getMessage());
            JOptionPane.showMessageDialog(this,
                    msg.contains("UNIQUE") ? "That roll number is already registered." : "Database error: " + msg);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "File error: " + ex.getMessage());
        }
    }

    private void cancelCapture() {
        int id = captureId;
        captureId = -1;
        try {
            db.deleteStudent(id);
            engine.deleteFaces(id);
        } catch (SQLException | IOException ignored) { }
        captureBtn.setEnabled(true);
        progress.setValue(0);
    }

    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= listed.size()) {
            return;
        }
        Db.Student s = listed.get(row);
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete " + s.name() + " and all their attendance records?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            db.deleteStudent(s.id());
            engine.deleteFaces(s.id());
            loadStudents();
            status.setText("Deleted " + s.name() + ". Retraining...");
            retrainAsync("Deleted " + s.name());
        } catch (SQLException | IOException ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
        }
    }

    private void retrainAsync(String prefix) {
        new Thread(() -> {
            String msg;
            try {
                int n = engine.train();
                msg = prefix + ". Model trained on " + n + " images.";
            } catch (Exception ex) {
                msg = "Training failed: " + ex.getMessage();
            }
            String finalMsg = msg;
            SwingUtilities.invokeLater(() -> {
                status.setText(finalMsg);
                captureBtn.setEnabled(true);
                rollField.setText("");
                nameField.setText("");
                progress.setValue(0);
            });
        }, "trainer").start();
    }

    private List<CameraPanel.Overlay> process(Mat bgr, BufferedImage img) {
        if (captureId < 0) {
            return List.of();
        }
        Mat gray = engine.toGray(bgr);
        RectVector faces = engine.detect(gray);
        long n = faces.size();
        List<CameraPanel.Overlay> out = new ArrayList<>();
        for (long i = 0; i < n; i++) {
            Rect r = faces.get(i);
            out.add(new CameraPanel.Overlay(r.x(), r.y(), r.width(), r.height(),
                    n == 1 ? "Capturing " + captured + "/" + SAMPLES : "One face only!",
                    n == 1 ? new Color(40, 170, 70) : new Color(220, 50, 50)));
        }

        long now = System.currentTimeMillis();
        if (n == 1 && now - lastCapture >= INTERVAL_MS) {
            Mat face = engine.prepare(gray, faces.get(0));
            try {
                engine.saveFace(captureId, captured, face);
                captured++;
                lastCapture = now;
                int c = captured;
                SwingUtilities.invokeLater(() -> progress.setValue(c));
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                face.close();
            }
            if (captured >= SAMPLES) {
                captureId = -1;
                retrainAsync("Registration complete");
            }
        }
        faces.close();
        gray.close();
        return out;
    }
}
