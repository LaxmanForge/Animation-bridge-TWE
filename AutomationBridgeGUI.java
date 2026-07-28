import java.util.*;
import java.awt.*;
import javax.swing.*;
import java.io.File;
import java.io.IOException;

public class AutomationBridgeGUI {
    public static void main(String[] args) {
        JLabel label = new JLabel();
        label.setText("Automation Bridge GUI"); // Set the text of the label
        ImageIcon imageIcon = new ImageIcon("The-World_.png"); // Creating an ImageIcon object with the specified image
                                                               // file
        label.setBounds(50, 50, 600, 600);
        label.setIcon(imageIcon); // Set the icon of the label to the ImageIcon object
        JFrame frame = new JFrame(); // JFrame is a GUI window to display the GUI
        frame.setSize(700, 700); // Set the size of the GUI window
        frame.setTitle("Automation Bridge GUI"); // Set the title of the GUI window
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // Set the default close operation for the GUI window
        frame.setLayout(null);
        label.setHorizontalTextPosition(JLabel.CENTER);
        label.setVerticalTextPosition(JLabel.BOTTOM);
        ImageIcon icon = new ImageIcon("The-World_.png"); // Create an ImageIcon object with the specified image file
        frame.setIconImage(icon.getImage()); // Set the icon image of the GUI window
        frame.getContentPane().setBackground(new java.awt.Color(6, 30, 41)); // Set the background color of the GUI
                                                                             // window
        frame.add(label); // Add the label to the GUI window
        label.setHorizontalAlignment(JLabel.CENTER); // Set the horizontal alignment of the label to left
        label.setVerticalAlignment(JLabel.TOP); // Set the vertical alignment of the label to top
        try {
            Font customFont = Font.createFont(Font.TRUETYPE_FONT, new File("JetBrainsMono-variableFont.ttf"));
            label.setFont(customFont.deriveFont(Font.BOLD, 20f));
        } catch (FontFormatException | IOException e) {
            e.printStackTrace();
            // Fallback font if the file isn't found
            label.setFont(new Font("SansSerif", Font.BOLD, 20));
        } // Set the font of
          // the label to
          // "JetBrains Mono",
          // bold, size 20
        label.setForeground(new java.awt.Color(255, 255, 255)); // Set the foreground color of the label to white

        frame.setVisible(true); // Make the GUI window visible and YES! this should be the last line of the main
                                // method, otherwise the GUI window will not be displayed
    }
}
