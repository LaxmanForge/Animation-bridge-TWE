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

public class AutomationBridgeGUI {
    public static void main(String[] args) {
        // Initialize header label
        JLabel headerLabel = new JLabel("Automation Bridge Setup");
        headerLabel.setBounds(0, 15, 500, 40);
        headerLabel.setHorizontalAlignment(JLabel.CENTER);
        headerLabel.setVerticalAlignment(JLabel.CENTER);

        // Frame configuration
        JFrame frame = new JFrame("Automation Bridge GUI");
        frame.setSize(500, 350);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(null);

        // App Icon
        ImageIcon icon = new ImageIcon("The-World_.png");
        frame.setIconImage(icon.getImage());

        // UI Theme Colors (Installer Aesthetic)
        Color windowBg = new Color(245, 246, 250);
        Color componentBg = Color.WHITE;
        Color accentColor = new Color(0, 120, 215);
        Color textColor = new Color(50, 50, 50);

        frame.getContentPane().setBackground(windowBg);
        headerLabel.setForeground(textColor);

        // Load and apply JetBrains Mono font
        Font projectFont = new Font("SansSerif", Font.BOLD, 20); // Fallback font
        try {
            Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("JetBrainsMono-variableFont.ttf"));
            projectFont = customFont.deriveFont(Font.BOLD, 20f);
        } catch (FontFormatException | IOException e) {
            e.printStackTrace();
            System.out.println("Warning: Custom font not found. Using fallback.");
        }
        headerLabel.setFont(projectFont);
        frame.add(headerLabel);

        // Link Input Field
        JTextField linkInput = new JTextField();
        linkInput.setBounds(30, 70, 290, 35);
        linkInput.setFont(projectFont.deriveFont(Font.PLAIN, 14f));
        linkInput.setBackground(componentBg);
        linkInput.setForeground(textColor);
        linkInput.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        frame.add(linkInput);

        // Process Trigger Button
        JButton downloadButton = new JButton("Start Setup");
        downloadButton.setBounds(340, 70, 110, 35);
        downloadButton.setFont(projectFont.deriveFont(Font.BOLD, 14f));
        downloadButton.setBackground(accentColor);
        downloadButton.setForeground(Color.WHITE);
        downloadButton.setFocusPainted(false);
        frame.add(downloadButton);

        // Status Console Area
        JTextArea consoleOutput = new JTextArea();
        consoleOutput.setEditable(false);
        consoleOutput.setFont(projectFont.deriveFont(Font.PLAIN, 12f));
        consoleOutput.setBackground(componentBg);
        consoleOutput.setForeground(textColor);
        consoleOutput.setText("Ready to install. Awaiting valid Telegram link...\n");

        JScrollPane scrollPane = new JScrollPane(consoleOutput);
        scrollPane.setBounds(30, 130, 420, 150);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(210, 210, 210), 1));
        frame.add(scrollPane);

        // Event listener for the setup process
        downloadButton.addActionListener(e -> {
            String link = linkInput.getText().trim();
            if (link.isEmpty()) {
                consoleOutput.append("-> Error: Please provide a valid Telegram link.\n");
                return;
            }

            downloadButton.setEnabled(false);
            linkInput.setEnabled(false);
            consoleOutput.append("\n-> Starting setup for: " + link + "\n");

            // Execute heavy process in a background thread to maintain UI responsiveness
            new Thread(() -> {
                try {
                    // Call the real backend logic
                    executeDownloadLogic(link, consoleOutput);
                } catch (Exception ex) {
                    safeLog(consoleOutput, "-> Process failed: " + ex.getMessage() + "\n");
                    ex.printStackTrace();
                } finally {
                    // Restore UI state safely
                    SwingUtilities.invokeLater(() -> {
                        downloadButton.setEnabled(true);
                        linkInput.setEnabled(true);
                    });
                }
            }).start();
        });

        // Display GUI
        frame.setVisible(true);
    }

    // --- REAL BACKEND LOGIC ---
    private static void executeDownloadLogic(String link, JTextArea console) throws Exception {
        // 1. Securely load the API token from the local file
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("config.properties")) {
            props.load(in);
        } catch (IOException e) {
            safeLog(console,
                    "-> Error: config.properties file not found. Create it in your project root to store your BOT_TOKEN.\n");
            return;
        }
        String botToken = props.getProperty("BOT_TOKEN");

        if (botToken == null || botToken.trim().isEmpty()) {
            safeLog(console, "-> Error: BOT_TOKEN is missing or empty in config.properties.\n");
            return;
        }

        // 2. Parse the pack name
        String packName = link.substring(link.lastIndexOf('/') + 1);
        safeLog(console, "-> Connecting to Telegram API for pack: " + packName + "\n");

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        // 3. Fetch pack metadata
        String setUrl = "https://api.telegram.org/bot" + botToken + "/getStickerSet?name=" + packName;
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(setUrl)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // 4. Extract file IDs
        Set<String> fileIds = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\"file_id\":\"([^\"]+)\"").matcher(response.body());
        while (m.find()) {
            fileIds.add(m.group(1));
        }

        if (fileIds.isEmpty()) {
            safeLog(console, "-> Error: No stickers found. Check link or API Token.\n");
            return;
        }

        // 5. Create local folder
        Path dir = Paths.get(packName + "_Export");
        try (var files = Files.list(dir)) {
            if (Files.exists(dir) && files.findAny().isPresent()) {
                safeLog(console,
                        "-> Notice: " + packName + "_Export already exists and is not empty. Skipping re-download.\n");
                return;
            }
        } catch (IOException e) {
            // Folder doesn't exist yet, proceed with creation
        }
        Files.createDirectories(dir);
        safeLog(console, "-> Created folder: " + dir.toAbsolutePath() + "\n");
        safeLog(console, "-> Found " + fileIds.size() + " stickers. Downloading raw files...\n");

        // 6. Download files
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
                safeLog(console, "-> Downloaded: raw_sticker_" + count + ext + "\n");
                count++;
            }
        }
        safeLog(console, "-> Setup Complete! Files are ready in the folder.\n");
    }

    // Helper method to safely append text to the console
    private static void safeLog(JTextArea console, String message) {
        SwingUtilities.invokeLater(() -> {
            console.append(message);
            console.setCaretPosition(console.getDocument().getLength());
        });
    }
}