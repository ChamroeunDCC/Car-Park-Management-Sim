package carpark;

/**
 * Represents the visible lifecycle state of a {@link ProducerWorker} or
 * {@link ConsumerWorker} thread. The GUI dashboard polls this value to
 * render a colored status icon next to each thread's name.
 */
public enum ThreadStatus {

    /** Thread has been created but has not started running yet. */
    IDLE,

    /** Thread is currently running/producing/consuming normally. */
    ACTIVE,

    /** Thread is blocked on a condition variable (buffer full/empty). */
    WAITING,

    /** Thread terminated because of an unexpected/simulated fault. */
    CRASHED,

    /** Thread was stopped gracefully by the user. */
    STOPPED
}
