package carpark;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulates a parking sign / security guard directing a car to an available
 * parking lot. Each {@code ConsumerWorker} runs on its own {@link Thread},
 * repeatedly taking a {@link Car} from the shared {@link ParkingLot} and
 * then sleeping for the configured "processing time" to simulate the work
 * of guiding that car to its spot.
 */
public final class ConsumerWorker implements Runnable {

    private final int id;
    private final ParkingLot parkingLot;
    private final SimConfig config;

    private final AtomicReference<ThreadStatus> status = new AtomicReference<>(ThreadStatus.IDLE);
    private volatile boolean running = true;
    private volatile boolean crashRequested = false;

    /**
     * @param id         a display identifier for this consumer (e.g. 1, 2, 3...)
     * @param parkingLot the shared buffer this consumer takes cars from
     * @param config     shared, live-tunable simulation settings
     */
    public ConsumerWorker(int id, ParkingLot parkingLot, SimConfig config) {
        this.id = id;
        this.parkingLot = parkingLot;
        this.config = config;
    }

    @Override
    public void run() {
        status.set(ThreadStatus.ACTIVE);
        try {
            while (running) {
                if (crashRequested) {
                    crashRequested = false;
                    throw new IllegalStateException("Simulated consumer fault (forced crash)");
                }

                Car car = parkingLot.retrieveCar(this);

                int processingMs = config.getProcessingTimeMs();
                if (processingMs > 0) {
                    Thread.sleep(processingMs);
                }
            }
            status.set(ThreadStatus.STOPPED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.set(ThreadStatus.STOPPED);
        } catch (RuntimeException e) {
            status.set(ThreadStatus.CRASHED);
        }
    }

    /** Signals this consumer's loop to exit gracefully after its next step. */
    public void stopGracefully() {
        running = false;
    }

    /** Requests that this consumer throw a simulated fault on its next cycle. */
    public void triggerCrash() {
        crashRequested = true;
    }

    /** @return the current visible lifecycle status of this thread */
    public ThreadStatus getStatus() {
        return status.get();
    }

    /**
     * Package-visible setter used by {@link ParkingLot} to reflect blocking
     * behaviour (WAITING/ACTIVE) back onto this worker's status.
     */
    void setStatus(ThreadStatus newStatus) {
        status.set(newStatus);
    }

    public int getId() {
        return id;
    }

    @Override
    public String toString() {
        return "Consumer-" + id;
    }
}
