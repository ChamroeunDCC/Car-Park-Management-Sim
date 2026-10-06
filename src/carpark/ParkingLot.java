package carpark;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The shared "Buffer" (parking lot) that sits at the heart of this
 * classic bounded-buffer producer/consumer problem.
 *
 * <p><b>Synchronization strategy:</b> a single {@link ReentrantLock} guards
 * the internal queue (the critical section), and two {@link Condition}
 * variables are used to put threads to sleep instead of busy-waiting:</p>
 * <ul>
 *   <li>{@code notFull}  - producers wait here when the lot is full, and are
 *       woken up by a consumer after it frees a slot.</li>
 *   <li>{@code notEmpty} - consumers wait here when the lot is empty, and are
 *       woken up by a producer after it parks a car.</li>
 * </ul>
 *
 * <p>This class also aggregates the statistics shown on the dashboard
 * (throughput, average wait time) using {@link AtomicLong} counters so that
 * simple numeric statistics can be read by the GUI thread without needing to
 * acquire the main lock.</p>
 */
public final class ParkingLot {

    private final Deque<Car> occupiedSlots = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    private final SimConfig config;

    private final AtomicLong totalCarsProduced = new AtomicLong(0);
    private final AtomicLong totalCarsConsumed = new AtomicLong(0);
    private final AtomicLong totalWaitTimeNanos = new AtomicLong(0);
    private final AtomicLong totalWaitSamples = new AtomicLong(0);

    public ParkingLot(SimConfig config) {
        this.config = config;
    }

    /**
     * Places a car into the lot, blocking the calling producer thread on the
     * {@code notFull} condition while the lot is at capacity.
     *
     * @param car    the car to park
     * @param caller the producer performing the operation, used only to
     *               update its visible {@link ThreadStatus} while blocked
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void parkCar(Car car, ProducerWorker caller) throws InterruptedException {
        lock.lock();
        try {
            while (occupiedSlots.size() >= config.getBufferCapacity()) {
                caller.setStatus(ThreadStatus.WAITING);
                notFull.await();
            }
            occupiedSlots.addLast(car);
            totalCarsProduced.incrementAndGet();
            caller.setStatus(ThreadStatus.ACTIVE);
            // Wake any consumer(s) sleeping because the lot was empty.
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes and returns the next car from the lot, blocking the calling
     * consumer thread on the {@code notEmpty} condition while the lot is
     * empty. Also records how long the consumer had to wait, which feeds
     * the "Wait Time" dashboard statistic.
     *
     * @param caller the consumer performing the operation
     * @return the retrieved car
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public Car retrieveCar(ConsumerWorker caller) throws InterruptedException {
        lock.lock();
        long waitStartNanos = System.nanoTime();
        try {
            while (occupiedSlots.isEmpty()) {
                caller.setStatus(ThreadStatus.WAITING);
                notEmpty.await();
            }
            Car car = occupiedSlots.removeFirst();
            long waitedNanos = System.nanoTime() - waitStartNanos;
            totalWaitTimeNanos.addAndGet(waitedNanos);
            totalWaitSamples.incrementAndGet();
            totalCarsConsumed.incrementAndGet();
            caller.setStatus(ThreadStatus.ACTIVE);
            // Wake any producer(s) sleeping because the lot was full.
            notFull.signalAll();
            return car;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wakes up every thread currently waiting on either condition. This is
     * called whenever the buffer capacity is changed at runtime so that
     * producers blocked on a now-stale "full" condition get a chance to
     * re-check it.
     */
    public void wakeAllWaitingThreads() {
        lock.lock();
        try {
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears the buffer and resets all statistics back to zero. Intended
     * to be called only while no producer/consumer threads are actively
     * running (e.g. right after they have all been stopped), so this does
     * not attempt to wake or coordinate with in-flight operations beyond
     * the safety already provided by the lock.
     */
    public void reset() {
        lock.lock();
        try {
            occupiedSlots.clear();
            totalCarsProduced.set(0);
            totalCarsConsumed.set(0);
            totalWaitTimeNanos.set(0);
            totalWaitSamples.set(0);
            notFull.signalAll();
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** @return number of cars currently occupying a parking slot */
    public int getOccupiedCount() {
        lock.lock();
        try {
            return occupiedSlots.size();
        } finally {
            lock.unlock();
        }
    }

    /** @return total number of cars produced (parked) since the sim started */
    public long getTotalCarsProduced() {
        return totalCarsProduced.get();
    }

    /** @return total number of cars consumed (guided out) since the sim started */
    public long getTotalCarsConsumed() {
        return totalCarsConsumed.get();
    }

    /** @return the average time (in milliseconds) a consumer has waited for a car */
    public double getAverageWaitTimeMillis() {
        long samples = totalWaitSamples.get();
        if (samples == 0) {
            return 0.0;
        }
        return (totalWaitTimeNanos.get() / (double) samples) / 1_000_000.0;
    }

    public SimConfig getConfig() {
        return config;
    }
}
