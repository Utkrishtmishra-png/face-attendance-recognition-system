package com.attendance;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Entry point. Config via -Dattendance.data=<dir> (default ./data), -Dattendance.camera=<index>, -Dattendance.threshold=<n>. */
public class App {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) { }

                String dir = System.getProperty("attendance.data", "data");
                if (dir.startsWith("~")) {
                    dir = System.getProperty("user.home") + dir.substring(1);
                }
                Path data = Paths.get(dir).toAbsolutePath();
                Files.createDirectories(data.resolve("faces"));

                Db db = new Db(data.resolve("attendance.db"));
                FaceEngine engine = new FaceEngine(data.resolve("faces"));
                engine.train();

                new MainFrame(db, engine).setVisible(true);
            } catch (Throwable t) {
                t.printStackTrace();
                JOptionPane.showMessageDialog(null, "Failed to start: " + t, "Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
