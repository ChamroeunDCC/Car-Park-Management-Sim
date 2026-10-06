# Car Park Management Sim

A Java Swing simulation of automated car park management, built as a classic
**bounded-buffer producer/consumer problem**.

## How to compile and run

```bash
# From the project's src/ folder:
javac -d out src/carpark/*.java
java -cp out carpark.CarParkSimApp
```

(Requires JDK 11+; no external dependencies.)

## Project structure

| File | Responsibility |
|---|---|
| `Car.java` | Immutable model of a single car (id + creation timestamp). |
| `ThreadStatus.java` | Enum of thread states shown on the dashboard: IDLE, ACTIVE, WAITING, CRASHED, STOPPED. |
| `SimConfig.java` | Shared, validated, live-tunable settings (buffer capacity, production rate range, processing time). |
| `ParkingLot.java` | The buffer/critical section. Uses `ReentrantLock` + two `Condition` variables (`notFull`, `notEmpty`) — the classic bounded-buffer solution. Also aggregates dashboard statistics. |
| `ProducerWorker.java` | Runnable simulating a car owner looking for parking; runs on its own thread. |
| `ConsumerWorker.java` | Runnable simulating a sign/guard directing cars to a spot; runs on its own thread. |
| `ParkingLotVisualPanel.java` | Custom-painted Swing panel drawing the occupancy grid. |
| `CarParkSimApp.java` | Main window: wires up controls, manages threads, refreshes the dashboard via a `javax.swing.Timer`. |

## How synchronization is implemented (maps to rubric #3/#4)

- **Mutual exclusion:** a single `ReentrantLock` in `ParkingLot` guards the
  internal queue (the critical section), so only one thread can mutate it at
  a time — no two threads can ever grab the same slot.
- **Condition variables:** `notFull.await()` puts producers to sleep when
  the lot is full; `notEmpty.await()` puts consumers to sleep when the lot
  is empty. `signalAll()` wakes the correct side up after every successful
  operation, and `wakeAllWaitingThreads()` is called whenever the user
  changes the buffer capacity at runtime so blocked threads re-check the
  new limit instead of staying stuck on a stale condition.
- **Thread-safety of statistics:** counters (produced, consumed, wait time)
  use `AtomicLong`, so the GUI can read live numbers without taking the main
  lock and without ever seeing a torn/inconsistent value.

## Dashboard features (maps to rubric "Visual Dashboard")

- Occupied Parking Lot (%) and Available Parking Lot count
- Throughput (cars processed per second, recalculated every ~1s)
- Average consumer wait time (ms)
- A live grid visualizing each parking slot (red = occupied, green = free)
- Per-thread status rows with colored icons (green = active, orange =
  waiting/blocked, red = crashed, gray = stopped) for every producer and
  consumer, plus a "Crash" button per thread to demonstrate/verify that a
  simulated fault is caught, reflected on the dashboard, and does not bring
  down the rest of the simulation.

## Real-time tunables (maps to rubric "Game Variables")

All exposed as spinners in the top control bar and applied instantly (no
restart needed): Buffer Capacity (default 9), Production Rate (default
400-3000 ms between cars), Processing Time (ms a consumer takes to guide a
car to its spot). Producers and consumers can also be added/removed while
the simulation is running, and a **Reset** button restores everything
(buffer, statistics, thread count, and every spinner) back to these
defaults in one click.

## Realistic slot assignment

Cars fill and vacate slots in a **randomized, scattered order** rather
than always filling 1,2,3...N and draining N,N-1,...1 \u2014 e.g. the lot
might go from 1 car parked to 9, then straight back down to 9-out-of-9
occupied in a completely different arrangement, just like a real parking
lot. This is handled by `ParkingLotVisualPanel`, which picks a random free
slot for each arriving car and a random occupied slot for each departing
one, while the real `ParkingLot` buffer underneath continues to enforce
capacity strictly via its lock and condition variables regardless of which
visual slot a car cosmetically appears in.

