package carpark;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A self-contained Swing component that visualizes the parking lot.
 *
 * <p>Each slot is either in its <b>default</b> (empty, green-outlined)
 * state, or holds a <b>stationary parked car</b> icon that stays put until
 * it leaves. Cars visibly drive in from an entrance gate to a
 * <em>randomly chosen open slot</em> and drive out from a
 * <em>randomly chosen occupied slot</em> to an exit gate &mdash; on
 * purpose, so the lot fills and drains in a realistic, scattered order
 * instead of always filling 1,2,3... and draining .../3,2,1.</p>
 *
 * <p>The animation is a cosmetic approximation layered on top of the real
 * thread-safe {@link ParkingLot} state: {@link #reportOccupancy(int)} is
 * called periodically by {@link CarParkSimApp} with the true occupied
 * count, and this panel spawns entering/exiting car animations to
 * reconcile its own displayed slots with that ground truth. All actual
 * synchronization correctness lives in {@link ParkingLot}; this class only
 * draws a picture of it.</p>
 */
public final class ParkingLotVisualPanel extends JPanel {

    private static final Color[] CAR_COLORS = {
            new Color(0x3B, 0x82, 0xF6), new Color(0xF5, 0x9E, 0x0B),
            new Color(0x8B, 0x5C, 0xF6), new Color(0xEC, 0x48, 0x99),
            new Color(0x06, 0xB6, 0xD4), new Color(0xF4, 0x43, 0x36)
    };

    /** A single car icon currently animating between two points. */
    private static final class AnimatedCar {
        final double startX, startY, endX, endY;
        final long startTimeMillis;
        final long durationMillis;
        final Color color;
        final Runnable onComplete;

        AnimatedCar(double startX, double startY, double endX, double endY,
                    long durationMillis, Color color, Runnable onComplete) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.startTimeMillis = System.currentTimeMillis();
            this.durationMillis = durationMillis;
            this.color = color;
            this.onComplete = onComplete;
        }

        boolean isFinished(long now) {
            return now - startTimeMillis >= durationMillis;
        }

        Point2D.Double positionAt(long now) {
            double t = Math.min(1.0, (now - startTimeMillis) / (double) durationMillis);
            double eased = 1 - Math.pow(1 - t, 3); // ease-out cubic for a natural glide
            return new Point2D.Double(startX + (endX - startX) * eased, startY + (endY - startY) * eased);
        }
    }

    private final ParkingLot parkingLot;
    private final List<AnimatedCar> activeAnimations = new ArrayList<>();

    /**
     * The color of the stationary car parked in each slot, indexed by
     * on-screen slot position; {@code null} means that slot is in its
     * default empty state. Rebuilt whenever the buffer capacity changes.
     */
    private Color[] slotColor = new Color[0];

    /** Slot indices currently reserved for a car that is still mid-entry animation. */
    private final Set<Integer> reservedForEntry = new HashSet<>();

    /** Cars produced by the real buffer that don't currently fit in the visual grid. */
    private int overflowCount = 0;

    public ParkingLotVisualPanel(ParkingLot parkingLot) {
        this.parkingLot = parkingLot;
        setPreferredSize(new Dimension(420, 240));
        setBackground(Color.WHITE);

        // Drives the animation frame rate independently of the (slower)
        // dashboard statistics refresh, so car movement looks smooth.
        Timer animationTimer = new Timer(30, e -> onAnimationTick());
        animationTimer.start();
    }

    /**
     * Called periodically (e.g. every dashboard refresh) with the real,
     * thread-safe occupied count from {@link ParkingLot}. Spawns entering
     * animations (to a random open slot) if the real count has grown, or
     * exiting animations (from a random occupied slot) if it has shrunk,
     * so the picture catches up to reality smoothly instead of jumping
     * instantly.
     */
    public void reportOccupancy(int actualOccupied) {
        resizeSlotArrayIfNeeded();

        int settled = countSettled();
        int predicted = settled + reservedForEntry.size() + overflowCount;
        int delta = actualOccupied - predicted;

        if (delta > 0) {
            for (int i = 0; i < delta; i++) {
                int idx = pickRandomFreeSlot();
                if (idx < 0) {
                    overflowCount++; // grid is full; the real buffer holds more than we can draw
                    continue;
                }
                reservedForEntry.add(idx);
                spawnEnteringCar(idx);
            }
        } else if (delta < 0) {
            int toRemove = -delta;
            for (int i = 0; i < toRemove; i++) {
                if (overflowCount > 0) {
                    overflowCount--;
                    continue;
                }
                int idx = pickRandomOccupiedSlot();
                if (idx < 0) {
                    break;
                }
                Color carColor = slotColor[idx];
                slotColor[idx] = null;
                spawnExitingCar(idx, carColor);
            }
        }
    }

    /** Instantly clears all slots, animations, and counters (used by the Reset button). */
    public void reset() {
        activeAnimations.clear();
        reservedForEntry.clear();
        overflowCount = 0;
        for (int i = 0; i < slotColor.length; i++) {
            slotColor[i] = null;
        }
        repaint();
    }

    private void resizeSlotArrayIfNeeded() {
        int capacity = Math.max(0, parkingLot.getConfig().getBufferCapacity());
        if (slotColor.length == capacity) {
            return;
        }
        Color[] fresh = new Color[capacity];
        int idx = 0;
        for (Color c : slotColor) {
            if (c != null && idx < capacity) {
                fresh[idx++] = c;
            }
        }
        slotColor = fresh;
        reservedForEntry.clear(); // stale indices from the old sizing; safe to drop
    }

    private int countSettled() {
        int count = 0;
        for (Color c : slotColor) {
            if (c != null) {
                count++;
            }
        }
        return count;
    }

    private int pickRandomFreeSlot() {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < slotColor.length; i++) {
            if (slotColor[i] == null && !reservedForEntry.contains(i)) {
                free.add(i);
            }
        }
        if (free.isEmpty()) {
            return -1;
        }
        return free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    private int pickRandomOccupiedSlot() {
        List<Integer> occupied = new ArrayList<>();
        for (int i = 0; i < slotColor.length; i++) {
            if (slotColor[i] != null) {
                occupied.add(i);
            }
        }
        if (occupied.isEmpty()) {
            return -1;
        }
        return occupied.get(ThreadLocalRandom.current().nextInt(occupied.size()));
    }

    private void onAnimationTick() {
        long now = System.currentTimeMillis();
        Iterator<AnimatedCar> it = activeAnimations.iterator();
        while (it.hasNext()) {
            AnimatedCar car = it.next();
            if (car.isFinished(now)) {
                it.remove();
                if (car.onComplete != null) {
                    car.onComplete.run();
                }
            }
        }
        repaint();
    }

    private void spawnEnteringCar(int targetSlotIndex) {
        int capacity = parkingLot.getConfig().getBufferCapacity();
        Rectangle target = slotRect(targetSlotIndex, capacity);
        Point2D.Double gate = entranceGatePoint();
        Color color = CAR_COLORS[ThreadLocalRandom.current().nextInt(CAR_COLORS.length)];

        activeAnimations.add(new AnimatedCar(
                gate.x, gate.y, target.getCenterX(), target.getCenterY(),
                450, color,
                () -> {
                    // The car has arrived: it now stops and stays parked in
                    // its slot until a consumer later takes it out.
                    if (targetSlotIndex < slotColor.length && slotColor[targetSlotIndex] == null) {
                        slotColor[targetSlotIndex] = color;
                    }
                    reservedForEntry.remove(targetSlotIndex);
                }));
    }

    private void spawnExitingCar(int sourceSlotIndex, Color color) {
        int capacity = parkingLot.getConfig().getBufferCapacity();
        Rectangle source = slotRect(sourceSlotIndex, capacity);
        Point2D.Double gate = exitGatePoint();

        activeAnimations.add(new AnimatedCar(
                source.getCenterX(), source.getCenterY(), gate.x, gate.y,
                450, color, null));
    }

    /** Computes the on-screen rectangle for parking slot {@code index} out of {@code capacity}. */
    private Rectangle slotRect(int index, int capacity) {
        capacity = Math.max(1, capacity);
        index = Math.min(index, capacity - 1);

        int padding = 14;
        int gateMargin = 34; // reserve space on the left/right for IN/OUT gates
        int availableWidth = Math.max(20, getWidth() - 2 * padding - 2 * gateMargin);
        int availableHeight = Math.max(20, getHeight() - 2 * padding);

        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(capacity * (availableWidth / (double) Math.max(availableHeight, 1)))));
        int rows = (int) Math.ceil(capacity / (double) columns);

        int cellSize = Math.max(10, Math.min(availableWidth / columns, availableHeight / Math.max(rows, 1)) - 4);

        int row = index / columns;
        int col = index % columns;
        int x = padding + gateMargin + col * (cellSize + 4);
        int y = padding + row * (cellSize + 4);
        return new Rectangle(x, y, cellSize, cellSize);
    }

    private Point2D.Double entranceGatePoint() {
        return new Point2D.Double(16, getHeight() / 2.0);
    }

    private Point2D.Double exitGatePoint() {
        return new Point2D.Double(getWidth() - 16, getHeight() / 2.0);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int capacity = parkingLot.getConfig().getBufferCapacity();
        if (capacity <= 0) {
            return;
        }

        drawGate(g2, entranceGatePoint(), "IN", new Color(0x4C, 0xAF, 0x50));
        drawGate(g2, exitGatePoint(), "OUT", new Color(0xE0, 0x4B, 0x4B));

        for (int i = 0; i < capacity && i < slotColor.length; i++) {
            Rectangle r = slotRect(i, capacity);
            boolean isOccupied = slotColor[i] != null;

            g2.setColor(isOccupied ? new Color(0xE0, 0x4B, 0x4B) : new Color(0xE9, 0xF7, 0xEC));
            g2.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 6, 6));
            g2.setColor(isOccupied ? new Color(0xA8, 0x2E, 0x2E) : new Color(0x4C, 0xAF, 0x50));
            g2.draw(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 6, 6));

            // A stationary parked car icon sits on top of the red slot.
            if (isOccupied) {
                drawCarIcon(g2, r.getCenterX(), r.getCenterY(), slotColor[i]);
            }
        }

        if (overflowCount > 0) {
            g2.setColor(new Color(0xC6, 0x28, 0x28));
            g2.setFont(getFont().deriveFont(Font.BOLD, 12f));
            g2.drawString("+" + overflowCount + " over capacity", 16, getHeight() - 8);
        }

        long now = System.currentTimeMillis();
        for (AnimatedCar car : activeAnimations) {
            Point2D.Double pos = car.positionAt(now);
            drawCarIcon(g2, pos.x, pos.y, car.color);
        }
    }

    private void drawGate(Graphics2D g2, Point2D.Double point, String label, Color color) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine((int) point.x, (int) point.y - 18, (int) point.x, (int) point.y + 18);
        g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
        g2.drawString(label, (int) point.x - 8, (int) point.y + 30);
    }

    /** Draws a small top-down car icon centered at (cx, cy). */
    private void drawCarIcon(Graphics2D g2, double cx, double cy, Color color) {
        double bodyWidth = 22;
        double bodyHeight = 12;
        double x = cx - bodyWidth / 2;
        double y = cy - bodyHeight / 2;

        g2.setColor(color);
        g2.fill(new RoundRectangle2D.Double(x, y, bodyWidth, bodyHeight, 5, 5));
        g2.setColor(color.darker());
        g2.draw(new RoundRectangle2D.Double(x, y, bodyWidth, bodyHeight, 5, 5));

        // Windshield accent
        g2.setColor(Color.WHITE);
        g2.fill(new RoundRectangle2D.Double(x + bodyWidth * 0.28, y + bodyHeight * 0.18, bodyWidth * 0.44, bodyHeight * 0.5, 3, 3));

        // Wheels
        g2.setColor(new Color(0x33, 0x33, 0x33));
        double wheelSize = 4;
        g2.fill(new Ellipse2D.Double(x + 2, y - wheelSize / 2.0, wheelSize, wheelSize));
        g2.fill(new Ellipse2D.Double(x + bodyWidth - 6, y - wheelSize / 2.0, wheelSize, wheelSize));
        g2.fill(new Ellipse2D.Double(x + 2, y + bodyHeight - wheelSize / 2.0, wheelSize, wheelSize));
        g2.fill(new Ellipse2D.Double(x + bodyWidth - 6, y + bodyHeight - wheelSize / 2.0, wheelSize, wheelSize));
    }
}
