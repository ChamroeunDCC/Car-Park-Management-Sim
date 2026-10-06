package carpark;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point and main window for the "Car Park Management Sim".
 *
 * <p>This class is responsible only for GUI wiring: building the Swing
 * components, reacting to user input, starting/stopping worker threads, and
 * periodically refreshing the dashboard. All actual synchronization and
 * simulation logic lives in {@link ParkingLot}, {@link ProducerWorker} and
 * {@link ConsumerWorker}, keeping this class focused on a single
 * responsibility (presentation and control).</p>
 */
public final class CarParkSimApp extends JFrame {

    /** Pairs a running producer with its underlying thread and its dashboard row UI. */
    private static final class ProducerHandle {
        final ProducerWorker worker;
        final Thread thread;
        final JPanel row;
        final JLabel icon;
        final JLabel text;

        ProducerHandle(ProducerWorker worker, Thread thread, JPanel row, JLabel icon, JLabel text) {
            this.worker = worker;
            this.thread = thread;
            this.row = row;
            this.icon = icon;
            this.text = text;
        }
    }

    /** Pairs a running consumer with its underlying thread and its dashboard row UI. */
    private static final class ConsumerHandle {
        final ConsumerWorker worker;
        final Thread thread;
        final JPanel row;
        final JLabel icon;
        final JLabel text;

        ConsumerHandle(ConsumerWorker worker, Thread thread, JPanel row, JLabel icon, JLabel text) {
            this.worker = worker;
            this.thread = thread;
            this.row = row;
            this.icon = icon;
            this.text = text;
        }
    }

    private final SimConfig config = new SimConfig();
    private final ParkingLot parkingLot = new ParkingLot(config);

    private final List<ProducerHandle> producers = new CopyOnWriteArrayList<>();
    private final List<ConsumerHandle> consumers = new CopyOnWriteArrayList<>();
    private final AtomicInteger producerIdSeq = new AtomicInteger(1);
    private final AtomicInteger consumerIdSeq = new AtomicInteger(1);

    // Dashboard components
    private final JLabel occupiedPercentLabel = new JLabel();
    private final JLabel availableLabel = new JLabel();
    private final JLabel throughputLabel = new JLabel();
    private final JLabel avgWaitLabel = new JLabel();
    private final ParkingLotVisualPanel visualPanel = new ParkingLotVisualPanel(parkingLot);
    private final JPanel producerListPanel = new JPanel();
    private final JPanel consumerListPanel = new JPanel();

    // Control spinners (kept as fields so the Reset button can restore their displayed values)
    private JSpinner capacitySpinner;
    private JSpinner minRateSpinner;
    private JSpinner maxRateSpinner;
    private JSpinner processingSpinner;

    /** Number of producers/consumers the simulation starts (and resets back to) by default. */
    private static final int DEFAULT_PRODUCER_COUNT = 2;
    private static final int DEFAULT_CONSUMER_COUNT = 2;

    // Throughput tracking
    private long lastThroughputCheckMillis = System.currentTimeMillis();
    private long lastConsumedSnapshot = 0;
    private double lastThroughputValue = 0.0;

    public CarParkSimApp() {
        super("Car Park Management Sim");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        add(buildControlPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);

        setSize(1100, 720);
        setLocationRelativeTo(null);

        // Seed the simulation with a couple of producers/consumers so the
        // user sees activity immediately on launch.
        for (int i = 0; i < DEFAULT_PRODUCER_COUNT; i++) {
            addProducer();
        }
        for (int i = 0; i < DEFAULT_CONSUMER_COUNT; i++) {
            addConsumer();
        }

        Timer refreshTimer = new Timer(150, e -> refreshDashboard());
        refreshTimer.start();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private JPanel buildControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Game Variables & Controls"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        int col = 0;

        gbc.gridx = col++; gbc.gridy = 0;
        panel.add(new JLabel("Buffer Capacity:"), gbc);
        capacitySpinner = new JSpinner(new SpinnerNumberModel(config.getBufferCapacity(), 1, 200, 1));
        capacitySpinner.addChangeListener(e -> {
            config.setBufferCapacity((Integer) capacitySpinner.getValue());
            parkingLot.wakeAllWaitingThreads();
        });
        gbc.gridx = col++;
        panel.add(capacitySpinner, gbc);

        gbc.gridx = col++;
        panel.add(new JLabel("Production Rate (ms min/max):"), gbc);
        minRateSpinner = new JSpinner(new SpinnerNumberModel(config.getMinProductionIntervalMs(), 10, 10000, 50));
        maxRateSpinner = new JSpinner(new SpinnerNumberModel(config.getMaxProductionIntervalMs(), 10, 10000, 50));
        gbc.gridx = col++;
        panel.add(minRateSpinner, gbc);
        gbc.gridx = col++;
        panel.add(maxRateSpinner, gbc);
        ChangeListenerHelper.link(minRateSpinner, maxRateSpinner, config);

        gbc.gridx = col++;
        panel.add(new JLabel("Processing Time (ms):"), gbc);
        processingSpinner = new JSpinner(new SpinnerNumberModel(config.getProcessingTimeMs(), 0, 10000, 50));
        processingSpinner.addChangeListener(e -> config.setProcessingTimeMs((Integer) processingSpinner.getValue()));
        gbc.gridx = col++;
        panel.add(processingSpinner, gbc);

        // Second row: thread management buttons + reset
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton addProducerBtn = new JButton("+ Producer");
        JButton removeProducerBtn = new JButton("- Producer");
        JButton addConsumerBtn = new JButton("+ Consumer");
        JButton removeConsumerBtn = new JButton("- Consumer");
        JButton resetBtn = new JButton("Reset");
        resetBtn.setForeground(new Color(0xA8, 0x2E, 0x2E));
        addProducerBtn.addActionListener(e -> addProducer());
        removeProducerBtn.addActionListener(e -> removeLastProducer());
        addConsumerBtn.addActionListener(e -> addConsumer());
        removeConsumerBtn.addActionListener(e -> removeLastConsumer());
        resetBtn.addActionListener(e -> resetSimulation());
        buttonRow.add(addProducerBtn);
        buttonRow.add(removeProducerBtn);
        buttonRow.add(new JSeparator(SwingConstants.VERTICAL));
        buttonRow.add(addConsumerBtn);
        buttonRow.add(removeConsumerBtn);
        buttonRow.add(new JSeparator(SwingConstants.VERTICAL));
        buttonRow.add(resetBtn);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = col;
        panel.add(buttonRow, gbc);

        return panel;
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel(new BorderLayout(8, 8));

        center.add(buildStatsBar(), BorderLayout.NORTH);

        JPanel visualWrapper = new JPanel(new BorderLayout());
        visualWrapper.setBorder(new TitledBorder("Parking Lot (Buffer)"));
        visualWrapper.add(visualPanel, BorderLayout.CENTER);

        JPanel threadPanels = buildThreadStatusPanel();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, visualWrapper, threadPanels);
        splitPane.setResizeWeight(0.55);
        center.add(splitPane, BorderLayout.CENTER);

        return center;
    }

    private JPanel buildStatsBar() {
        JPanel bar = new JPanel(new GridLayout(1, 4, 10, 0));
        bar.setBorder(new EmptyBorder(6, 6, 6, 6));
        bar.add(statCard("Occupied", occupiedPercentLabel));
        bar.add(statCard("Available", availableLabel));
        bar.add(statCard("Throughput (items/sec)", throughputLabel));
        bar.add(statCard("Avg Consumer Wait", avgWaitLabel));
        return bar;
    }

    private JPanel statCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createLineBorder(new Color(0xCC, 0xCC, 0xCC)));
        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 11f));
        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 18f));
        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildThreadStatusPanel() {
        JPanel wrapper = new JPanel(new GridLayout(1, 2, 6, 0));

        producerListPanel.setLayout(new BoxLayout(producerListPanel, BoxLayout.Y_AXIS));
        consumerListPanel.setLayout(new BoxLayout(consumerListPanel, BoxLayout.Y_AXIS));

        JScrollPane producerScroll = new JScrollPane(producerListPanel);
        producerScroll.setBorder(new TitledBorder("Producers (Cars Arriving)"));
        JScrollPane consumerScroll = new JScrollPane(consumerListPanel);
        consumerScroll.setBorder(new TitledBorder("Consumers (Guides / Signs)"));

        wrapper.add(producerScroll);
        wrapper.add(consumerScroll);
        return wrapper;
    }

    // ------------------------------------------------------------------
    // Thread management
    // ------------------------------------------------------------------

    private void addProducer() {
        int id = producerIdSeq.getAndIncrement();
        ProducerWorker worker = new ProducerWorker(id, parkingLot, config);
        Thread thread = new Thread(worker, "Producer-" + id);
        thread.setDaemon(true);

        JLabel icon = new JLabel("\u25CF");
        JLabel text = new JLabel("Producer-" + id + " : IDLE");
        JButton crashBtn = new JButton("Crash");
        crashBtn.addActionListener(e -> worker.triggerCrash());

        JPanel row = buildRow(icon, text, crashBtn);
        producerListPanel.add(row);
        producerListPanel.revalidate();

        ProducerHandle handle = new ProducerHandle(worker, thread, row, icon, text);
        producers.add(handle);
        thread.start();
    }

    private void removeLastProducer() {
        if (producers.isEmpty()) {
            return;
        }
        ProducerHandle handle = producers.remove(producers.size() - 1);
        handle.worker.stopGracefully();
        handle.thread.interrupt();
        producerListPanel.remove(handle.row);
        producerListPanel.revalidate();
        producerListPanel.repaint();
    }

    private void addConsumer() {
        int id = consumerIdSeq.getAndIncrement();
        ConsumerWorker worker = new ConsumerWorker(id, parkingLot, config);
        Thread thread = new Thread(worker, "Consumer-" + id);
        thread.setDaemon(true);

        JLabel icon = new JLabel("\u25CF");
        JLabel text = new JLabel("Consumer-" + id + " : IDLE");
        JButton crashBtn = new JButton("Crash");
        crashBtn.addActionListener(e -> worker.triggerCrash());

        JPanel row = buildRow(icon, text, crashBtn);
        consumerListPanel.add(row);
        consumerListPanel.revalidate();

        ConsumerHandle handle = new ConsumerHandle(worker, thread, row, icon, text);
        consumers.add(handle);
        thread.start();
    }

    private void removeLastConsumer() {
        if (consumers.isEmpty()) {
            return;
        }
        ConsumerHandle handle = consumers.remove(consumers.size() - 1);
        handle.worker.stopGracefully();
        handle.thread.interrupt();
        consumerListPanel.remove(handle.row);
        consumerListPanel.revalidate();
        consumerListPanel.repaint();
    }

    private JPanel buildRow(JLabel icon, JLabel text, JButton crashBtn) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        icon.setForeground(Color.GRAY);
        row.add(icon);
        row.add(text);
        row.add(crashBtn);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return row;
    }

    /**
     * Restores the simulation to its default starting state: stops and
     * removes every current producer/consumer thread, clears the buffer
     * and its statistics, resets the tunable game variables (buffer
     * capacity = 9, production rate = 400-3000 ms) back to their defaults,
     * clears the visual dashboard, and re-seeds the default number of
     * producers/consumers so activity resumes immediately.
     */
    private void resetSimulation() {
        for (ProducerHandle handle : producers) {
            handle.worker.stopGracefully();
            handle.thread.interrupt();
            producerListPanel.remove(handle.row);
        }
        producers.clear();

        for (ConsumerHandle handle : consumers) {
            handle.worker.stopGracefully();
            handle.thread.interrupt();
            consumerListPanel.remove(handle.row);
        }
        consumers.clear();

        producerListPanel.revalidate();
        producerListPanel.repaint();
        consumerListPanel.revalidate();
        consumerListPanel.repaint();

        parkingLot.reset();
        visualPanel.reset();

        // Restoring the spinner values fires their change listeners, which
        // pushes the defaults back into SimConfig for us.
        capacitySpinner.setValue(9);
        minRateSpinner.setValue(400);
        maxRateSpinner.setValue(3000);
        processingSpinner.setValue(800);

        lastThroughputCheckMillis = System.currentTimeMillis();
        lastConsumedSnapshot = 0;
        lastThroughputValue = 0.0;

        for (int i = 0; i < DEFAULT_PRODUCER_COUNT; i++) {
            addProducer();
        }
        for (int i = 0; i < DEFAULT_CONSUMER_COUNT; i++) {
            addConsumer();
        }
    }

    // ------------------------------------------------------------------
    // Dashboard refresh (runs on the Swing Event Dispatch Thread via Timer)
    // ------------------------------------------------------------------

    private void refreshDashboard() {
        int capacity = config.getBufferCapacity();
        int occupied = parkingLot.getOccupiedCount();
        int available = Math.max(0, capacity - occupied);
        double percent = capacity == 0 ? 0 : (occupied * 100.0 / capacity);

        occupiedPercentLabel.setText(String.format("%.0f%%", percent));
        availableLabel.setText(String.valueOf(available));
        avgWaitLabel.setText(String.format("%.1f ms", parkingLot.getAverageWaitTimeMillis()));

        long now = System.currentTimeMillis();
        long elapsed = now - lastThroughputCheckMillis;
        if (elapsed >= 1000) {
            long consumedNow = parkingLot.getTotalCarsConsumed();
            long delta = consumedNow - lastConsumedSnapshot;
            lastThroughputValue = delta / (elapsed / 1000.0);
            lastConsumedSnapshot = consumedNow;
            lastThroughputCheckMillis = now;
        }
        throughputLabel.setText(String.format("%.2f", lastThroughputValue));

        visualPanel.reportOccupancy(occupied);

        for (ProducerHandle handle : producers) {
            ThreadStatus status = handle.worker.getStatus();
            handle.icon.setForeground(colorFor(status));
            handle.text.setText(handle.worker + " : " + status);
        }
        for (ConsumerHandle handle : consumers) {
            ThreadStatus status = handle.worker.getStatus();
            handle.icon.setForeground(colorFor(status));
            handle.text.setText(handle.worker + " : " + status);
        }
    }

    private Color colorFor(ThreadStatus status) {
        return switch (status) {
            case ACTIVE -> new Color(0x2E, 0x7D, 0x32);
            case WAITING -> new Color(0xF9, 0xA8, 0x25);
            case CRASHED -> new Color(0xC6, 0x28, 0x28);
            case STOPPED -> Color.GRAY;
            case IDLE -> Color.LIGHT_GRAY;
        };
    }

    /**
     * Small internal helper that keeps the min/max production-rate spinners
     * consistent with each other and pushes valid changes into
     * {@link SimConfig}.
     */
    private static final class ChangeListenerHelper {
        static void link(JSpinner minSpinner, JSpinner maxSpinner, SimConfig config) {
            Runnable pushValues = () -> {
                int min = (Integer) minSpinner.getValue();
                int max = (Integer) maxSpinner.getValue();
                if (max < min) {
                    max = min;
                    maxSpinner.setValue(max);
                }
                config.setProductionIntervalRangeMs(min, max);
            };
            minSpinner.addChangeListener(e -> pushValues.run());
            maxSpinner.addChangeListener(e -> pushValues.run());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Fall back to default look and feel if system L&F is unavailable.
            }
            CarParkSimApp app = new CarParkSimApp();
            app.setVisible(true);
        });
    }
}
