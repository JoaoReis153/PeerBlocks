package dev.joaoreis.peerblocks.gui;

import dev.joaoreis.peerblocks.core.Node;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.*;

public class GUINode {

    private JFrame frame;
    private Node node;

    public GUINode(GUI gui) {
        this.node = gui.getNode();
        frame = new JFrame("Add Node");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addFrameContent();
        frame.pack();
    }

    public void open() {
        frame.setVisible(true);
    }

    private void addFrameContent() {
        frame.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 10));

        JLabel addressLabel = new JLabel("Address:");
        JTextField addressField = new JTextField(15);

        addressField.setText(node.getAddress().getHostAddress());

        frame.add(addressLabel);
        frame.add(addressField);

        JLabel portLabel = new JLabel("Port:");
        JTextField portField = new JTextField(5);
        frame.add(portLabel);
        frame.add(portField);

        JButton cancelButton = new JButton("Cancel");
        JButton okButton = new JButton("OK");

        cancelButton.addActionListener(
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        frame.dispose();
                    }
                });

        okButton.addActionListener(
                new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        String address = addressField.getText();
                        String portText = portField.getText();
                        try {
                            int port = Integer.parseInt(portText);

                            int choice =
                                    JOptionPane.showConfirmDialog(
                                            frame,
                                            "Connect to:\nAddress: "
                                                    + address
                                                    + "\nPort: "
                                                    + port
                                                    + "?",
                                            "Confirm Connection",
                                            JOptionPane.YES_NO_OPTION);

                            if (choice == JOptionPane.YES_OPTION) {
                                node.connectToNode(address, port);
                            }

                        } catch (NumberFormatException ex) {
                            JOptionPane.showMessageDialog(
                                    frame, "Invalid port!", "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                });

        frame.add(cancelButton);
        frame.add(okButton);
    }
}
