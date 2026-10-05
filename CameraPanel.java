package com.attendance;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/** Shows the live webcam feed with face boxes. Frames are processed on a background thread. */
public class CameraPanel extends JPanel {
    public interface FrameHandler {
        /** Called on the camera thread for each frame. The Mat is only valid during the call. */
        List<Overlay> handle(Mat bgr, BufferedImage image);
    }

    public record Overlay(int x, int y, int w, int h, String label, Color color) { }

    private final int cameraIndex;
    private final FrameHandler handler;
    private volatile BufferedImage current;
    private volatile List<Overlay> overlays = List.of();
    private volatile boolean running;
    private volatile Consumer<String> statusListener = s -> { };
    private Thread thread;

    public CameraPanel(int cameraIndex, FrameHandler handler) {
        this.cameraIndex = cameraIndex;
        this.handler = handler;
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(640, 480));
    }

    public void setStatusListener(Consumer<String> listener) {
        this.statusListener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "camera-loop");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        current = null;
        overlays = List.of();
        repaint();
        statusListener.accept("Camera stopped");
    }

    private void loop() {
        OpenCVFrameConverter.ToMat toMat = new OpenCVFrameConverter.ToMat();
        Java2DFrameConverter toImage = new Java2DFrameConverter();
        OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(cameraIndex);
        grabber.setImageWidth(640);
        grabber.setImageHeight(480);
        try {
            grabber.start();
            statusListener.accept("Camera running");
            while (running) {
                Frame frame = grabber.grab();
                if (frame == null || frame.image == null) {
                    Thread.sleep(10);
                    continue;
                }
                Mat mat = toMat.convert(frame);
                BufferedImage img = copy(toImage.convert(frame));
                if (mat == null || img == null) {
                    continue;
                }
                List<Overlay> result;
                try {
                    result = handler.handle(mat, img);
                } catch (Exception e) {
                    e.printStackTrace();
                    result = List.of();
                }
                current = img;
                overlays = result == null ? List.of() : result;
                repaint();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            statusListener.accept("Camera error (index " + cameraIndex + "): " + t.getMessage());
        } finally {
            try {
                grabber.stop();
                grabber.release();
            } catch (Exception ignored) { }
            running = false;
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        if (src == null) {
            return null;
        }
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        try {
            BufferedImage img = current;
            if (img == null) {
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("Camera not running", 20, 30);
                return;
            }
            double scale = Math.min((double) getWidth() / img.getWidth(), (double) getHeight() / img.getHeight());
            int dw = (int) (img.getWidth() * scale);
            int dh = (int) (img.getHeight() * scale);
            int ox = (getWidth() - dw) / 2;
            int oy = (getHeight() - dh) / 2;
            g.drawImage(img, ox, oy, dw, dh, null);

            g.setStroke(new BasicStroke(2f));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
            FontMetrics fm = g.getFontMetrics();
            for (Overlay o : overlays) {
                int x = ox + (int) (o.x() * scale);
                int y = oy + (int) (o.y() * scale);
                int w = (int) (o.w() * scale);
                int h = (int) (o.h() * scale);
                g.setColor(o.color());
                g.drawRect(x, y, w, h);
                String label = o.label();
                if (label != null && !label.isEmpty()) {
                    int tw = fm.stringWidth(label) + 8;
                    int th = fm.getHeight();
                    g.fillRect(x, y - th, tw, th);
                    g.setColor(Color.BLACK);
                    g.drawString(label, x + 4, y - fm.getDescent());
                }
            }
        } finally {
            g.dispose();
        }
    }
}
