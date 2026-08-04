import java.util.*;
import java.awt.*;
import javax.swing.*;
import java.io.File;
import java.io.IOException;

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
                    consoleOutput.append("-> Connecting to server...\n");
                    Thread.sleep(1500);

                    consoleOutput.append("-> Optimizing formats for WhatsApp...\n");
                    Thread.sleep(2000);

                    consoleOutput.append("-> Setup Complete! Files are ready.\n");
                } catch (InterruptedException ex) {
                    consoleOutput.append("-> Process interrupted.\n");
                    Thread.currentThread().interrupt();
                } finally {
                    // Restore UI state
                    downloadButton.setEnabled(true);
                    linkInput.setEnabled(true);
                }
            }).start();
        });

        // Display GUI
        frame.setVisible(true);
    }
}