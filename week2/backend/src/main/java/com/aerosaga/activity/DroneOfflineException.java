package com.aerosaga.activity;

/**
 * Thrown by an activity when the airframe stops answering.
 *
 * <p>This is the hinge of the whole project. The activity throws, the workflow catches it — and
 * instead of retrying or failing, the workflow parks itself on a durable
 * {@code Workflow.await(...)} inside Temporal. At that instant:</p>
 *
 * <ul>
 *   <li>No thread is blocked. The worker is free to run a thousand other workflows.</li>
 *   <li>No timer is counting down in memory. State is in Temporal's database.</li>
 *   <li>You can kill the entire Spring Boot process and the mission stays exactly here.</li>
 * </ul>
 *
 * <p>Two hours later a {@code reconnect()} signal walks it straight back into the loop, at the
 * exact waypoint it stalled on.</p>
 */
public class DroneOfflineException extends RuntimeException {

    public DroneOfflineException(String droneId) {
        super("Drone " + droneId + " lost its link");
    }

    public DroneOfflineException(String droneId, String detail) {
        super("Drone " + droneId + " lost its link: " + detail);
    }

    public DroneOfflineException(String droneId, Throwable cause) {
        super("Drone " + droneId + " lost its link", cause);
    }

    /**
     * Walk the cause chain to see whether the failure we were handed really is a lost link.
     *
     * <p>Activity failures reach the workflow wrapped in {@code ActivityCompletionException}, and
     * the SDK's retry machinery may add layers in between, so we unwrap rather than compare
     * directly.</p>
     */
    public static boolean isCausedBy(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth++ < 20) {
            if (cursor instanceof DroneOfflineException) {
                return true;
            }
            // The subtlety that decides whether the demo works: a custom exception thrown by an
            // activity crosses the wire as an ApplicationFailure whose type is the original
            // class NAME — the instance itself is usually not rebuilt on the workflow side. An
            // instanceof-only chain walk therefore never matches, and the workflow fails instead
            // of suspending.
            if (cursor instanceof io.temporal.failure.ApplicationFailure appFailure
                    && DroneOfflineException.class.getName().equals(appFailure.getType())) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
