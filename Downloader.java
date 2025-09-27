import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class Downloader extends JFrame {

    private JTextField urlField;
    private JTextField fileNameField;
    private JButton downloadButton;
    private JProgressBar progressBar;
    private JTextArea logArea;

    public Downloader() {
        setTitle("Aplikasi Downloader");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Warna nude / soft
        Color backgroundColor = new Color(255, 228, 225); // pink nude
        Color buttonColor = new Color(173, 216, 230);     // baby blue

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(backgroundColor);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel urlLabel = new JLabel("Masukkan URL:");
        urlField = new JTextField("https://example.com/file.zip", 30);

        JLabel fileLabel = new JLabel("Nama File Output:");
        fileNameField = new JTextField("hasil_download.zip", 30);

        downloadButton = new JButton("Download");
        downloadButton.setBackground(buttonColor);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);

        logArea = new JTextArea(8, 40);
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(urlLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 0;
        panel.add(urlField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(fileLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 1;
        panel.add(fileNameField, gbc);

        gbc.gridx = 1; gbc.gridy = 2;
        panel.add(downloadButton, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
        panel.add(progressBar, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        panel.add(scrollPane, gbc);

        add(panel);

        // Event klik tombol download
        downloadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String fileURL = urlField.getText().trim();
                String saveFilePath = fileNameField.getText().trim();
                new Thread(() -> downloadFile(fileURL, saveFilePath)).start();
            }
        });
    }

    private void downloadFile(String fileURL, String saveFilePath) {
        try {
            URL url = new URL(fileURL);
            HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();

            // Tambahkan User-Agent supaya server tidak menolak
            httpConn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36");

            int responseCode = httpConn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                int contentLength = httpConn.getContentLength();

                InputStream inputStream = httpConn.getInputStream();
                FileOutputStream outputStream = new FileOutputStream(saveFilePath);

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalRead = 0;
                int percentCompleted;

                logArea.append("Mengunduh: " + fileURL + "\n");
                if (contentLength > 0) {
                    logArea.append("Ukuran file: " + contentLength / 1024 + " KB\n");
                }

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    if (contentLength > 0) {
                        percentCompleted = (int) (totalRead * 100 / contentLength);
                        progressBar.setValue(percentCompleted);
                    } else {
                        progressBar.setIndeterminate(true);
                    }
                }

                outputStream.close();
                inputStream.close();

                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                logArea.append("File berhasil diunduh ke: " + saveFilePath + "\n\n");
            } else {
                logArea.append("Tidak bisa mengunduh file. Server balas kode: " + responseCode + "\n\n");
            }
            httpConn.disconnect();
        } catch (Exception ex) {
            logArea.append("Error: " + ex.getMessage() + "\n\n");
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new Downloader().setVisible(true);
        });
    }
}

