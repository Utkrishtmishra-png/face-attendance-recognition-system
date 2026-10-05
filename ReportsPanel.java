package com.attendance;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Date-range attendance report with CSV export. */
public class ReportsPanel extends JPanel implements MainFrame.Tab {
    private final Db db;
    private final JTextField from = new JTextField(LocalDate.now().toString(), 10);
    private final JTextField to = new JTextField(LocalDate.now().toString(), 10);
    private final JLabel summary = new JLabel(" ");
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Date", "Time", "Roll No", "Name"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private List<Db.AttendanceRow> rows = new ArrayList<>();

    public ReportsPanel(Db db) {
        this.db = db;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton load = new JButton("Load");
        JButton export = new JButton("Export CSV");
        top.add(new JLabel("From (yyyy-MM-dd):"));
        top.add(from);
        top.add(new JLabel("To:"));
        top.add(to);
        top.add(load);
        top.add(export);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(new JTable(model)), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);

        load.addActionListener(e -> load());
        export.addActionListener(e -> export());
    }

    @Override
    public void onShow() {
        load();
    }

    private void load() {
        try {
            String f = LocalDate.parse(from.getText().trim()).toString();
            String t = LocalDate.parse(to.getText().trim()).toString();
            rows = db.attendanceBetween(f, t);
            model.setRowCount(0);
            for (Db.AttendanceRow r : rows) {
                model.addRow(new Object[]{r.day(), r.time(), r.rollNo(), r.name()});
            }
            summary.setText(rows.size() + " record(s) from " + f + " to " + t);
        } catch (DateTimeParseException e) {
            JOptionPane.showMessageDialog(this, "Dates must look like 2026-10-05.");
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage());
        }
    }

    private void export() {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing to export. Load a report first.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("attendance_" + from.getText().trim() + "_to_" + to.getText().trim() + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        StringBuilder sb = new StringBuilder("Date,Time,Roll No,Name\n");
        for (Db.AttendanceRow r : rows) {
            sb.append(csv(r.day())).append(',').append(csv(r.time())).append(',')
              .append(csv(r.rollNo())).append(',').append(csv(r.name())).append('\n');
        }
        try {
            Files.write(fc.getSelectedFile().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            JOptionPane.showMessageDialog(this, "Saved " + fc.getSelectedFile().getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not save: " + e.getMessage());
        }
    }

    private static String csv(String s) {
        String v = s == null ? "" : s;
        // Guard against spreadsheet formula injection from names like "=cmd|..."
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
