package com.attendance;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live camera view; recognizes faces and marks attendance once per student per day. */
public class AttendancePanel extends JPanel implements MainFrame.Tab {
    /** A face must be recognized as the same person in this many consecutive checks before it is marked. */
    private static final int CONFIRM_CHECKS = 3;

    private final Db db;
    private final FaceEngine engine;
    private final CameraPanel camera;
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Roll No", "Name", "Time"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JLabel status = new JLabel("Camera stopped");

    // Touched only from the camera thread.
    private int frameCounter;
    private List<CameraPanel.Overlay> lastOverlays = List.of();
    private Map<Integer, Integer> streaks = new HashMap<>();
    private final Set<Integer> markedToday = new HashSet<>();
    private String markedDay = "";

    public AttendancePanel(Db db, FaceEngine engine, int cameraIndex) {
        this.db = db;
        this.engine = engine;
        this.camera = new CameraPanel(cameraIndex, this::process);
        camera.setStatusListener(s -> SwingUtilities.invokeLater(() -> status.setText(s)));

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(camera, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(new JTable(model));
        scroll.setBorder(BorderFactory.createTitledBorder("Today's attendance"));
        scroll.setPreferredSize(new Dimension(340, 100));
        add(scroll, BorderLayout.EAST);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton start = new JButton("Start camera");
        JButton stop = new JButton("Stop camera");
        start.addActionListener(e -> camera.start());
        stop.addActionListener(e -> camera.stop());
        bottom.add(start);
        bottom.add(stop);
        bottom.add(status);
        add(bottom, BorderLayout.SOUTH);
    }

    @Override
    public void onShow() {
        refreshTable();
        camera.start();
    }

    @Override
    public void onHide() {
        camera.stop();
    }

    private void refreshTable() {
        try {
            String today = LocalDate.now().toString();
            List<Db.AttendanceRow> rows = db.attendanceBetween(today, today);
            model.setRowCount(0);
            for (Db.AttendanceRow r : rows) {
                model.addRow(new Object[]{r.rollNo(), r.name(), r.time()});
            }
        } catch (SQLException e) {
            status.setText("DB error: " + e.getMessage());
        }
    }

    private List<CameraPanel.Overlay> process(Mat bgr, BufferedImage img) {
        // Process every 2nd frame to keep the UI smooth.
        if (frameCounter++ % 2 != 0) {
            return lastOverlays;
        }
        String today = LocalDate.now().toString();
        if (!today.equals(markedDay)) {
            markedToday.clear();
            markedDay = today;
        }

        Mat gray = engine.toGray(bgr);
        RectVector faces = engine.detect(gray);
        List<CameraPanel.Overlay> out = new ArrayList<>();
        Map<Integer, Integer> nextStreaks = new HashMap<>();

        for (long i = 0; i < faces.size(); i++) {
            Rect r = faces.get(i);
            int x = r.x(), y = r.y(), w = r.width(), h = r.height();
            Mat face = engine.prepare(gray, r);
            FaceEngine.Match match = engine.recognize(face);
            face.close();

            try {
                Db.Student student = (match != null && match.distance() <= engine.threshold())
                        ? db.findStudent(match.studentId()) : null;
                if (student == null) {
                    out.add(new CameraPanel.Overlay(x, y, w, h, "Unknown", new Color(220, 50, 50)));
                    continue;
                }
                int id = student.id();
                int streak = streaks.getOrDefault(id, 0) + 1;
                nextStreaks.put(id, streak);

                if (markedToday.contains(id)) {
                    out.add(new CameraPanel.Overlay(x, y, w, h, student.name() + " (present)", new Color(40, 170, 70)));
                } else if (streak >= CONFIRM_CHECKS) {
                    boolean added = db.markAttendance(id);
                    markedToday.add(id);
                    if (added) {
                        SwingUtilities.invokeLater(() -> {
                            refreshTable();
                            status.setText("Marked present: " + student.name());
                        });
                    }
                    out.add(new CameraPanel.Overlay(x, y, w, h, student.name() + (added ? " - marked" : " (present)"),
                            new Color(40, 170, 70)));
                } else {
                    out.add(new CameraPanel.Overlay(x, y, w, h, student.name() + "...", new Color(235, 170, 20)));
                }
            } catch (SQLException e) {
                out.add(new CameraPanel.Overlay(x, y, w, h, "DB error", Color.MAGENTA));
            }
        }

        streaks = nextStreaks;
        faces.close();
        gray.close();
        lastOverlays = out;
        return out;
    }
}
