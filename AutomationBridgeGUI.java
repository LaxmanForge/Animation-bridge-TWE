import java.util.*;
import java.awt.*;
import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// --- NEW IMPORTS FOR PHASE 3 ---
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;

public class AutomationBridgeGUI {
    
    // Tracks the folder we just downloaded so the server knows what to zip
    private static Path latestExportDir = null;

    public static void main(String[] args) {
        JLabel headerLabel = new JLabel("Automation Bridge Setup");
        headerLabel.setBounds(0, 15, 500, 40);
        headerLabel.setHorizontalAlignment(JLabel.CENTER);
        headerLabel.setVerticalAlignment(JLabel.CENTER);

        JFrame frame = new JFrame("Automation Bridge GUI");
        frame.setSize(500, 400); // Expanded slightly for the new button
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(null);

        ImageIcon icon = new ImageIcon("The-World_.png");
        frame.setIconImage(icon.getImage());

        Color windowBg = new Color(245, 246, 250);
        Color componentBg = Color.WHITE;
        Color accentColor = new Color(0, 120, 215);
        Color textColor = new Color(50, 50, 50);

        frame.getContentPane().setBackground(windowBg);
        headerLabel.setForeground(textColor);

        Font projectFont = new Font("SansSerif", Font.BOLD, 20); 
        try {
            Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("JetBrainsMono-variableFont.ttf"));
            projectFont = customFont.deriveFont(Font.BOLD, 20f);
        } catch (FontFormatException | IOException e) {
            System.out.println("Warning: Custom font not found. Using fallback.");
        }
        headerLabel.setFont(projectFont);
        frame.add(headerLabel);

        JTextField linkInput = new JTextField();
        linkInput.setBounds(30, 70, 290, 35);
        linkInput.setFont(projectFont.deriveFont(Font.PLAIN, 14f));
        linkInput.setBackground(componentBg);
        linkInput.setForeground(textColor);
        linkInput.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        frame.add(linkInput);

        JButton downloadButton = new JButton("Start Setup");
        downloadButton.setBounds(340, 70, 130, 35);
        downloadButton.setFont(projectFont.deriveFont(Font.BOLD, 14f));
        downloadButton.setBackground(accentColor);
        downloadButton.setForeground(Color.WHITE);
        downloadButton.setFocusPainted(false);
        frame.add(downloadButton);

        // --- NEW BUTTON: SEND TO PHONE ---
        JButton sendButton = new JButton("Send to Phone");
        sendButton.setBounds(340, 115, 130, 35);
        sendButton.setFont(projectFont.deriveFont(Font.BOLD, 12f));
        sendButton.setBackground(new Color(40, 167, 69)); // WhatsApp Green
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setEnabled(false); // Locked until download is done
        frame.add(sendButton);

        JTextArea consoleOutput = new JTextArea();
        consoleOutput.setEditable(false);
        consoleOutput.setFont(projectFont.deriveFont(Font.PLAIN, 12f));
        consoleOutput.setBackground(componentBg);
        consoleOutput.setForeground(textColor);
        consoleOutput.setText("Ready to install. Awaiting valid Telegram link...\n");

        JScrollPane scrollPane = new JScrollPane(consoleOutput);
        scrollPane.setBounds(30, 165, 440, 170);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(210, 210, 210), 1));
        frame.add(scrollPane);

        // Download Action
        downloadButton.addActionListener(e -> {
            String link = linkInput.getText().trim();
            if (link.isEmpty()) {
                consoleOutput.append("-> Error: Please provide a valid Telegram link.\n");
                return;
            }

            downloadButton.setEnabled(false);
            sendButton.setEnabled(false);
            linkInput.setEnabled(false);
            consoleOutput.append("\n-> Starting setup for: " + link + "\n");

            new Thread(() -> {
                try {
                    executeDownloadLogic(link, consoleOutput, sendButton);
                } catch (Exception ex) {
                    safeLog(consoleOutput, "-> Process failed: " + ex.getMessage() + "\n");
                    ex.printStackTrace();
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        downloadButton.setEnabled(true);
                        linkInput.setEnabled(true);
                    });
                }
            }).start();
        });

        // --- NEW ACTION: START SERVER ---
        sendButton.addActionListener(e -> {
            sendButton.setEnabled(false); // Prevent multiple server instances
            startWifiServer(consoleOutput);
        });

        frame.setVisible(true);
    }

    // --- REAL BACKEND LOGIC ---
    private static void executeDownloadLogic(String link, JTextArea console, JButton sendButton) throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("config.properties")) {
            props.load(in);
        } catch (IOException e) {
            safeLog(console, "-> Error: config.properties file not found.\n");
            return;
        }
        
        String botToken = props.getProperty("BOT_TOKEN");
        if (botToken == null || botToken.trim().isEmpty()) {
            safeLog(console, "-> Error: BOT_TOKEN is missing or empty.\n");
            return;
        }

        String packName = link.substring(link.lastIndexOf('/') + 1);
        safeLog(console, "-> Connecting to Telegram API for pack: " + packName + "\n");

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        String setUrl = "https://api.telegram.org/bot" + botToken + "/getStickerSet?name=" + packName;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(setUrl)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        Set<String> fileIds = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"file_id\":\"([^\"]+)\"").matcher(response.body());
        while (m.find()) {
            fileIds.add(m.group(1));
        }

        if (fileIds.isEmpty()) {
            safeLog(console, "-> Error: No stickers found.\n");
            return;
        }

        Path dir = Paths.get(packName + "_Export");
        latestExportDir = dir; // Save globally for the Send button

        try (var files = Files.list(dir)) {
            if (Files.exists(dir) && files.findAny().isPresent()) {
                safeLog(console, "-> Notice: Folder already exists. Skipping re-download.\n");
                SwingUtilities.invokeLater(() -> sendButton.setEnabled(true));
                return;
            }
        } catch (IOException e) {}

        Files.createDirectories(dir);
        safeLog(console, "-> Found " + fileIds.size() + " stickers. Initiating Transcoder...\n");

        int count = 1;
        for (String fileId : fileIds) {
            String fileUrl = "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId;
            HttpRequest fileReq = HttpRequest.newBuilder().uri(URI.create(fileUrl)).build();
            HttpResponse<String> fileRes = client.send(fileReq, HttpResponse.BodyHandlers.ofString());

            Matcher pathMatcher = Pattern.compile("\"file_path\":\"([^\"]+)\"").matcher(fileRes.body());
            if (pathMatcher.find()) {
                String filePath = pathMatcher.group(1);
                String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;

                HttpRequest downloadReq = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build();
                String ext = filePath.substring(filePath.lastIndexOf('.'));
                Path savePath = dir.resolve("raw_sticker_" + count + ext);

                client.send(downloadReq, HttpResponse.BodyHandlers.ofFile(savePath));
                convertToWhatsAppFormat(savePath, dir, count, console);
                count++;
            }
        }
        
        generatePackJson(dir, packName, count, console);
        safeLog(console, "-> Setup Complete! Files are ready.\n");
        
        // Unlock the Send button!
        SwingUtilities.invokeLater(() -> sendButton.setEnabled(true));
    }

    // --- MEDIA TRANSCODER ENGINE ---
    private static void convertToWhatsAppFormat(Path rawFile, Path outputDir, int count, JTextArea console) {
        try {
            String fileName = "sticker_" + count + ".webp";
            Path outputPath = outputDir.resolve(fileName);
            
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", rawFile.toAbsolutePath().toString(),
                "-vcodec", "libwebp",
                "-vf", "scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=white@0.0",
                "-loop", "0", "-an", outputPath.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {} // Drain buffer
            }
            
            if (process.waitFor() == 0) {
                safeLog(console, "-> Converted & Resized: " + fileName + "\n");
                File fileToDelete = rawFile.toFile();
                if(!fileToDelete.delete()) fileToDelete.deleteOnExit(); 
            } else {
                safeLog(console, "-> Conversion failed for sticker " + count + "\n");
            }
        } catch (Exception e) {
            safeLog(console, "-> Transcoding error: " + e.getMessage() + "\n");
        }
    }

    // --- WHATSAPP METADATA BUNDLER ---
    private static void generatePackJson(Path dir, String packName, int totalStickers, JTextArea console) {
        try {
            safeLog(console, "-> Generating WhatsApp pack.json...\n");
            Path firstSticker = dir.resolve("sticker_1.webp");
            Path trayIcon = dir.resolve("tray_icon.webp");
            if (Files.exists(firstSticker)) {
                Files.copy(firstSticker, trayIcon, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            StringBuilder json = new StringBuilder();
            json.append("{\n  \"android_play_store_link\": \"\",\n  \"ios_app_store_link\": \"\",\n");
            json.append("  \"sticker_packs\": [\n    {\n");
            json.append("      \"identifier\": \"").append(packName.replaceAll("[^a-zA-Z0-9]", "")).append("\",\n");
            json.append("      \"name\": \"").append(packName).append("\",\n");
            json.append("      \"publisher\": \"Automation Bridge\",\n");
            json.append("      \"tray_image_file\": \"tray_icon.webp\",\n");
            json.append("      \"image_data_version\": \"1\",\n      \"avoid_cache\": false,\n      \"stickers\": [\n");

            for (int i = 1; i < totalStickers; i++) {
                json.append("        { \"image_file\": \"sticker_").append(i).append(".webp\", \"emojis\": [\"✨\"] }");
                if (i < totalStickers - 1) json.append(",");
                json.append("\n");
            }
            json.append("      ]\n    }\n  ]\n}");

            Files.writeString(dir.resolve("pack.json"), json.toString());
        } catch (Exception e) {}
    }

    // --- WI-FI SOCKET SERVER (PHASE 3) ---
    private static void startWifiServer(JTextArea console) {
        if (latestExportDir == null) return;

        new Thread(() -> {
            try {
                // 1. Zip the folder
                safeLog(console, "\n-> Zipping payload for transfer...\n");
                Path zipPath = Paths.get(latestExportDir.toString() + ".zip");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                    Files.walk(latestExportDir).filter(path -> !Files.isDirectory(path)).forEach(path -> {
                        try {
                            ZipEntry zipEntry = new ZipEntry(latestExportDir.relativize(path).toString());
                            zos.putNextEntry(zipEntry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {}
                    });
                }

                // 2. Start the Server
                try (ServerSocket serverSocket = new ServerSocket(8080)) {
                    String ip = InetAddress.getLocalHost().getHostAddress();
                    safeLog(console, "======================================\n");
                    safeLog(console, "🌐 SERVER LIVE! Waiting for phone...\n");
                    safeLog(console, "👉 Mobile App IP: " + ip + "\n");
                    safeLog(console, "👉 Mobile App Port: 8080\n");
                    safeLog(console, "======================================\n");

                    // 3. Wait for the phone to connect, then blast the file
                    Socket clientSocket = serverSocket.accept();
                    safeLog(console, "-> 📱 Phone connected! Beaming payload...\n");
                    
                    Files.copy(zipPath, clientSocket.getOutputStream());
                    clientSocket.getOutputStream().flush();
                    
                    safeLog(console, "-> ✅ Transfer 100% Complete!\n");
                }
            } catch (Exception e) {
                safeLog(console, "-> Network Error: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private static void safeLog(JTextArea console, String message) {
        SwingUtilities.invokeLater(() -> {
            console.append(message);
            console.setCaretPosition(console.getDocument().getLength());
        });
    }
}