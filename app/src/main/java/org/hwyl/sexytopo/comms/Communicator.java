package org.hwyl.sexytopo.comms;

import java.util.HashMap;
import java.util.Map;

public interface Communicator {

    Map<Integer, Integer> NO_COMMANDS = new HashMap<>();

    boolean isConnected();

    /**
     * Whether the communicator has lost the instrument and is trying to get it back, so the device
     * screen can say so rather than just showing a switch flipped off.
     */
    default boolean isReconnecting() {
        return false;
    }

    void requestConnect();

    void requestDisconnect();

    default Map<Integer, Integer> getCustomCommands() {
        return NO_COMMANDS;
    }

    default boolean handleCustomCommand(int viewId) {
        return false;
    }

    default void forceStop() {
        // Do nothing unless implemented
    }
}
