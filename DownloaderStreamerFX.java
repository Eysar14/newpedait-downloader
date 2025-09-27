import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.time.Duration; // ETA download
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// JavaFX
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.animation.PauseTransition;

public class DownloaderStreamerFX extends JFrame {

    // ===== UI utama
    private final JTextField urlField = new JTextField("https://example.com/file.mp4");
    private final JButton downloadBtn = new JButton("Download");
    private final JButton streamBtn = new JButton("Stream (Play URL)");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel speedLabel = new JLabel("Speed: -");
    private final JLabel etaLabel = new JLabel("ETA: -");

    private final DefaultListModel<Path> listModel = new DefaultListModel<>();
    private final JList<Path> resultList = new JList<>(listModel);
    private final JButton playLocalBtn = new JButton("Putar File");
    private final JButton deleteBtn = new JButton("Hapus File");
    private final JCheckBox overwriteCheck = new JCheckBox("Overwrite jika ada", false);

    // ===== Folder result
    private final Path resultDir = Paths.get("result");

    // ===== Downloader worker
    private DownloadWorker currentWorker;

    // ===== JavaFX Player area
    private final JPanel playerHost = new JPanel(new BorderLayout());
    private JFXPanel jfxPanel;
    private volatile MediaPlayer mediaPlayer;
    private volatile boolean fxReady = false;

    public DownloaderStreamerFX() {
        super("Downloader + Streamer (Swing + JavaFX, DataInputStream/DataOutputStream)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 680));
        setLocationRelativeTo(null);

        ensureResultDir();
        loadResultList();

        // ===== Root layout
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        // ===== TOP: URL + tombol
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(new JLabel("URL:"), BorderLayout.WEST);
        top.add(urlField, BorderLayout.CENTER);
        JPanel topBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        topBtns.add(streamBtn);
        topBtns.add(downloadBtn);
        top.add(topBtns, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        // ===== CENTER: Kiri (list) | Kanan (player + status)
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.35);
        root.add(split, BorderLayout.CENTER);

        // Left: daftar hasil
        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.add(new JLabel("Hasil di ./result"), BorderLayout.NORTH);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path p) {
                    String name = p.getFileName().toString();
                    String size = "-";
                    try { size = humanBytes(Files.size(p)); } catch (IOException ignored) {}
                    l.setText(name + "  [" + size + "]");
                }
                return l;
            }
        });
        left.add(new JScrollPane(resultList), BorderLayout.CENTER);
        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        leftBtns.add(playLocalBtn);
        leftBtns.add(deleteBtn);
        left.add(leftBtns, BorderLayout.SOUTH);
        split.setLeftComponent(left);

        // Right: player + status
        JPanel right = new JPanel(new BorderLayout(8, 8));
        jfxPanel = new JFXPanel(); // init toolkit JavaFX
        playerHost.add(jfxPanel, BorderLayout.CENTER);
        right.add(playerHost, BorderLayout.CENTER);

        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        progressBar.setStringPainted(true);
        JPanel infoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        infoRow.add(speedLabel);
        infoRow.add(etaLabel);
        status.add(progressBar);
        status.add(Box.createVerticalStrut(6));
        status.add(infoRow);
        status.add(Box.createVerticalStrut(6));
        status.add(overwriteCheck);
        right.add(status, BorderLayout.SOUTH);
        split.setRightComponent(right);

        // Events
        downloadBtn.addActionListener(e -> startDownload());
        streamBtn.addActionListener(e -> streamURL());
        playLocalBtn.addActionListener(e -> playSelectedFile());
        deleteBtn.addActionListener(e -> deleteSelectedFile());
        playLocalBtn.setEnabled(false);
        resultList.addListSelectionListener(e -> playLocalBtn.setEnabled(!resultList.isSelectionEmpty()));

        // Init scene JavaFX
        initFXScene();
    }

    // ====== Folder & List ======
    private void ensureResultDir() {
        try { Files.createDirectories(resultDir); }
        catch (IOException e) { throw new RuntimeException("Gagal membuat ./result: " + e.getMessage(), e); }
    }

    private void loadResultList() {
        listModel.clear();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(resultDir)) {
            for (Path p : ds) if (Files.isRegularFile(p)) listModel.addElement(p);
        } catch (IOException ignored) {}
    }

    private String[] splitNameExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return new String[]{fileName, ""};
        return new String[]{fileName.substring(0, dot), fileName.substring(dot)}; // [base, .ext]
    }

    private Path resolveDuplicate(Path basePath) {
        if (!Files.exists(basePath)) return basePath;
        String fileName = basePath.getFileName().toString();
        String[] parts = splitNameExt(fileName);
        String base = parts[0], ext = parts[1];
        int i = 1;
        while (true) {
            Path p = basePath.getParent().resolve(base + "(" + i + ")" + ext);
            if (!Files.exists(p)) return p;
            i++;
        }
    }

    // ====== JavaFX Player ======
    private void initFXScene() {
        Platform.runLater(() -> {
            try {
                // Root untuk panel embedded (bukan fullscreen)
                BorderPane base = new BorderPane();

                // MediaView + binding ukuran agar proporsional
                MediaView mediaView = new MediaView();
                mediaView.setPreserveRatio(true);
                mediaView.fitWidthProperty().bind(base.widthProperty());
                mediaView.fitHeightProperty().bind(base.heightProperty().subtract(70));
                base.setCenter(mediaView);

                // Kontrol dasar (selalu terlihat di panel embedded)
                javafx.scene.control.Button back5 = new javafx.scene.control.Button("<< 5s");
                javafx.scene.control.Button play = new javafx.scene.control.Button("Play");
                javafx.scene.control.Button pause = new javafx.scene.control.Button("Pause");
                javafx.scene.control.Button stop = new javafx.scene.control.Button("Stop");
                javafx.scene.control.Button fwd5 = new javafx.scene.control.Button(">> 5s");
                javafx.scene.control.Button fullscreen = new javafx.scene.control.Button("Fullscreen");

                Slider time = new Slider(0, 100, 0);
                time.setDisable(true);

                // Volume
                Label volIcon = new Label("\uD83D\uDD0A"); // speaker
                Slider volume = new Slider(0, 1, 0.7);
                volume.setPrefWidth(120);

                Label timeLabel = new Label("00:00 / 00:00");

                play.setOnAction(ae -> { if (mediaPlayer != null) mediaPlayer.play(); });
                pause.setOnAction(ae -> { if (mediaPlayer != null) mediaPlayer.pause(); });
                stop.setOnAction(ae -> { if (mediaPlayer != null) mediaPlayer.stop(); });
                back5.setOnAction(ae -> {
                    if (mediaPlayer != null) {
                        javafx.util.Duration cur = mediaPlayer.getCurrentTime();
                        mediaPlayer.seek(cur.subtract(javafx.util.Duration.seconds(5)));
                    }
                });
                fwd5.setOnAction(ae -> {
                    if (mediaPlayer != null) {
                        javafx.util.Duration cur = mediaPlayer.getCurrentTime();
                        mediaPlayer.seek(cur.add(javafx.util.Duration.seconds(5)));
                    }
                });
                fullscreen.setOnAction(ae -> { if (mediaPlayer != null) openFullscreen(mediaView, mediaPlayer); });
                volIcon.setOnMouseClicked(e -> {
                    if (mediaPlayer == null) return;
                    if (mediaPlayer.getVolume() > 0) { mediaPlayer.setVolume(0); volume.setValue(0); volIcon.setText("\uD83D\uDD07"); }
                    else { mediaPlayer.setVolume(0.7); volume.setValue(0.7); volIcon.setText("\uD83D\uDD0A"); }
                });
                volume.valueProperty().addListener((obs, o, v) -> {
                    if (mediaPlayer != null) {
                        mediaPlayer.setVolume(v.doubleValue());
                        volIcon.setText(v.doubleValue() > 0 ? "\uD83D\uDD0A" : "\uD83D\uDD07"); // speaker or mute
                    }
                });

                time.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
                    if (!isChanging && mediaPlayer != null) {
                        javafx.util.Duration total = mediaPlayer.getTotalDuration();
                        if (!total.isUnknown() && total.toMillis() > 0) {
                            double frac = time.getValue() / 100.0;
                            mediaPlayer.seek(total.multiply(frac));
                        }
                    }
                });

                HBox controls = new HBox(8, back5, play, pause, stop, fwd5, fullscreen, time, timeLabel, volIcon, volume);
                controls.setAlignment(Pos.CENTER_LEFT);
                controls.setStyle("-fx-padding: 8; -fx-background-color: rgba(0,0,0,0.06);");
                base.setBottom(controls);

                // Double click di mediaView untuk fullscreen
                mediaView.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && mediaPlayer != null) {
                        openFullscreen(mediaView, mediaPlayer);
                    }
                });

                // Simpan referensi untuk diakses dari Swing
                jfxPanel.putClientProperty("mediaView", mediaView);
                jfxPanel.putClientProperty("timeSlider", time);
                jfxPanel.putClientProperty("timeLabel", timeLabel);
                jfxPanel.putClientProperty("volume", volume);

                Scene scene = new Scene(base, 700, 420);
                jfxPanel.setScene(scene);
                fxReady = true;
            } catch (Throwable t) {
                fxReady = false;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        DownloaderStreamerFX.this,
                        "Gagal init JavaFX: " + t,
                        "JavaFX Error", JOptionPane.ERROR_MESSAGE
                ));
            }
        });
    }

    /** Fullscreen overlay: controls auto-hide saat mouse idle; double-click / ESC untuk keluar. */
    private void openFullscreen(MediaView mainView, MediaPlayer player) {
        Platform.runLater(() -> {
            // Detach sementara dari view utama
            mainView.setMediaPlayer(null);

            MediaView mv = new MediaView(player);
            mv.setPreserveRatio(true);

            // Overlay dengan auto-hide controls
            StackPane root = new StackPane();
            BorderPane videoPane = new BorderPane(mv);
            root.getChildren().add(videoPane);

            // Controls bar (mirip yang embedded)
            javafx.scene.control.Button back5 = new javafx.scene.control.Button("<< 5s");
            javafx.scene.control.Button play = new javafx.scene.control.Button("Play");
            javafx.scene.control.Button pause = new javafx.scene.control.Button("Pause");
            javafx.scene.control.Button stop = new javafx.scene.control.Button("Stop");
            javafx.scene.control.Button fwd5 = new javafx.scene.control.Button(">> 5s");

            Slider time = new Slider(0, 100, 0);
            Label timeLabel = new Label("00:00 / 00:00");
            Label volIcon = new Label("\uD83D\uDD0A");
            Slider volume = new Slider(0, 1, player.getVolume());

            HBox bar = new HBox(10, back5, play, pause, stop, fwd5, time, timeLabel, volIcon, volume);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setStyle("-fx-padding: 50; -fx-background-color: rgba(0,0,0,0.6);");
            bar.setVisible(false);
            root.getChildren().add(bar);
            StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);

            // Binding ukuran
            mv.fitWidthProperty().bind(root.widthProperty());
            mv.fitHeightProperty().bind(root.heightProperty());

            // Wiring kontrol
            play.setOnAction(ae -> player.play());
            pause.setOnAction(ae -> player.pause());
            stop.setOnAction(ae -> player.stop());
            back5.setOnAction(ae -> player.seek(player.getCurrentTime().subtract(javafx.util.Duration.seconds(5))));
            fwd5.setOnAction(ae -> player.seek(player.getCurrentTime().add(javafx.util.Duration.seconds(5))));
            volume.valueProperty().addListener((o, ov, nv) -> {
                player.setVolume(nv.doubleValue());
                volIcon.setText(nv.doubleValue() > 0 ? "\uD83D\uDD0A" : "\uD83D\uDD07");
            });
            volIcon.setOnMouseClicked(e -> {
                if (player.getVolume() > 0) { player.setVolume(0); volume.setValue(0); volIcon.setText("\uD83D\uDD07"); }
                else { player.setVolume(0.7); volume.setValue(0.7); volIcon.setText("\uD83D\uDD0A"); }
            });

            // Time & label
            time.setDisable(false);
            time.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
                if (!isChanging) {
                    javafx.util.Duration total = player.getTotalDuration();
                    if (!total.isUnknown() && total.toMillis() > 0) {
                        player.seek(total.multiply(time.getValue() / 100.0));
                    }
                }
            });

            player.currentTimeProperty().addListener((obs, oldV, newV) -> {
                javafx.util.Duration total = player.getTotalDuration();
                if (!total.isUnknown() && total.toMillis() > 0) {
                    double frac = newV.toMillis() / total.toMillis();
                    double pct = Math.max(0, Math.min(100, frac * 100.0));
                    time.setValue(pct);
                    timeLabel.setText(formatTime(newV, total));
                }
            });
            player.setOnReady(() -> {
                javafx.util.Duration total = player.getTotalDuration();
                timeLabel.setText(formatTime(javafx.util.Duration.ZERO, total));
            });

            // Auto-hide behavior
            PauseTransition hide = new PauseTransition(javafx.util.Duration.seconds(2.5));
            Runnable showControls = () -> {
                bar.setVisible(true);
                hide.stop();
                hide.setOnFinished(ev -> bar.setVisible(false));
                hide.playFromStart();
            };
            root.setOnMouseMoved(e -> showControls.run());
            root.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    // double-click to exit fullscreen
                    Stage st = (Stage) root.getScene().getWindow();
                    st.close();
                }
            });
            showControls.run(); // show at start

            Scene scene = new Scene(root, 1280, 720, javafx.scene.paint.Color.BLACK);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.setTitle("Fullscreen");
            stage.setFullScreenExitHint("ESC untuk keluar");
            stage.setFullScreen(true);

            // ESC / close → kembalikan player ke view utama
            scene.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) stage.close();
            });
            stage.setOnHidden(e -> mainView.setMediaPlayer(player));

            stage.show();
        });
    }

    private static String formatTime(javafx.util.Duration elapsed, javafx.util.Duration duration) {
        int intElapsed = (int) Math.floor(elapsed.toSeconds());
        int h = intElapsed / 3600; intElapsed -= h * 3600;
        int m = intElapsed / 60;
        int s = intElapsed - m * 60;
        String elapsedStr = (h > 0) ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);

        if (duration == null || duration.isUnknown()) return elapsedStr + " / --:--";
        int intDur = (int) Math.floor(duration.toSeconds());
        int dh = intDur / 3600; intDur -= dh * 3600;
        int dm = intDur / 60;
        int ds = intDur - dm * 60;
        String totalStr = (dh > 0) ? String.format("%d:%02d:%02d", dh, dm, ds) : String.format("%02d:%02d", dm, ds);
        return elapsedStr + " / " + totalStr;
    }

    private void setMediaSource(String uri) {
        if (!fxReady) {
            JOptionPane.showMessageDialog(this, "JavaFX belum siap.");
            return;
        }
        Platform.runLater(() -> {
            try {
                if (mediaPlayer != null) {
                    try { mediaPlayer.stop(); } catch (Exception ignored) {}
                    try { mediaPlayer.dispose(); } catch (Exception ignored) {}
                }
                Media media = new Media(uri);
                mediaPlayer = new MediaPlayer(media);

                MediaView mv = (MediaView) jfxPanel.getClientProperty("mediaView");
                Slider time = (Slider) jfxPanel.getClientProperty("timeSlider");
                Label timeLabel = (Label) jfxPanel.getClientProperty("timeLabel");
                Slider volume = (Slider) jfxPanel.getClientProperty("volume");

                mv.setMediaPlayer(mediaPlayer);
                mediaPlayer.setVolume(volume.getValue());

                mediaPlayer.setOnReady(() -> {
                    javafx.util.Duration total = mediaPlayer.getTotalDuration();
                    boolean known = !total.isUnknown() && total.toMillis() > 0;
                    Platform.runLater(() -> {
                        time.setDisable(!known);
                        timeLabel.setText(formatTime(javafx.util.Duration.ZERO, total));
                    });
                });

                mediaPlayer.currentTimeProperty().addListener((obs, oldV, newV) -> {
                    javafx.util.Duration total = mediaPlayer.getTotalDuration();
                    if (!total.isUnknown() && total.toMillis() > 0) {
                        double frac = newV.toMillis() / total.toMillis();
                        double pct = Math.max(0, Math.min(100, frac * 100.0));
                        Platform.runLater(() -> {
                            time.setValue(pct);
                            timeLabel.setText(formatTime(newV, total));
                        });
                    }
                });

                mediaPlayer.play(); // auto-play
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        DownloaderStreamerFX.this,
                        "Gagal memutar: " + t.getMessage(),
                        "Player Error", JOptionPane.ERROR_MESSAGE
                ));
            }
        });
    }

    private void streamURL() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Masukkan URL terlebih dahulu.");
            return;
        }
        setMediaSource(url); // mainkan langsung dari URL
    }

    private void playSelectedFile() {
        Path p = resultList.getSelectedValue();
        if (p == null) return;
        setMediaSource(p.toUri().toString());
    }

    private void deleteSelectedFile() {
        Path p = resultList.getSelectedValue();
        if (p == null) return;
        int c = JOptionPane.showConfirmDialog(this, "Hapus file ini?\n" + p.getFileName(),
                "Konfirmasi", JOptionPane.YES_NO_OPTION);
        if (c != JOptionPane.YES_OPTION) return;
        try {
            Files.deleteIfExists(p);
            loadResultList();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Gagal menghapus: " + e.getMessage());
        }
    }

    // ====== Download ======
    private void startDownload() {
        if (currentWorker != null && !currentWorker.isDone()) {
            int choice = JOptionPane.showConfirmDialog(this, "Masih ada download berjalan. Batalkan?",
                    "Konfirmasi", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.NO_OPTION) return;
            currentWorker.cancel(true);
        }

        String urlText = urlField.getText().trim();
        if (urlText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Masukkan URL terlebih dahulu.");
            return;
        }

        try {
            String suggested = suggestFileNameFromUrl(urlText);
            Path savePath = resultDir.resolve(suggested);
            if (Files.exists(savePath) && !overwriteCheck.isSelected()) {
                savePath = resolveDuplicate(savePath);
            }

            progressBar.setValue(0);
            progressBar.setIndeterminate(true);
            speedLabel.setText("Speed: -");
            etaLabel.setText("ETA: -");

            downloadBtn.setEnabled(false);
            streamBtn.setEnabled(false);

            currentWorker = new DownloadWorker(urlText, savePath);
            currentWorker.execute();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Gagal mulai download: " + e.getMessage());
        }
    }

    private class DownloadWorker extends SwingWorker<Path, DownloadProgress> {
        private final String urlText;
        private final Path targetPath;
        private long contentLength = -1;
        private final List<SpeedSample> samples = new CopyOnWriteArrayList<>();
        private long totalRead = 0;

        DownloadWorker(String urlText, Path targetPath) {
            this.urlText = urlText;
            this.targetPath = targetPath;
        }

        @Override
        protected Path doInBackground() throws Exception {
            URL url = new URL(urlText);
            URLConnection conn = url.openConnection();

            if (conn instanceof HttpURLConnection http) {
                http.setInstanceFollowRedirects(true);
                http.setRequestProperty("User-Agent", "DownloaderStreamerFX/1.0");
                int code = http.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = http.getHeaderField("Location");
                    if (loc != null) {
                        http.disconnect();
                        url = new URL(loc);
                    }
                }
            }
            conn = url.openConnection();
            contentLength = conn.getContentLengthLong();

            try (InputStream rawIn = conn.getInputStream();
                 BufferedInputStream bis = new BufferedInputStream(rawIn, 64 * 1024);
                 DataInputStream din = new DataInputStream(bis);
                 OutputStream fos = Files.newOutputStream(targetPath,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 64 * 1024);
                 DataOutputStream dout = new DataOutputStream(bos)) {

                byte[] buffer = new byte[64 * 1024];
                int n;
                samples.clear();
                totalRead = 0;

                publish(new DownloadProgress(0, 0.0, Duration.ZERO, contentLength));

                while (!isCancelled() && (n = din.read(buffer)) != -1) {
                    dout.write(buffer, 0, n);
                    totalRead += n;

                    // kecepatan: window 3 detik
                    samples.add(new SpeedSample(System.currentTimeMillis(), totalRead));
                    trimOldSamples();

                    double speedBps = computeSpeedBps();
                    Duration eta = computeETA(speedBps);

                    int percent = (contentLength > 0)
                            ? (int) Math.min(100, (totalRead * 100L) / contentLength)
                            : -1;

                    publish(new DownloadProgress(percent, speedBps, eta, contentLength));
                }
                dout.flush();
            }

            if (isCancelled()) {
                try { Files.deleteIfExists(targetPath); } catch (IOException ignored) {}
                return null;
            }
            return targetPath;
        }

        @Override
        protected void process(List<DownloadProgress> chunks) {
            DownloadProgress last = chunks.get(chunks.size() - 1);
            if (last.percent() >= 0) {
                progressBar.setIndeterminate(false);
                progressBar.setValue(last.percent());
                progressBar.setString(last.percent() + "%");
            } else {
                progressBar.setIndeterminate(true);
                progressBar.setString("Mengunduh...");
            }
            speedLabel.setText("Speed: " + humanSpeed(last.speedBps()));
            etaLabel.setText("ETA: " + humanETA(last.eta()));
        }

        @Override
        protected void done() {
            downloadBtn.setEnabled(true);
            streamBtn.setEnabled(true);
            try {
                Path p = get();
                if (p != null) {
                    progressBar.setValue(100);
                    progressBar.setString("Selesai");
                    etaLabel.setText("ETA: Selesai");
                    loadResultList();
                } else {
                    progressBar.setValue(0);
                    progressBar.setString("Dibatalkan");
                    etaLabel.setText("ETA: -");
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(DownloaderStreamerFX.this,
                        "Gagal mengunduh: " + ex.getMessage());
                progressBar.setValue(0);
                progressBar.setString("Gagal");
                speedLabel.setText("Speed: -");
                etaLabel.setText("ETA: -");
            }
        }

        private void trimOldSamples() {
            long cutoff = System.currentTimeMillis() - 3000;
            samples.removeIf(s -> s.tMillis < cutoff);
        }

        private double computeSpeedBps() {
            if (samples.size() < 2) return 0;
            SpeedSample first = samples.get(0);
            SpeedSample last = samples.get(samples.size() - 1);
            long dBytes = last.bytes - first.bytes;
            long dMillis = Math.max(1, last.tMillis - first.tMillis);
            return (dBytes * 1000.0) / dMillis;
        }

        private Duration computeETA(double speedBps) {
            if (contentLength <= 0 || speedBps <= 0) return Duration.ZERO;
            long remaining = contentLength - totalRead;
            long secs = (long) Math.ceil(remaining / speedBps);
            return Duration.ofSeconds(Math.max(0, secs));
        }
    }

    private record SpeedSample(long tMillis, long bytes) {}
    private record DownloadProgress(int percent, double speedBps, Duration eta, long contentLength) {}

    // ===== Utils =====
    private static String suggestFileNameFromUrl(String urlText) {
        try {
            URL u = new URL(urlText);
            String path = u.getPath();
            if (path == null || path.isEmpty() || path.endsWith("/")) return "downloaded_file";
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.isEmpty() ? "downloaded_file" : name;
        } catch (Exception e) {
            return "downloaded_file";
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return new DecimalFormat("#,##0.##").format(kb) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return new DecimalFormat("#,##0.##").format(mb) + " MB";
        double gb = mb / 1024.0;
        return new DecimalFormat("#,##0.##").format(gb) + " GB";
    }

    private static String humanSpeed(double bps) {
        if (bps <= 0) return "-";
        double kbps = bps / 1024.0;
        if (kbps < 1024) return new DecimalFormat("#,##0.0").format(kbps) + " KB/s";
        double mbps = kbps / 1024.0;
        return new DecimalFormat("#,##0.00").format(mbps) + " MB/s";
    }

    private static String humanETA(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) return "-";
        long s = d.getSeconds();
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format("%dh %02dm %02ds", h, m, sec);
        if (m > 0) return String.format("%dm %02ds", m, sec);
        return sec + "s";
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DownloaderStreamerFX().setVisible(true));
    }
}
