package lsmkv;

/**
 * Failpoints: a test seam that lets a test stop the engine at a named step. A no-op in production.
 */
final class CrashPoints {
    private CrashPoints() {}

    interface Hook {
        void at(String point);
    }

    /** Thrown by tests to simulate the process dying at a point. */
    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String point) {
            super("simulated crash at " + point);
        }
    }

    private static final Hook NOOP = point -> { };
    private static volatile Hook hook = NOOP;

    static void set(Hook h) {
        hook = h == null ? NOOP : h;
    }

    static void reset() {
        hook = NOOP;
    }

    static void at(String point) {
        hook.at(point);
    }
}