package com.attendance;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_face.LBPHFaceRecognizer;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.bytedeco.opencv.global.opencv_core.CV_32SC1;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;

/**
 * Face detection (Haar cascade) + recognition (LBPH). Face crops are stored as PNGs under
 * faces/&lt;studentId&gt;/ and the model is retrained from them (fast for classroom-sized data).
 */
public class FaceEngine {
    public record Match(int studentId, double distance) { }

    private static final Size FACE_SIZE = new Size(160, 160);

    private final Path facesDir;
    private final CascadeClassifier detector;
    private final double threshold = Double.parseDouble(System.getProperty("attendance.threshold", "70"));
    private LBPHFaceRecognizer recognizer;

    public FaceEngine(Path facesDir) throws IOException {
        this.facesDir = facesDir;
        Files.createDirectories(facesDir);

        Path tmp = Files.createTempFile("haarcascade_frontalface", ".xml");
        tmp.toFile().deleteOnExit();
        try (InputStream in = FaceEngine.class.getResourceAsStream("/haarcascade_frontalface_default.xml")) {
            if (in == null) {
                throw new IOException("haarcascade_frontalface_default.xml missing from resources");
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        detector = new CascadeClassifier(tmp.toAbsolutePath().toString());
        if (detector.empty()) {
            throw new IOException("Could not load face cascade");
        }
    }

    /** Lower distance = better match. A face is accepted if distance <= threshold. */
    public double threshold() {
        return threshold;
    }

    public Mat toGray(Mat bgr) {
        Mat gray = new Mat();
        cvtColor(bgr, gray, COLOR_BGR2GRAY);
        return gray;
    }

    /** Caller should close() the returned vector. */
    public synchronized RectVector detect(Mat gray) {
        RectVector faces = new RectVector();
        detector.detectMultiScale(gray, faces, 1.1, 5, 0, new Size(80, 80), new Size());
        return faces;
    }

    /** Crops, resizes and equalizes a detected face. Caller should close() the result. */
    public Mat prepare(Mat gray, Rect r) {
        Mat roi = new Mat(gray, r);
        Mat out = new Mat();
        resize(roi, out, FACE_SIZE);
        equalizeHist(out, out);
        roi.close();
        return out;
    }

    public synchronized Match recognize(Mat preparedFace) {
        if (recognizer == null) {
            return null;
        }
        IntPointer label = new IntPointer(1);
        DoublePointer confidence = new DoublePointer(1);
        try {
            recognizer.predict(preparedFace, label, confidence);
            return new Match(label.get(0), confidence.get(0));
        } finally {
            label.close();
            confidence.close();
        }
    }

    public void saveFace(int studentId, int index, Mat preparedFace) throws IOException {
        Path dir = facesDir.resolve(String.valueOf(studentId));
        Files.createDirectories(dir);
        imwrite(dir.resolve(index + ".png").toString(), preparedFace);
    }

    public void deleteFaces(int studentId) throws IOException {
        Path dir = facesDir.resolve(String.valueOf(studentId));
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.delete(p);
                }
            }
        }
    }

    /** Retrains from all stored face images. @return number of images used. */
    public synchronized int train() throws IOException {
        List<Mat> images = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();

        List<Path> dirs;
        try (Stream<Path> s = Files.list(facesDir)) {
            dirs = s.filter(Files::isDirectory).collect(Collectors.toList());
        }
        for (Path d : dirs) {
            int id;
            try {
                id = Integer.parseInt(d.getFileName().toString());
            } catch (NumberFormatException e) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.list(d)) {
                files = s.filter(p -> p.toString().endsWith(".png")).collect(Collectors.toList());
            }
            for (Path f : files) {
                Mat m = imread(f.toString(), IMREAD_GRAYSCALE);
                if (m == null || m.empty()) {
                    continue;
                }
                images.add(m);
                ids.add(id);
            }
        }

        if (images.isEmpty()) {
            recognizer = null;
            return 0;
        }

        MatVector vec = new MatVector(images.size());
        Mat labels = new Mat(images.size(), 1, CV_32SC1);
        IntBuffer labelBuf = labels.createBuffer();
        for (int i = 0; i < images.size(); i++) {
            vec.put(i, images.get(i));
            labelBuf.put(i, ids.get(i));
        }

        LBPHFaceRecognizer r = LBPHFaceRecognizer.create();
        r.train(vec, labels);
        recognizer = r;

        int count = images.size();
        vec.close();
        labels.close();
        images.forEach(Mat::close);
        return count;
    }
}
