package carpark;

/**
 * Holds all "game variables" that the user can tune in real time from the
 * GUI: buffer capacity, production rate range, and consumer processing
 * time. All fields are {@code volatile} so that changes made on the Swing
 * Event Dispatch Thread become immediately visible to the producer and
 * consumer worker threads without requiring explicit locking, since each
 * field is read/written independently and does not need compound atomicity.
 *
 * <p>All setters validate their input and throw
 * {@link IllegalArgumentException} on invalid values, guarding the
 * simulation against nonsensical states (e.g. a negative buffer size).</p>
 */
public final class SimConfig {

    private volatile int bufferCapacity;
    private volatile int minProductionIntervalMs;
    private volatile int maxProductionIntervalMs;
    private volatile int processingTimeMs;

    /**
     * Creates a configuration with sensible defaults:
     * capacity = 9 lots, production interval = 400-3000 ms,
     * processing time = 800 ms.
     */
    public SimConfig() {
        this.bufferCapacity = 9;
        this.minProductionIntervalMs = 400;
        this.maxProductionIntervalMs = 3000;
        this.processingTimeMs = 800;
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }

    public void setBufferCapacity(int bufferCapacity) {
        if (bufferCapacity < 1) {
            throw new IllegalArgumentException("Buffer capacity must be >= 1");
        }
        this.bufferCapacity = bufferCapacity;
    }

    public int getMinProductionIntervalMs() {
        return minProductionIntervalMs;
    }

    public int getMaxProductionIntervalMs() {
        return maxProductionIntervalMs;
    }

    /**
     * Sets the production rate as a range in milliseconds. Producers sleep a
     * random duration inside this range between generating cars, which is
     * how the "varying speeds" requirement is simulated.
     *
     * @param minMs lower bound (inclusive), must be > 0
     * @param maxMs upper bound (inclusive), must be >= minMs
     */
    public void setProductionIntervalRangeMs(int minMs, int maxMs) {
        if (minMs <= 0 || maxMs <= 0) {
            throw new IllegalArgumentException("Production interval must be > 0");
        }
        if (maxMs < minMs) {
            throw new IllegalArgumentException("Max interval cannot be less than min interval");
        }
        this.minProductionIntervalMs = minMs;
        this.maxProductionIntervalMs = maxMs;
    }

    public int getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(int processingTimeMs) {
        if (processingTimeMs < 0) {
            throw new IllegalArgumentException("Processing time cannot be negative");
        }
        this.processingTimeMs = processingTimeMs;
    }
}
