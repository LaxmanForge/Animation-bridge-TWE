import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.swing.*;

/**
 * Automation Bridge (TEW)
 * Telegram sticker pack -> FFmpeg -> WhatsApp-ready pack -> phone over local
 * Wi-Fi.
 *
 * Single file, JDK 11+, no external libraries.
 * Compile: javac AutomationBridgeGUI.java
 * Run: java AutomationBridgeGUI
 *
 * The program has 4 parts:
 * 1. GUI - buildGui()
 * 2. Setup - runSetup() : Telegram API download + FFmpeg conversion +
 * contents.json
 * 3. Wi-Fi server - startServer(): zip the pack and serve it with the JDK's
 * built-in HttpServer
 * 4. Logger - log() : every step is shown in the window AND saved to
 * automation_bridge.log
 */
public class AutomationBridgeGUI {

    // ---------- Settings ----------
    static final int START_PORT = 8080; // server tries 8080..8089
    static final int MAX_STICKERS = 30; // WhatsApp limit per pack
    static final int MIN_STICKERS = 3; // WhatsApp minimum per pack
    static final long STATIC_LIMIT = 100 * 1024; // WhatsApp: static sticker <= 100 KB
    static final long ANIMATED_LIMIT = 500 * 1024; // WhatsApp: animated sticker <= 500 KB
    static final long TRAY_LIMIT = 50 * 1024; // WhatsApp: tray icon <= 50 KB
    static final String FALLBACK_EMOJI = "\uD83D\uDE00";
    static final Path LOG_FILE = Paths.get("automation_bridge.log");
    static final Pattern FILE_PATH = Pattern.compile("\"file_path\":\"([^\"]+)\"");

    // ---------- Shared state ----------
    static JTextArea console;
    static JTextField linkInput;
    static JButton downloadButton, sendButton;
    static String fontNote = null;

    static String botToken = ""; // kept only so the logger can hide it
    static Path exportDir = null; // e.g. MyPack_Export
    static boolean exportReady = false; // true after a successful setup
    static HttpServer server = null; // not null while the Wi-Fi server runs

    static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    // =====================================================================
    // 1. GUI
    // =====================================================================
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> logError("Unexpected crash in " + t.getName(), e));
        SwingUtilities.invokeAndWait(AutomationBridgeGUI::buildGui);
        new Thread(AutomationBridgeGUI::startupChecks).start();
    }

    static void buildGui() {
        JFrame frame = new JFrame("Automation Bridge GUI");
        frame.setSize(500, 420);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(null);

        if (new File("Icon.png").exists()) {
            frame.setIconImage(new ImageIcon("Icon.png").getImage());
        }

        Color windowBg = new Color(245, 246, 250);
        Color accent = new Color(0, 120, 215);
        Color textColor = new Color(50, 50, 50);
        frame.getContentPane().setBackground(windowBg);

        // Custom font, or fall back to SansSerif
        Font projectFont = new Font("SansSerif", Font.BOLD, 20);
        try {
            Font custom = Font.createFont(Font.TRUETYPE_FONT, new File("JetBrainsMono-VariableFont.ttf"));
            projectFont = custom.deriveFont(Font.BOLD, 20f);
        } catch (Exception e) {
            fontNote = "JetBrainsMono font not found - using the default system font.";
        }

        JLabel header = new JLabel("Automation Bridge Setup", SwingConstants.CENTER);
        header.setBounds(0, 15, 500, 40);
        header.setForeground(textColor);
        header.setFont(projectFont);
        frame.add(header);

        linkInput = new JTextField();
        linkInput.setBounds(30, 70, 290, 35);
        linkInput.setFont(projectFont.deriveFont(Font.PLAIN, 13f));
        linkInput.setForeground(textColor);
        linkInput.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        frame.add(linkInput);

        downloadButton = new JButton("Start Setup");
        downloadButton.setBounds(340, 70, 130, 35);
        downloadButton.setFont(projectFont.deriveFont(Font.BOLD, 13f));
        downloadButton.setBackground(accent);
        downloadButton.setForeground(Color.WHITE);
        downloadButton.setFocusPainted(false);
        frame.add(downloadButton);

        sendButton = new JButton("Send to Phone");
        sendButton.setBounds(340, 115, 130, 35);
        sendButton.setFont(projectFont.deriveFont(Font.BOLD, 12f));
        sendButton.setBackground(new Color(40, 167, 69));
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setEnabled(false); // locked until a pack is ready
        frame.add(sendButton);

        console = new JTextArea("Ready. Paste a Telegram sticker link above...\n");
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setForeground(textColor);
        console.setLineWrap(true);
        console.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(console);
        scroll.setBounds(30, 165, 440, 195);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(210, 210, 210), 1));
        frame.add(scroll);

        // Buttons
        downloadButton.addActionListener(e -> onStartSetup());
        linkInput.addActionListener(e -> onStartSetup()); // Enter key
        sendButton.addActionListener(e -> {
            if (server == null)
                startServer();
            else
                stopServer();
        });

        frame.setVisible(true);
    }

    static void onStartSetup() {
        if (!downloadButton.isEnabled())
            return;
        String input = linkInput.getText().trim();
        if (input.isEmpty()) {
            log("WARN", "Paste a Telegram sticker link first.");
            return;
        }
        setBusy(true);
        // Slow work runs on a background thread so the window never freezes
        new Thread(() -> {
            try {
                runSetup(input);
            } catch (Exception ex) {
                logError("Setup failed", ex);
            } finally {
                setBusy(false);
            }
        }).start();
    }

    /**
     * Locks / unlocks the buttons while setup is running. Safe to call from any
     * thread.
     */
    static void setBusy(boolean busy) {
        SwingUtilities.invokeLater(() -> {
            downloadButton.setEnabled(!busy);
            linkInput.setEnabled(!busy);
            sendButton.setEnabled(!busy && exportReady);
        });
    }

    /** Switches the Send button between "Send to Phone" and "Stop Server". */
    static void setServerUi(boolean running) {
        SwingUtilities.invokeLater(() -> {
            sendButton.setText(running ? "Stop Server" : "Send to Phone");
            sendButton.setBackground(running ? new Color(200, 60, 60) : new Color(40, 167, 69));
            sendButton.setEnabled(running || exportReady);
            downloadButton.setEnabled(!running);
            linkInput.setEnabled(!running);
        });
    }

    // =====================================================================
    // 4. LOGGER (window + file, bot token always hidden)
    // =====================================================================
    static void log(String level, String message) {
        String clean = hideToken(message);
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String line = "[" + time + "] " + String.format("%-5s", level) + " " + clean + "\n";
        SwingUtilities.invokeLater(() -> {
            if (console != null) {
                console.append(line);
                console.setCaretPosition(console.getDocument().getLength());
            }
        });
        writeLogFile(LocalDateTime.now() + " " + String.format("%-5s", level) + " " + clean + "\n");
    }

    /** Short message in the window, full stack trace in the log file. */
    static void logError(String message, Throwable t) {
        String detail = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        log("ERROR", message + " (" + detail + ")");
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        writeLogFile(hideToken(sw.toString()));
    }

    static synchronized void writeLogFile(String text) {
        try {
            Files.writeString(LOG_FILE, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write log file: " + e.getMessage());
        }
    }

    static String hideToken(String s) {
        return (botToken == null || botToken.isEmpty()) ? s : s.replace(botToken, "<BOT_TOKEN>");
    }

    // =====================================================================
    // Startup self-check: tells you what is missing BEFORE you press a button
    // =====================================================================
    static void startupChecks() {
        log("INFO", "Working folder: " + Paths.get("").toAbsolutePath());
        log("INFO", "Log file: " + LOG_FILE.toAbsolutePath());
        log("INFO", "Java " + System.getProperty("java.version"));
        if (fontNote != null)
            log("WARN", fontNote);
        checkFfmpeg();
        if (loadToken())
            log("OK", "config.properties found, BOT_TOKEN loaded.");
    }

    static boolean checkFfmpeg() {
        try {
            List<String> out = new ArrayList<>();
            int code = runProcess(Arrays.asList("ffmpeg", "-version"), out);
            if (code == 0) {
                log("OK", "FFmpeg found: " + (out.isEmpty() ? "" : out.get(0)));
                return true;
            }
        } catch (Exception e) {
            // fall through
        }
        log("ERROR", "FFmpeg not found. Install it and make sure 'ffmpeg -version' works in a terminal, then restart.");
        return false;
    }

    /**
     * Reads BOT_TOKEN from config.properties. Re-read on every setup, so you can
     * edit it without restarting.
     */
    static boolean loadToken() {
        Path cfg = Paths.get("config.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(cfg)) {
            props.load(in);
        } catch (IOException e) {
            log("ERROR", "config.properties not found in " + cfg.toAbsolutePath().getParent()
                    + " - create it with one line:  BOT_TOKEN=123456:ABC...");
            return false;
        }
        String token = props.getProperty("BOT_TOKEN");
        if (token == null || token.trim().isEmpty()) {
            log("ERROR", "BOT_TOKEN is empty in config.properties.");
            return false;
        }
        botToken = token.trim();
        return true;
    }

    // =====================================================================
    // 2. SETUP (Telegram API -> FFmpeg -> contents.json)
    // =====================================================================
    static void runSetup(String input) throws Exception {
        exportReady = false;
        log("INFO", "---------- New setup ----------");

        String packName = parsePackName(input);
        if (packName == null) {
            log("ERROR", "That is not a Telegram sticker link. Example: https://t.me/addstickers/PackName");
            return;
        }
        if (!loadToken() || !checkFfmpeg())
            return;

        // Step 1: ask Telegram for the list of stickers
        log("INFO", "Step 1/4  Asking Telegram for pack \"" + packName + "\"...");
        String json = telegramCall("getStickerSet?name=" + packName);
        // Remove thumbnail blocks so we only keep full-size sticker file_ids
        json = json.replaceAll("\"thumbnail\":\\{[^}]*\\}", "").replaceAll("\"thumb\":\\{[^}]*\\}", "");

        Map<String, String> stickers = new LinkedHashMap<>(); // file_id -> emoji
        Matcher m = Pattern.compile("\"emoji\":\"([^\"]*)\"|\"file_id\":\"([^\"]+)\"").matcher(json);
        String lastEmoji = FALLBACK_EMOJI;
        while (m.find()) {
            if (m.group(1) != null) {
                lastEmoji = m.group(1).isEmpty() ? FALLBACK_EMOJI : m.group(1);
            } else {
                stickers.putIfAbsent(m.group(2), lastEmoji);
            }
        }
        if (stickers.isEmpty()) {
            log("ERROR", "Telegram answered, but the pack contains no stickers.");
            return;
        }
        log("INFO", "Found " + stickers.size() + " stickers"
                + (stickers.size() > MAX_STICKERS
                        ? " - WhatsApp allows " + MAX_STICKERS + ", using the first " + MAX_STICKERS
                        : "")
                + ".");

        // Step 2: fresh export folder (old/partial exports are removed)
        log("INFO", "Step 2/4  Preparing folder " + packName + "_Export");
        exportDir = Paths.get(packName + "_Export");
        deleteFolder(exportDir);
        Files.createDirectories(exportDir);

        // Step 3: download + convert each sticker
        log("INFO", "Step 3/4  Downloading and converting (can take a minute)...");
        int converted = 0, failed = 0, skipped = 0, animatedCount = 0, tried = 0;
        List<String> emojis = new ArrayList<>();

        for (Map.Entry<String, String> entry : stickers.entrySet()) {
            if (converted >= MAX_STICKERS)
                break;
            tried++;
            String label = "[" + tried + "/" + stickers.size() + "]";
            try {
                String fileJson = telegramCall("getFile?file_id=" + entry.getKey());
                Matcher pm = FILE_PATH.matcher(fileJson);
                if (!pm.find()) {
                    log("WARN", label + " Telegram gave no file path - skipped.");
                    failed++;
                    continue;
                }
                String filePath = pm.group(1);
                int dot = filePath.lastIndexOf('.');
                String ext = dot >= 0 ? filePath.substring(dot).toLowerCase() : "";

                if (ext.equals(".tgs")) { // Lottie vector animation - FFmpeg cannot read it
                    log("WARN", label + " is a .tgs vector sticker - FFmpeg cannot convert it, skipped.");
                    skipped++;
                    continue;
                }

                boolean animated = ext.equals(".webm");
                Path raw = exportDir.resolve("raw_" + tried + ext);
                try {
                    downloadFile(filePath, raw);
                    Path out = exportDir.resolve("sticker_" + (converted + 1) + ".webp");
                    if (convertSticker(raw, out, animated)) {
                        if (converted == 0)
                            makeTrayIcon(raw, animated); // tray icon = first sticker
                        converted++;
                        if (animated)
                            animatedCount++;
                        emojis.add(entry.getValue());
                        log("INFO", label + " OK -> " + out.getFileName() + " (" + kb(Files.size(out)) + " KB)");
                    } else {
                        failed++;
                    }
                } finally {
                    Files.deleteIfExists(raw);
                }
            } catch (Exception ex) {
                failed++;
                logError(label + " failed", ex);
            }
        }

        log("INFO", "Converted " + converted + ", failed " + failed + ", skipped " + skipped + ".");
        if (converted == 0) {
            log("ERROR", "No sticker could be converted. Check the messages above and automation_bridge.log.");
            return;
        }
        if (converted < MIN_STICKERS) {
            log("WARN", "WhatsApp needs at least " + MIN_STICKERS + " stickers in a pack - this pack has " + converted
                    + ".");
        }
        if (animatedCount != 0 && animatedCount != converted) {
            log("WARN", "Pack mixes animated and static stickers - WhatsApp needs one type per pack.");
        }

        // Step 4: metadata file for WhatsApp
        log("INFO", "Step 4/4  Writing contents.json...");
        writeContentsJson(packName, emojis, animatedCount == converted);

        exportReady = true;
        log("OK", "Pack ready in " + exportDir.toAbsolutePath() + " - click 'Send to Phone'.");
    }

    /**
     * Accepts t.me/addstickers/Name, tg://addstickers?set=Name, links with ?query,
     * or just the name.
     */
    static String parsePackName(String input) {
        String s = input.trim();
        int setIdx = s.indexOf("set=");
        if (s.startsWith("tg:") && setIdx >= 0) {
            s = s.substring(setIdx + 4);
            int amp = s.indexOf('&');
            if (amp >= 0)
                s = s.substring(0, amp);
        } else {
            int q = s.indexOf('?');
            if (q >= 0)
                s = s.substring(0, q);
            s = s.replaceAll("/+$", "");
            s = s.substring(s.lastIndexOf('/') + 1);
        }
        return s.matches("[A-Za-z0-9_]{1,64}") ? s : null;
    }

    /**
     * Calls the Telegram Bot API and turns HTTP errors into messages a human can
     * read.
     */
    static String telegramCall(String methodAndQuery) throws Exception {
        HttpRequest req = HttpRequest
                .newBuilder(URI.create("https://api.telegram.org/bot" + botToken + "/" + methodAndQuery))
                .timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            Matcher d = Pattern.compile("\"description\":\"([^\"]*)\"").matcher(res.body());
            String why = d.find() ? d.group(1) : "no description";
            if (res.statusCode() == 401 || res.statusCode() == 404) {
                throw new IOException("Telegram rejected the request (HTTP " + res.statusCode() + ": " + why
                        + "). Check BOT_TOKEN in config.properties.");
            }
            if (why.contains("STICKERSET_INVALID")) {
                throw new IOException("Sticker pack not found. Check the link.");
            }
            throw new IOException("Telegram error HTTP " + res.statusCode() + ": " + why);
        }
        return res.body();
    }

    static void downloadFile(String filePath, Path target) throws Exception {
        HttpRequest req = HttpRequest
                .newBuilder(URI.create("https://api.telegram.org/file/bot" + botToken + "/" + filePath))
                .timeout(Duration.ofSeconds(60)).build();
        HttpResponse<Path> res = client.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (res.statusCode() != 200)
            throw new IOException("download returned HTTP " + res.statusCode());
    }

    /**
     * Converts one Telegram sticker into a WhatsApp sticker: 512x512, transparent
     * padding, WebP.
     * If the file is bigger than WhatsApp allows, quality is lowered and it is
     * tried again.
     */
    static boolean convertSticker(Path raw, Path out, boolean animated) throws Exception {
        long limit = animated ? ANIMATED_LIMIT : STATIC_LIMIT;
        String filter = "scale=512:512:force_original_aspect_ratio=decrease,format=rgba,"
                + "pad=512:512:(ow-iw)/2:(oh-ih)/2:color=black@0" + (animated ? ",fps=15" : "");
        boolean useVpxDecoder = animated; // keeps the transparency of .webm stickers

        for (int quality : new int[] { 70, 50, 30, 15 }) {
            List<String> output = new ArrayList<>();
            int code = runProcess(ffmpegCommand(raw, out, filter, quality, useVpxDecoder), output);
            if (code != 0 && useVpxDecoder) { // libvpx decoder missing -> retry with the default one
                log("WARN",
                        "FFmpeg could not use the libvpx decoder - retrying without it (transparency may be lost).");
                useVpxDecoder = false;
                output.clear();
                code = runProcess(ffmpegCommand(raw, out, filter, quality, false), output);
            }
            if (code != 0) {
                log("ERROR", "FFmpeg failed (exit code " + code + ") for " + raw.getFileName() + ":");
                logTail(output);
                return false;
            }
            long size = Files.size(out);
            if (size <= limit)
                return true;
            log("INFO", out.getFileName() + " is " + kb(size) + " KB (limit " + kb(limit)
                    + " KB) - lowering quality to fit...");
        }
        log("WARN", out.getFileName() + " is still above " + kb(limit) + " KB - WhatsApp may reject it.");
        return true;
    }

    static List<String> ffmpegCommand(Path in, Path out, String filter, int quality, boolean vpxDecoder) {
        List<String> cmd = new ArrayList<>(Arrays.asList("ffmpeg", "-y", "-loglevel", "error"));
        if (vpxDecoder)
            cmd.addAll(Arrays.asList("-c:v", "libvpx-vp9"));
        cmd.addAll(Arrays.asList("-i", in.toAbsolutePath().toString(), "-vf", filter,
                "-c:v", "libwebp", "-lossless", "0", "-q:v", String.valueOf(quality),
                "-compression_level", "6", "-loop", "0", "-an", out.toAbsolutePath().toString()));
        return cmd;
    }

    /**
     * WhatsApp tray icon: 96x96, static, <= 50 KB. Made from the ORIGINAL first
     * sticker (first frame only).
     */
    static void makeTrayIcon(Path raw, boolean animated) {
        try {
            Path tray = exportDir.resolve("tray_icon.webp");
            List<String> cmd = new ArrayList<>(Arrays.asList("ffmpeg", "-y", "-loglevel", "error"));
            if (animated)
                cmd.addAll(Arrays.asList("-c:v", "libvpx-vp9"));
            cmd.addAll(Arrays.asList("-i", raw.toAbsolutePath().toString(), "-frames:v", "1",
                    "-vf",
                    "scale=96:96:force_original_aspect_ratio=decrease,format=rgba,pad=96:96:(ow-iw)/2:(oh-ih)/2:color=black@0",
                    "-c:v", "libwebp", "-lossless", "0", "-q:v", "70", tray.toAbsolutePath().toString()));
            List<String> output = new ArrayList<>();
            int code = runProcess(cmd, output);
            if (code != 0 || !Files.exists(tray)) {
                log("WARN", "Could not create tray_icon.webp:");
                logTail(output);
            } else if (Files.size(tray) > TRAY_LIMIT) {
                log("WARN", "tray_icon.webp is above 50 KB - WhatsApp may reject it.");
            } else {
                log("INFO", "tray_icon.webp created (96x96).");
            }
        } catch (Exception e) {
            logError("Tray icon failed", e);
        }
    }

    static void writeContentsJson(String packName, List<String> emojis, boolean animatedPack) throws IOException {
        StringBuilder j = new StringBuilder("{\n");
        j.append("  \"identifier\": \"").append(jsonEscape(packName.replaceAll("[^a-zA-Z0-9]", ""))).append("\",\n");
        j.append("  \"name\": \"").append(jsonEscape(packName)).append("\",\n");
        j.append("  \"publisher\": \"Automation Bridge\",\n");
        j.append("  \"tray_image_file\": \"tray_icon.webp\",\n");
        j.append("  \"publisher_email\": \"\",\n  \"publisher_website\": \"\",\n");
        j.append("  \"privacy_policy_website\": \"\",\n  \"license_agreement_website\": \"\",\n");
        j.append("  \"image_data_version\": \"1\",\n  \"avoid_cache\": false,\n");
        j.append("  \"animated_sticker_pack\": ").append(animatedPack).append(",\n");
        j.append("  \"stickers\": [\n");
        for (int i = 0; i < emojis.size(); i++) {
            j.append("    { \"image_file\": \"sticker_").append(i + 1).append(".webp\", \"emojis\": [\"")
                    .append(jsonEscape(emojis.get(i))).append("\"] }");
            j.append(i < emojis.size() - 1 ? ",\n" : "\n");
        }
        j.append("  ]\n}\n");
        Files.write(exportDir.resolve("contents.json"), j.toString().getBytes(StandardCharsets.UTF_8));
    }

    // =====================================================================
    // 3. WI-FI SERVER (zip + JDK HttpServer, phone opens a normal web page)
    // =====================================================================
    static void startServer() {
        if (!exportReady || exportDir == null) {
            log("WARN", "Nothing to send yet - run 'Start Setup' first.");
            return;
        }
        SwingUtilities.invokeLater(() -> sendButton.setEnabled(false));

        new Thread(() -> {
            try {
                log("INFO", "Zipping the sticker pack...");
                Path zip = zipExport();
                byte[] zipBytes = Files.readAllBytes(zip);
                log("INFO", "Zip ready: " + zip.toAbsolutePath() + " (" + kb(zipBytes.length) + " KB)");

                // Try ports 8080..8089 until one is free
                int port = -1;
                for (int p = START_PORT; p < START_PORT + 10 && server == null; p++) {
                    try {
                        server = HttpServer.create(new InetSocketAddress(p), 0); // all network adapters
                        port = p;
                    } catch (BindException be) {
                        log("WARN", "Port " + p + " is busy, trying the next one...");
                    }
                }
                if (server == null)
                    throw new IOException("No free port between " + START_PORT + " and " + (START_PORT + 9) + ".");

                String fileName = zip.getFileName().toString();
                byte[] page = pageHtml(exportDir.getFileName().toString(), zipBytes.length)
                        .getBytes(StandardCharsets.UTF_8);
                server.createContext("/", ex -> handleRequest(ex, page, zipBytes, fileName));
                server.start();

                log("OK", "Server is running. On your phone open one of these links:");
                List<String[]> addresses = lanAddresses();
                if (addresses.isEmpty()) {
                    log("ERROR",
                            "No local network address found. Connect the PC to Wi-Fi or turn on Windows Mobile Hotspot.");
                }
                for (String[] a : addresses) {
                    log("INFO", "   http://" + a[0] + ":" + port + "     (" + a[1] + ")");
                }
                log("INFO", "Use the link whose adapter is your Wi-Fi / hotspot (usually 192.168.x.x).");
                log("INFO", "Phone and PC must be on the same network. If the page does not open, allow "
                        + "'Java(TM) Platform SE binary' in Windows Firewall (Private networks).");
                selfTest(port);
                setServerUi(true);
            } catch (Exception e) {
                logError("Could not start the server", e);
                if (server != null) {
                    server.stop(0);
                }
                server = null;
                setServerUi(false);
            }
        }).start();
    }

    static void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
            log("INFO", "Server stopped.");
        }
        setServerUi(false);
    }

    /**
     * Handles every browser request. "/" = download page, "/stickers.zip" = the
     * file.
     */
    static void handleRequest(HttpExchange ex, byte[] page, byte[] zipBytes, String fileName) {
        String path = ex.getRequestURI().getPath();
        String who = ex.getRemoteAddress().getAddress().getHostAddress();
        try {
            if (path.equals("/")) {
                log("INFO", "Phone " + who + " opened the download page.");
                send(ex, 200, "text/html; charset=utf-8", page, null);
            } else if (path.equals("/ping")) { // used by selfTest() only, not logged as a phone visit
                send(ex, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), null);
            } else if (path.equals("/stickers.zip")) {
                log("INFO", "Phone " + who + " is downloading " + fileName + "...");
                send(ex, 200, "application/zip", zipBytes, fileName);
                log("OK", "Sent " + kb(zipBytes.length) + " KB to " + who + ". You can stop the server now.");
            } else {
                send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), null); // e.g. /favicon.ico
            }
        } catch (IOException e) {
            log("WARN", "Transfer to " + who + " was interrupted: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    static void send(HttpExchange ex, int status, String type, byte[] body, String downloadName) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (downloadName != null) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
        }
        if (ex.getRequestMethod().equalsIgnoreCase("HEAD")) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    static String pageHtml(String packFolder, int zipSize) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>Sticker Pack</title>"
                + "<style>body{font-family:sans-serif;text-align:center;padding:40px 20px;background:#f5f6fa;color:#333}"
                + "a{display:inline-block;margin-top:24px;padding:16px 32px;background:#28a745;color:#fff;"
                + "border-radius:8px;text-decoration:none;font-size:18px}</style></head><body>"
                + "<h2>Automation Bridge</h2><p>Pack: <b>" + packFolder + "</b><br>" + kb(zipSize) + " KB</p>"
                + "<a href='/stickers.zip'>Download sticker pack</a></body></html>";
    }

    /**
     * Asks the server itself, so a failing phone can be blamed on the
     * network/firewall instead of the code.
     */
    static void selfTest(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping"))
                    .timeout(Duration.ofSeconds(5)).build();
            int code = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code == 200)
                log("OK",
                        "Self-test passed: the server answers on this PC. If the phone cannot connect, it is the network or firewall.");
            else
                log("WARN", "Self-test got HTTP " + code + ".");
        } catch (Exception e) {
            log("WARN", "Self-test could not reach the server: " + e.getMessage());
        }
    }

    /**
     * Zips every file of the export folder. Entry names are plain file names (works
     * on Windows too).
     */
    static Path zipExport() throws IOException {
        Path zip = Paths.get(exportDir.getFileName() + ".zip");
        List<Path> files;
        try (Stream<Path> s = Files.list(exportDir)) {
            files = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path f : files) {
                zos.putNextEntry(new ZipEntry(f.getFileName().toString()));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
        log("INFO", "Zipped " + files.size() + " files.");
        return zip;
    }

    /**
     * Finds this PC's local IPv4 addresses (the old code had one hard-coded).
     * Returns {ip, adapter name}.
     */
    static List<String[]> lanAddresses() {
        List<String[]> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                    continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        result.add(new String[] { a.getHostAddress(), ni.getDisplayName() });
                    }
                }
            }
        } catch (SocketException e) {
            logError("Could not read network adapters", e);
        }
        return result;
    }

    // =====================================================================
    // Small helpers
    // =====================================================================
    /**
     * Runs a command, collects its output, kills it after 60 s. Returns the exit
     * code (-1 on timeout).
     */
    static int runProcess(List<String> cmd, List<String> output) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null)
                    output.add(line);
            } catch (IOException ignored) {
            }
        });
        reader.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(1000);
            output.add("Timed out after 60 seconds.");
            return -1;
        }
        reader.join(2000);
        return p.exitValue();
    }

    static void logTail(List<String> output) {
        int from = Math.max(0, output.size() - 5);
        for (int i = from; i < output.size(); i++)
            log("ERROR", "   ffmpeg: " + output.get(i));
    }

    static void deleteFolder(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        List<Path> all;
        try (Stream<Path> walk = Files.walk(dir)) {
            all = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path p : all)
            Files.delete(p);
    }

    static String kb(long bytes) {
        return String.valueOf(Math.max(1, Math.round(bytes / 1024.0)));
    }

    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}