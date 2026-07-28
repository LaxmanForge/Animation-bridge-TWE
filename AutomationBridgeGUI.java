import java.util.*;

import javax.swing.*;

public class AutomationBridgeGUI {
    public static void main(String[] args) {
        JLabel label = new JLabel();
        label.setText("Automation Bridge GUI"); // Set the text of the label
        ImageIcon imageIcon = new ImageIcon("The-World_.png"); // Creating an ImageIcon object with the specified image
                                                               // file
        label.setIcon(imageIcon); // Set the icon of the label to the ImageIcon object
        JFrame frame = new JFrame(); // JFrame is a GUI window to display the GUI
        frame.setSize(500, 400); // Set the size of the GUI window
        frame.setTitle("Automation Bridge GUI"); // Set the title of the GUI window
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // Set the default close operation for the GUI window
        ImageIcon icon = new ImageIcon("The-World_.png"); // Create an ImageIcon object with the specified image file
        frame.setIconImage(icon.getImage()); // Set the icon image of the GUI window
        frame.getContentPane().setBackground(new java.awt.Color(6, 30, 41)); // Set the background color of the GUI
                                                                             // window
        frame.add(label); // Add the label to the GUI window

        frame.setVisible(true); // Make the GUI window visible and YES! this should be the last line of the main
                                // method, otherwise the GUI window will not be displayed
    }
}
