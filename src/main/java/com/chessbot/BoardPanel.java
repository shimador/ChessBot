package com.chessbot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JPanel;

/** Draws a position, with the given move highlighted by an arrow. */
class BoardPanel extends JPanel {
    private static final Color LIGHT = new Color(240, 217, 181);
    private static final Color DARK = new Color(181, 136, 99);
    private static final Color HIGHLIGHT_LIGHT = new Color(247, 236, 116);
    private static final Color HIGHLIGHT_DARK = new Color(218, 195, 74);
    private static final Color ARROW = new Color(21, 120, 27, 170);

    private Position position;
    private Move move;

    BoardPanel() {
        setPreferredSize(new Dimension(560, 560));
    }

    void show(Position position, Move move) {
        this.position = position;
        this.move = move;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (position == null) return;
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int sq = Math.min(getWidth(), getHeight()) / 8;
        int x0 = (getWidth() - 8 * sq) / 2;
        int y0 = (getHeight() - 8 * sq) / 2;
        Font pieceFont = new Font("Segoe UI Symbol", Font.PLAIN, (int) (sq * 0.8));
        Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, sq / 6));

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int x = x0 + c * sq, y = y0 + r * sq;
                boolean light = (r + c) % 2 == 0;
                boolean highlighted = move != null
                        && ((r == move.fromRow() && c == move.fromCol()) || (r == move.toRow() && c == move.toCol()));
                g2.setColor(highlighted ? (light ? HIGHLIGHT_LIGHT : HIGHLIGHT_DARK) : (light ? LIGHT : DARK));
                g2.fillRect(x, y, sq, sq);

                // Coordinates along the left and bottom edges, in the opposite square colour
                g2.setFont(labelFont);
                g2.setColor(light ? DARK : LIGHT);
                if (c == 0) g2.drawString(String.valueOf(8 - r), x + 3, y + labelFont.getSize() + 1);
                if (r == 7) g2.drawString(String.valueOf((char) ('a' + c)), x + sq - labelFont.getSize(), y + sq - 4);

                char piece = position.board[r][c];
                if (piece != '.') drawPiece(g2, pieceFont, piece, x, y, sq);
            }
        }

        if (move != null) drawArrow(g2, x0, y0, sq);
    }

    private void drawPiece(Graphics2D g2, Font font, char piece, int x, int y, int sq) {
        // Use the solid glyphs for both sides and fill white pieces in white, so they read well on any square
        char glyph = (char) (0x265A + "kqrbnp".indexOf(Character.toLowerCase(piece)));
        GlyphVector gv = font.createGlyphVector(g2.getFontRenderContext(), String.valueOf(glyph));
        Rectangle2D b = gv.getVisualBounds();
        Shape outline = gv.getOutline(
                (float) (x + (sq - b.getWidth()) / 2 - b.getX()),
                (float) (y + (sq - b.getHeight()) / 2 - b.getY()));
        g2.setColor(Character.isUpperCase(piece) ? Color.WHITE : Color.BLACK);
        g2.fill(outline);
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(Math.max(1f, sq / 40f)));
        g2.draw(outline);
    }

    private void drawArrow(Graphics2D g2, int x0, int y0, int sq) {
        double fx = x0 + move.fromCol() * sq + sq / 2.0, fy = y0 + move.fromRow() * sq + sq / 2.0;
        double tx = x0 + move.toCol() * sq + sq / 2.0, ty = y0 + move.toRow() * sq + sq / 2.0;
        double angle = Math.atan2(ty - fy, tx - fx);
        double head = sq * 0.45, spread = 0.5;

        // Stop the shaft at the arrowhead's base so the translucent parts don't overlap
        double shaftEnd = head * Math.cos(spread);
        g2.setColor(ARROW);
        g2.setStroke(new BasicStroke(sq / 7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g2.draw(new Line2D.Double(fx, fy, tx - Math.cos(angle) * shaftEnd, ty - Math.sin(angle) * shaftEnd));

        Path2D tip = new Path2D.Double();
        tip.moveTo(tx, ty);
        tip.lineTo(tx - head * Math.cos(angle - spread), ty - head * Math.sin(angle - spread));
        tip.lineTo(tx - head * Math.cos(angle + spread), ty - head * Math.sin(angle + spread));
        tip.closePath();
        g2.fill(tip);
    }
}
