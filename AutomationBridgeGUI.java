import java.util.*;
import java.awt.*;
import javax.swing.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AutomationBridgeGUI {

    // Stores the path of the most recently exported sticker folder
    private static Path latestExportDir = null;

    public static void main(String[] args) {
        // --- 1. GUI WINDOW SETUP ---
        JFrame frame = new JFrame("Automation Bridge GUI");
        frame.setSize(500, 420);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(null);

        // Matches Icon.png in your project directory
        ImageIcon icon = new ImageIcon("Icon.png");
        frame.setIconImage(icon.getImage());

        Color windowBg = new Color(245, 246, 250);
        Color componentBg = Color.WHITE;
        Color accentColor = new Color(0, 120, 215);
        Color textColor = new Color(50, 50, 50);
        frame.getContentPane().setBackground(windowBg);

        // Load custom font or fallback to SansSerif
        Font projectFont = new Font("SansSerif", Font.BOLD, 20);
        try {
            Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("JetBrainsMono-VariableFont.ttf"));
            projectFont = customFont.deriveFont(Font.BOLD, 20f);
        } catch (Exception e) {
            System.out.println("Using default system font.");
        }

        // Header Label
        JLabel headerLabel = new JLabel("Automation Bridge Setup", SwingConstants.CENTER);
        headerLabel.setBounds(0, 15, 500, 40);
        headerLabel.setForeground(textColor);
        headerLabel.setFont(projectFont);
        frame.add(headerLabel);

        // Input Text Field for Telegram Link
        JTextField linkInput = new JTextField();
        linkInput.setBounds(30, 70, 290, 35);
        linkInput.setFont(projectFont.deriveFont(Font.PLAIN, 13f));
        linkInput.setBackground(componentBg);
        linkInput.setForeground(textColor);
        linkInput.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        frame.add(linkInput);

        // Button 1: Start Download & Conversion
        JButton downloadButton = new JButton("Start Setup");
        downloadButton.setBounds(340, 70, 130, 35);
        downloadButton.setFont(projectFont.deriveFont(Font.BOLD, 13f));
        downloadButton.setBackground(accentColor);
        downloadButton.setForeground(Color.WHITE);
        downloadButton.setFocusPainted(false);
        frame.add(downloadButton);

        // Button 2: Send to Phone via Wi-Fi
        JButton sendButton = new JButton("Send to Phone");
        sendButton.setBounds(330, 115, 145, 35);
        sendButton.setFont(projectFont.deriveFont(Font.BOLD, 12f));
        sendButton.setBackground(new Color(40, 167, 69)); // WhatsApp Green
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setEnabled(false); // Locked until stickers are ready
        frame.add(sendButton);

        // Console Log Area
        JTextArea consoleOutput = new JTextArea("Ready. Paste a Telegram sticker link above...\n");
        consoleOutput.setEditable(false);
        consoleOutput.setFont(projectFont.deriveFont(Font.PLAIN, 12f));
        consoleOutput.setBackground(componentBg);
        consoleOutput.setForeground(textColor);

        JScrollPane scrollPane = new JScrollPane(consoleOutput);
        scrollPane.setBounds(30, 165, 440, 195);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(210, 210, 210), 1));
        frame.add(scrollPane);

        // --- 2. BUTTON LISTENERS ---
        downloadButton.addActionListener(e -> {
            String link = linkInput.getText().trim();
            if (link.isEmpty()) {
                safeLog(consoleOutput, "-> Error: Please provide a valid Telegram link.\n");
                return;
            }

            downloadButton.setEnabled(false);
            sendButton.setEnabled(false);
            linkInput.setEnabled(false);
            safeLog(consoleOutput, "\n-> Starting setup for: " + link + "\n");

            // Run networking and FFmpeg on a background thread so GUI doesn't freeze
            new Thread(() -> {
                try {
                    executeDownloadLogic(link, consoleOutput, sendButton);
                } catch (Exception ex) {
                    safeLog(consoleOutput, "-> Process failed: " + ex.getMessage() + "\n");
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        downloadButton.setEnabled(true);
                        linkInput.setEnabled(true);
                    });
                }
            }).start();
        });

        sendButton.addActionListener(e -> {
            sendButton.setEnabled(false);
            startWifiServer(consoleOutput, sendButton);
        });

        frame.setVisible(true);
    }

    // --- PHASE 1: TELEGRAM API DOWNLOADER ---
    private static void executeDownloadLogic(String link, JTextArea console, JButton sendButton) throws Exception {
        // Load Bot Token from config.properties
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("config.properties")) {
            props.load(in);
        } catch (IOException e) {
            safeLog(console, "-> Error: config.properties file not found.\n");
            return;
        }

        String botToken = props.getProperty("BOT_TOKEN");
        if (botToken == null || botToken.trim().isEmpty()) {
            safeLog(console, "-> Error: BOT_TOKEN is missing in config.properties.\n");
            return;
        }

        // Extract pack name from URL (e.g., https://t.me/addstickers/PackName -> PackName)
        String packName = link.replaceAll("/+$", "");
        packName = packName.substring(packName.lastIndexOf('/') + 1);
        safeLog(console, "-> Fetching Telegram pack: " + packName + "\n");

        HttpClient client = HttpClient.newHttpClient();
        String setUrl = "https://api.telegram.org/bot" + botToken + "/getStickerSet?name=" + packName;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(setUrl)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Remove thumbnail blocks first so we only grab full-resolution sticker file_ids
        String cleanJson = response.body().replaceAll("\"thumbnail\":\\{[^}]+\\}", "")
                                          .replaceAll("\"thumb\":\\{[^}]+\\}", "");

        Set<String> fileIds = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"file_id\":\"([^\"]+)\"").matcher(cleanJson);
        while (m.find()) {
            fileIds.add(m.group(1));
        }

        if (fileIds.isEmpty()) {
            safeLog(console, "-> Error: No stickers found. Check link or BOT_TOKEN.\n");
            return;
        }

        Path dir = Paths.get(packName + "_Export");
        latestExportDir = dir;

        // Skip download if folder already exists and has files
        if (Files.exists(dir) && Files.list(dir).findAny().isPresent()) {
            safeLog(console, "-> Notice: Pack already exported! Ready to send.\n");
            SwingUtilities.invokeLater(() -> sendButton.setEnabled(true));
            return;
        }

        Files.createDirectories(dir);
        // WhatsApp maximum limit is 30 stickers per pack
        int maxStickers = Math.min(fileIds.size(), 30);
        safeLog(console, "-> Found " + fileIds.size() + " stickers. Converting top " + maxStickers + "...\n");

        int count = 1;
        for (String fileId : fileIds) {
            if (count > 30) break; 

            // Step A: Ask Telegram for the file path on their servers
            String fileUrl = "https://api.telegram.org/bot" + botToken + "/getFile?file_id=" + fileId;
            HttpRequest fileReq = HttpRequest.newBuilder().uri(URI.create(fileUrl)).build();
            HttpResponse<String> fileRes = client.send(fileReq, HttpResponse.BodyHandlers.ofString());

            Matcher pathMatcher = Pattern.compile("\"file_path\":\"([^\"]+)\"").matcher(fileRes.body());
            if (pathMatcher.find()) {
                String filePath = pathMatcher.group(1);
                
                // Reject .tgs vector stickers because standard FFmpeg cannot decode Lottie JSON
                if (filePath.endsWith(".tgs")) {
                    safeLog(console, "-> Skipping unsupported .tgs vector sticker.\n");
                    continue;
                }

                // Step B: Download the actual raw sticker file (.webp or .webm)
                String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
                String ext = filePath.substring(filePath.lastIndexOf('.'));
                Path savePath = dir.resolve("raw_sticker_" + count + ext);

                HttpRequest downloadReq = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build();
                client.send(downloadReq, HttpResponse.BodyHandlers.ofFile(savePath));

                // Step C: Convert to WhatsApp format using FFmpeg
                if (convertToWhatsAppFormat(savePath, dir, count, console)) {
                    count++;
                }
            }
        }

        generatePackJson(dir, packName, count - 1, console);
        safeLog(console, "-> Setup Complete! Click 'Send to Phone' to transfer.\n");
        SwingUtilities.invokeLater(() -> sendButton.setEnabled(true));
    }

    // --- PHASE 2: FFMPEG MEDIA TRANSCODER ---
    private static boolean convertToWhatsAppFormat(Path rawFile, Path outputDir, int count, JTextArea console) {
        try {
            String fileName = "sticker_" + count + ".webp";
            Path outputPath = outputDir.resolve(fileName);

            // FFmpeg command: scales to 512x512, pads with transparent pixels, encodes as WebP
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", rawFile.toAbsolutePath().toString(),
                "-vcodec", "libwebp",
                "-vf", "scale=512:512:force_original_aspect_ratio=decrease,pad=512:512:(ow-iw)/2:(oh-ih)/2:color=white@0.0,fps=15",
                "-lossless", "0", "-q:v", "70", "-loop", "0", "-an",
                outputPath.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read FFmpeg output so the process doesn't hang
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {}
            }

            int exitCode = process.waitFor();
            Files.deleteIfExists(rawFile); // Clean up raw file

            if (exitCode == 0) {
                safeLog(console, "-> Converted (512x512 WebP): " + fileName + "\n");
                return true;
            } else {
                safeLog(console, "-> FFmpeg failed for sticker " + count + "\n");
                return false;
            }
        } catch (Exception e) {
            safeLog(console, "-> Transcoding error: " + e.getMessage() + "\n");
            return false;
        }
    }

   // --- PHASE 2.5: WHATSAPP METADATA & TRAY ICON ---
    private static void generatePackJson(Path dir, String packName, int totalStickers, JTextArea console) {
        try {
            safeLog(console, "-> Generating tray_icon.webp (96x96) & pack.json...\n");
            Path firstSticker = dir.resolve("sticker_1.webp");
            Path trayIcon = dir.resolve("tray_icon.webp");

            // Resize first sticker to 96x96 for WhatsApp's tray icon requirement
            if (Files.exists(firstSticker)) {
                ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", firstSticker.toAbsolutePath().toString(),
                    "-vf", "scale=96:96", trayIcon.toAbsolutePath().toString()
                );
                
                // CRITICAL FIX: Discard the output streams so FFmpeg doesn't fill the OS buffer and freeze
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                
                Process p = pb.start();
                p.waitFor();
            }

            // Build JSON structure required by WhatsApp
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"identifier\": \"").append(packName.replaceAll("[^a-zA-Z0-9]", "")).append("\",\n");
            json.append("  \"name\": \"").append(packName).append("\",\n");
            json.append("  \"publisher\": \"Automation Bridge\",\n");
            json.append("  \"tray_image_file\": \"tray_icon.webp\",\n");
            json.append("  \"image_data_version\": \"1\",\n");
            json.append("  \"avoid_cache\": false,\n");
            json.append("  \"stickers\": [\n");

            for (int i = 1; i <= totalStickers; i++) {
                json.append("    { \"image_file\": \"sticker_").append(i).append(".webp\", \"emojis\": [\"✨\"] }");
                if (i < totalStickers) json.append(",");
                json.append("\n");
            }
            json.append("  ]\n}");

            Files.writeString(dir.resolve("pack.json"), json.toString());
        } catch (Exception e) {
            safeLog(console, "-> JSON Metadata error: " + e.getMessage() + "\n");
        }
    }

    // --- PHASE 3: LOCAL WI-FI HTTP SERVER ---
    private static void startWifiServer(JTextArea console, JButton sendButton) {
        if (latestExportDir == null) return;

        new Thread(() -> {
            try {
                // 1. Compress the exported folder into a .zip file
                safeLog(console, "\n-> Zipping stickers for transfer...\n");
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

                byte[] zipBytes = Files.readAllBytes(zipPath);

                // 2. Bind to 0.0.0.0 so all network interfaces (including Hotspot) can access it
                try (ServerSocket serverSocket = new ServerSocket(8080, 10, InetAddress.getByName("0.0.0.0"))) {
                    safeLog(console, "========================================\n");
                    safeLog(console, "🌐 SERVER LIVE! Open your phone browser\n");
                    safeLog(console, "👉 Go to: http://10.53.13.76:8080\n");
                    safeLog(console, "========================================\n");

                    boolean transferred = false;
                    while (!transferred) {
                        try (Socket client = serverSocket.accept()) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                            String requestLine = in.readLine();

                            // Ignore empty socket connections or pre-connect pings from mobile browsers
                            if (requestLine == null || !requestLine.startsWith("GET")) {
                                continue;
                            }

                            // Read remaining HTTP request headers
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {}

                            // Stream the ZIP file with HTTP headers
                            OutputStream out = client.getOutputStream();
                            String httpHeaders = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/zip\r\n" +
                                    "Content-Disposition: attachment; filename=\"" + zipPath.getFileName() + "\"\r\n" +
                                    "Content-Length: " + zipBytes.length + "\r\n" +
                                    "Connection: close\r\n\r\n";

                            out.write(httpHeaders.getBytes());
                            out.write(zipBytes);
                            out.flush();

                            safeLog(console, "-> ✅ Transfer 100% Complete! File sent.\n");
                            transferred = true;
                        } catch (IOException clientEx) {
                            // If the browser drops a probe connection, keep listening
                        }
                    }
                }
            } catch (Exception e) {
                safeLog(console, "-> Server Error: " + e.getMessage() + "\n");
            } finally {
                SwingUtilities.invokeLater(() -> sendButton.setEnabled(true));
            }
        }).start();
    }

    // Thread-safe helper to update the Swing JTextArea from background threads
    private static void safeLog(JTextArea console, String message) {
        SwingUtilities.invokeLater(() -> {
            console.append(message);
            console.setCaretPosition(console.getDocument().getLength());
        });
    }
}