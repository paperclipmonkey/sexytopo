package org.hwyl.sexytopo.comms;

import android.os.Handler;
import android.os.Looper;
import org.hwyl.sexytopo.R;
import org.hwyl.sexytopo.control.Log;
import org.hwyl.sexytopo.control.util.GeneralPreferences;

/**
 * Decides whether a communicator should reconnect after losing its device, and schedules the
 * attempts. Only unexpected disconnections are worth reconnecting after, so communicators report
 * user intent as well as trouble.
 *
 * <p>Attempts stop once the window set in the preferences has elapsed, measured from the first
 * failure in a run, so a device left behind in a cave doesn't keep the radio busy all day. A run
 * that succeeds resets the window, so an instrument that drops out repeatedly while it's being used
 * - a BRIC4 dozing off between shots, say - is chased indefinitely.
 *
 * <p>Not thread safe, and by default it schedules onto the main looper: call it from the main
 * thread.
 */
public class ReconnectionPolicy {

    private static final long RETRY_INTERVAL_MS = 3000;

    /** Defers a retry. Injectable so the timing can be driven by hand in tests. */
    public interface Scheduler {
        void postDelayed(Runnable runnable, long delayMs);

        void cancelAll();
    }

    /** The parts of the preferences this cares about, so tests can vary them. */
    public interface Settings {
        boolean isAutoReconnectOn();

        long getWindowMs();
    }

    /** The current time in milliseconds. */
    public interface Clock {
        long now();
    }

    private static final Settings PREFERENCES =
            new Settings() {
                @Override
                public boolean isAutoReconnectOn() {
                    return GeneralPreferences.isAutoReconnectOn();
                }

                @Override
                public long getWindowMs() {
                    return GeneralPreferences.getAutoReconnectWindowMinutes() * 60_000L;
                }
            };

    private final String deviceName;
    private final Runnable reconnect;
    private final Scheduler scheduler;
    private final Settings settings;
    private final Clock clock;

    private boolean userRequestedDisconnect = false;

    /** When the current run of attempts must give up; null if no run is in progress. */
    private Long giveUpAt = null;

    /** Whether a reconnection attempt we scheduled is currently in flight. */
    private boolean retrying = false;

    public ReconnectionPolicy(String deviceName, Runnable reconnect) {
        this(deviceName, reconnect, new HandlerScheduler(), PREFERENCES, System::currentTimeMillis);
    }

    ReconnectionPolicy(
            String deviceName,
            Runnable reconnect,
            Scheduler scheduler,
            Settings settings,
            Clock clock) {
        this.deviceName = deviceName;
        this.reconnect = reconnect;
        this.scheduler = scheduler;
        this.settings = settings;
        this.clock = clock;
    }

    /** Call when the user asks to connect, so a later drop counts as unexpected. */
    public void noteUserRequestedConnect() {
        userRequestedDisconnect = false;
        if (!retrying) { // a fresh start by hand, rather than one of our own retries
            giveUpAt = null;
        }
    }

    /** Call when the user asks to disconnect, so we leave the device alone. */
    public void noteUserRequestedDisconnect() {
        userRequestedDisconnect = true;
        cancel();
    }

    /**
     * Call once the device is properly usable, so the next failure starts a fresh window. Don't
     * call this merely on connecting: a link that comes up and immediately drops again would keep
     * resetting the window, and we'd never give up.
     */
    public void noteReady() {
        giveUpAt = null;
    }

    /** Call when the device drops out or fails to connect. */
    public void onUnexpectedDisconnection() {

        if (userRequestedDisconnect || !settings.isAutoReconnectOn()) {
            return;
        }

        long now = clock.now();

        if (giveUpAt == null) {
            giveUpAt = now + settings.getWindowMs();
        } else if (now >= giveUpAt) {
            Log.device(R.string.device_ble_auto_reconnect_gave_up);
            giveUpAt = null;
            return;
        }

        Log.device(R.string.device_ble_auto_reconnecting, deviceName);
        scheduler.postDelayed(
                () -> {
                    retrying = true;
                    try {
                        reconnect.run();
                    } finally {
                        retrying = false;
                    }
                },
                RETRY_INTERVAL_MS);
    }

    /**
     * Whether a run of attempts is under way. Worth showing: "reconnecting" and "not connected"
     * mean different things to someone deciding whether to walk back for the instrument.
     */
    public boolean isReconnecting() {
        return giveUpAt != null;
    }

    /** Call when the communicator is being torn down, to drop any pending attempt. */
    public void cancel() {
        scheduler.cancelAll();
        giveUpAt = null;
        retrying = false;
    }

    /**
     * How long a retry loop should keep going, in milliseconds, or zero if auto-reconnect is off.
     * The DistoX communicators drive their own loops from a background thread, so they can't use
     * the scheduling above but can still apply the same preference and time limit.
     */
    public static long getRetryWindowMs() {

        if (!GeneralPreferences.isAutoReconnectOn()) {
            return 0;
        }

        return GeneralPreferences.getAutoReconnectWindowMinutes() * 60_000L;
    }

    /** The real scheduler: the main looper, which is where the BLE callbacks arrive. */
    private static class HandlerScheduler implements Scheduler {

        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            handler.postDelayed(runnable, delayMs);
        }

        @Override
        public void cancelAll() {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
