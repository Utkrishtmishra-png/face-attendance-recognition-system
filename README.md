# Face Recognition Attendance System (Java)

A desktop attendance app. A webcam watches the room, recognizes registered students, and marks each one present
**once per day**. Records live in a local SQLite file and can be exported to CSV.

- **Language / UI:** Java 17, Swing
- **Vision:** JavaCV (OpenCV) - Haar cascade face detection + LBPH face recognition
- **Storage:** SQLite (`data/attendance.db`) + face crops (`data/faces/<id>/*.png`)
- **No cloud, no external services.** All biometric data stays on the machine.

## Features

| Tab | What it does |
|---|---|
| Live Attendance | Live camera, boxes + names on faces, auto-marks attendance, shows today's list |
| Register Student | Enter roll no. + name, captures 30 face samples, retrains the model, delete students |
| Reports | Date-range report table, CSV export |

A person must be recognized in 3 consecutive checks before being marked (reduces false marks).

## Requirements

- JDK 17+ (to build and run)
- Maven 3.8+ (to build)
- A webcam
- Internet access for the first build (Maven downloads JavaCV/OpenCV natives, ~100-200 MB)

## Build

```bash
./build.sh            # Windows: build.bat
# or: mvn clean package
```

Output: `target/face-attendance.jar` (a self-contained "fat" jar).
The OS/CPU is auto-detected. To force one: `mvn clean package -Djavacpp.platform=windows-x86_64`
(others: `linux-x86_64`, `linux-arm64`, `macosx-x86_64`, `macosx-arm64`).

## Run

```bash
./run.sh              # Windows: run.bat
# or: java -jar target/face-attendance.jar
```

### Usage

1. **Register Student** tab: type roll number and name, click *Capture face samples*, look at the camera
   (one face only, slightly vary your head angle) until the bar fills. The model retrains automatically.
2. **Live Attendance** tab: students are recognized and marked automatically.
3. **Reports** tab: pick dates, *Load*, then *Export CSV*.

### Configuration (JVM system properties)

| Property | Default | Meaning |
|---|---|---|
| `-Dattendance.data` | `./data` | Folder for database and face images (`~` = home dir) |
| `-Dattendance.camera` | `0` | Camera index (try 1, 2 for external cams) |
| `-Dattendance.threshold` | `70` | LBPH distance limit. **Lower = stricter** (fewer false matches, more misses). Try 50-80 |

## Deployment options

### 1. Fat JAR (simplest)
Copy `face-attendance.jar` to the target machine (needs JRE 17+ and a webcam) and run
`java -Xmx1g -Dattendance.data=/path/to/data -jar face-attendance.jar`.
Build on the same OS/CPU family as the target, or pass `-Djavacpp.platform=...`.

### 2. Native installer (no Java needed on target machines)
Run on the OS you are targeting, after `mvn package`:

```bash
# Windows (needs WiX Toolset for exe/msi)
jpackage --type exe --name FaceAttendance --input target --main-jar face-attendance.jar \
  --java-options "-Xmx1g" --java-options "-Dattendance.data=~/FaceAttendanceData" --win-shortcut --win-menu

# Linux (.deb)
jpackage --type deb --name face-attendance --input target --main-jar face-attendance.jar \
  --java-options "-Xmx1g" --java-options "-Dattendance.data=~/FaceAttendanceData"

# macOS (.dmg)
jpackage --type dmg --name FaceAttendance --input target --main-jar face-attendance.jar \
  --java-options "-Xmx1g" --java-options "-Dattendance.data=~/FaceAttendanceData"
```

### 3. Docker (Linux hosts with X11)
This is a GUI app, so the container needs your display and camera:

```bash
docker build -t face-attendance .
xhost +local:docker
docker run --rm -it \
  --device /dev/video0 \
  -e DISPLAY=$DISPLAY -v /tmp/.X11-unix:/tmp/.X11-unix \
  -v "$PWD/data:/app/data" \
  face-attendance
```
Docker Desktop on Windows/macOS cannot pass webcams through, so use options 1 or 2 there.

### 4. Classroom kiosk / auto-start
- **Windows:** put a shortcut to `run.bat` in `shell:startup`.
- **Linux / Raspberry Pi (use `linux-arm64`):** create `~/.config/autostart/face-attendance.desktop`:
  ```
  [Desktop Entry]
  Type=Application
  Name=Face Attendance
  Exec=/path/to/run.sh
  ```
- **Backups:** copy the `data/` folder (it holds everything).

## Accuracy tips

- Good, even front lighting; avoid strong backlight.
- Register with the same camera and position used for attendance.
- Capture 30 samples with small head turns; re-register anyone who is often missed.
- If strangers get matched, lower the threshold; if known students are "Unknown", raise it.

## Project layout

```
pom.xml, build.sh/.bat, run.sh/.bat, Dockerfile
src/main/java/com/attendance/
  App.java              entry point, wiring
  MainFrame.java        tabbed window
  CameraPanel.java      webcam capture + overlay drawing
  FaceEngine.java       detection, LBPH training/prediction
  Db.java               SQLite access
  AttendancePanel.java  live attendance tab
  RegisterPanel.java    registration tab
  ReportsPanel.java     reports + CSV export
src/main/resources/haarcascade_frontalface_default.xml   (OpenCV, BSD licence)
```

## Troubleshooting

| Problem | Fix |
|---|---|
| "Camera error" / black view | Try `-Dattendance.camera=1`; close other apps using the camera; macOS: allow camera for your terminal/app in System Settings > Privacy |
| `UnsatisfiedLinkError` | Wrong platform natives. Rebuild with `-Djavacpp.platform=<your platform>` |
| Linux: missing `.so` | `sudo apt install libgomp1 libgl1 libglib2.0-0` |
| Headless server | Swing needs a display; use a desktop machine or X11 forwarding |
| Build slow first time | Native libraries are large; later builds are cached |

## Limitations and responsible use

- LBPH is a classic, lightweight method. It is **not** spoof-proof: a printed photo or phone screen can fool it
  (no liveness detection), and accuracy drops with poor light or large pose changes.
- Face data is biometric personal data. Get consent from students/parents, tell them what is stored and why,
  restrict access to the `data/` folder, and check your local rules (e.g. India's DPDP Act, GDPR) before using it
  in a real school or workplace.
- For high-stakes use, consider a deep-learning recognizer plus liveness checks.
