package com.chessbot;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Small window: type a FEN, see the board and the fastest mate up to the chosen depth. */
public final class ChessUI {
    private final BoardPanel board = new BoardPanel();
    private final JTextField fenField;
    private final JSpinner depthSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 5, 1));
    private final JButton solveButton = new JButton("Find mate");
    private final JLabel status = new JLabel(" ");
    private SwingWorker<Result, String> search; // non-null while a search is running

    private record Result(int n, Move move, long millis) {
    }

    private ChessUI(String initialFen) {
        fenField = new JTextField(initialFen, 50);
    }

    public static void show(String initialFen) {
        SwingUtilities.invokeLater(() -> new ChessUI(initialFen).open());
    }

    private void open() {
        status.setFont(status.getFont().deriveFont(Font.BOLD, 16f));
        status.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        solveButton.addActionListener(e -> solveOrStop());
        fenField.addActionListener(e -> solveOrStop()); // Enter in the text field

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(new JLabel("Mate in up to"));
        controls.add(depthSpinner);
        controls.add(solveButton);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        top.add(new JLabel("FEN:"), BorderLayout.WEST);
        top.add(fenField, BorderLayout.CENTER);
        top.add(controls, BorderLayout.EAST);

        JFrame frame = new JFrame("ChessBot – Mate finder");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(top, BorderLayout.NORTH);
        frame.add(board, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        solveOrStop();
    }

    private void solveOrStop() {
        if (search != null) {
            search.cancel(true);
            return;
        }

        Position position;
        try {
            position = Position.fromFen(fenField.getText());
        } catch (RuntimeException e) {
            status.setText("Invalid FEN: " + e.getMessage());
            return;
        }
        board.show(position, null);
        int maxN = (Integer) depthSpinner.getValue();

        // Search off the UI thread: deep searches can take a long time with the naive code
        search = new SwingWorker<>() {
            @Override
            protected Result doInBackground() {
                long start = System.nanoTime();
                // Try 1, 2, ... so the fastest mate is the one reported
                for (int n = 1; n <= maxN; n++) {
                    publish("Searching mate in " + n + "…");
                    Optional<Move> mate = MateFinder.findMate(position, n);
                    if (mate.isPresent()) return new Result(n, mate.get(), elapsedMillis(start));
                }
                return new Result(maxN, null, elapsedMillis(start));
            }

            @Override
            protected void process(List<String> messages) {
                status.setText(messages.getLast());
            }

            @Override
            protected void done() {
                search = null;
                solveButton.setText("Find mate");
                if (isCancelled()) {
                    status.setText("Search stopped");
                    return;
                }
                try {
                    Result r = get();
                    board.show(position, r.move());
                    status.setText((r.move() != null ? "Mate in " + r.n() + ": " + r.move() : "No mate in " + r.n() + " or less")
                            + "    (" + r.millis() + " ms)");
                } catch (InterruptedException | ExecutionException e) {
                    status.setText("Search failed: " + e.getCause());
                }
            }
        };
        solveButton.setText("Stop");
        search.execute();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
