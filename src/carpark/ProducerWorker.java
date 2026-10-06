package carpark;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulates a car owner arriving and looking for a parking lot. Each
 * {@code ProducerWorker} runs on its own {@link Thread}, repeatedly creating
 * a new {@link Car} and handing it to the shared {@link ParkingLot}, then
 * sleeping for a randomized interval to simulate "varying speeds" of car
 * arrivals.
 */
public final class ProducerWorker implements Runnable {

    private final int id;
    private final ParkingLot parkingLot;
    private final SimConfig config;

    private final AtomicReference<ThreadStatus> status = new AtomicReference<>(ThreadStatus.IDLE);
    private volatile boolean running = true;
    private volatile boolean crashRequested = false;

    /**
     * @param id         a display identifier for this producer (e.g. 1, 2, 3...)
     * @param parkingLot the shared buffer this producer parks cars into
     * @param config     shared, live-tunable simulation settings
     */
    public ProducerWorker(int id, ParkingLot parkingLot, SimConfig config) {
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
                    // Simulated fault, used to demonstrate the "Crashed"
                    // status on the dashboard and confirm the rest of the
                    // system keeps running safely without this thread.
                    throw new IllegalStateException("Simulated producer fault (forced crash)");
                }

                Car car = new Car();
                parkingLot.parkCar(car, this);

                int sleepMs = ThreadLocalRandom.current().nextInt(
                        config.getMinProductionIntervalMs(),
                        config.getMaxProductionIntervalMs() + 1);
                Thread.sleep(sleepMs);
            }
            status.set(ThreadStatus.STOPPED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.set(ThreadStatus.STOPPED);
        } catch (RuntimeException e) {
            status.set(ThreadStatus.CRASHED);
        }
    }

    /** Signals this producer's loop to exit gracefully after its next step. */
    public void stopGracefully() {
        running = false;
    }

    /** Requests that this producer throw a simulated fault on its next cycle. */
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
        return "Producer-" + id;
    }
}
