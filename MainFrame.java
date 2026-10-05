package com.attendance;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class MainFrame extends JFrame {
    /** Tabs implement this so the single webcam is only open while its tab is visible. */
    public interface Tab {
        default void onShow() { }
        default void onHide() { }
    }

    public MainFrame(Db db, FaceEngine engine) {
        super("Face Recognition Attendance System");
        int camera = Integer.getInteger("attendance.camera", 0);

        AttendancePanel attendance = new AttendancePanel(db, engine, camera);
        RegisterPanel register = new RegisterPanel(db, engine, camera);
        ReportsPanel reports = new ReportsPanel(db);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Live Attendance", attendance);
        tabs.addTab("Register Student", register);
        tabs.addTab("Reports", reports);

        Tab[] current = {attendance};
        tabs.addChangeListener(e -> {
            current[0].onHide();
            Component c = tabs.getSelectedComponent();
            current[0] = (Tab) c;
            current[0].onShow();
        });

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                current[0].onHide();
                db.close();
                dispose();
                System.exit(0);
            }
        });

        add(tabs);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        attendance.onShow();
    }
}
