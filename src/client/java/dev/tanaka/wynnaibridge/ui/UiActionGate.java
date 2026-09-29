package dev.tanaka.wynnaibridge.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Process-local gate for limited GUI actions. Its armed state is never loaded
 * from or written to configuration.
 */
public final class UiActionGate {
    public static final UiActionGate INSTANCE = new UiActionGate(System::nanoTime, System::currentTimeMillis, 750L);

    private static final int RECENT_ACTION_LIMIT = 256;

    private final LongSupplier nanoTime;
    private final LongSupplier epochMillis;
    private final long minimumIntervalNanos;
    private final LinkedHashMap<String, Boolean> recentActions = new LinkedHashMap<>();

    private Object armedWorld;
    private long expiresAtNanos;
    private long expiresAtEpochMillis;
    private long nextActionAtNanos;
    private boolean actionInFlight;
    private int bankWithdrawalsRemaining;

    public UiActionGate(LongSupplier nanoTime, LongSupplier epochMillis, long minimumIntervalMillis) {
        this.nanoTime = nanoTime;
        this.epochMillis = epochMillis;
        this.minimumIntervalNanos = Math.max(1L, minimumIntervalMillis) * 1_000_000L;
    }

    public synchronized long arm(Object world, int seconds) {
        return arm(world, seconds, 0);
    }

    public synchronized long arm(Object world, int seconds, int bankWithdrawals) {
        if (world == null) throw new GateException("NOT_CONNECTED", "A connected Minecraft world is required to arm UI actions");
        if (actionInFlight) throw new GateException("ACTION_IN_PROGRESS", "A UI action is already in progress");
        if (bankWithdrawals < 0 || bankWithdrawals > 7) {
            throw new GateException("INVALID_BANK_WITHDRAWAL_ALLOWANCE", "Bank withdrawal allowance must be between 0 and 7");
        }
        int duration = Math.max(1, Math.min(seconds, 300));
        armedWorld = world;
        expiresAtNanos = nanoTime.getAsLong() + duration * 1_000_000_000L;
        expiresAtEpochMillis = epochMillis.getAsLong() + duration * 1000L;
        nextActionAtNanos = 0L;
        bankWithdrawalsRemaining = bankWithdrawals;
        return expiresAtEpochMillis;
    }

    /** Consume one locally authorized, single-item Bank withdrawal before its first click. */
    public synchronized int consumeBankWithdrawalAllowance() {
        expireIfNeeded();
        if (armedWorld == null) {
            throw new GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        }
        if (!actionInFlight) {
            throw new GateException("ACTION_NOT_ACTIVE", "The UI action is no longer active");
        }
        if (bankWithdrawalsRemaining <= 0) {
            throw new GateException("BANK_WITHDRAWAL_NOT_LOCALLY_AUTHORIZED",
                "Bank withdrawal was not included in the local arm; use /wynnbridge actions arm bank <1-7>");
        }
        return --bankWithdrawalsRemaining;
    }

    public synchronized void disarm() {
        clearArmedState();
    }

    /** Called on every client tick and before every MCP action. */
    public synchronized void observeWorld(Object world, boolean connected) {
        expireIfNeeded();
        if (armedWorld != null && (!connected || world == null || world != armedWorld)) {
            clearArmedState();
        }
    }

    public synchronized void beginAction(String actionKey, Object world, boolean connected) {
        observeWorld(world, connected);
        if (armedWorld == null) {
            throw new GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        }
        if (actionKey == null || actionKey.isBlank()) {
            throw new GateException("MISSING_REQUEST_ID", "A unique MCP request id is required for UI actions");
        }
        if (recentActions.containsKey(actionKey)) {
            throw new GateException("DUPLICATE_ACTION", "This MCP request id has already been used for a UI action");
        }
        if (actionInFlight) {
            throw new GateException("ACTION_IN_PROGRESS", "Another UI action is already in progress");
        }
        long now = nanoTime.getAsLong();
        if (now < nextActionAtNanos) {
            throw new GateException("RATE_LIMITED", "Wait before starting another UI action");
        }

        recentActions.put(actionKey, Boolean.TRUE);
        while (recentActions.size() > RECENT_ACTION_LIMIT) {
            String oldest = recentActions.keySet().iterator().next();
            recentActions.remove(oldest);
        }
        actionInFlight = true;
        nextActionAtNanos = now + minimumIntervalNanos;
    }

    public synchronized void endAction() {
        actionInFlight = false;
    }

    /** Re-check the local arm immediately before each GUI interaction. */
    public synchronized void requireActionActive(Object world, boolean connected) {
        observeWorld(world, connected);
        if (armedWorld == null) {
            throw new GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        }
        if (!actionInFlight) {
            throw new GateException("ACTION_NOT_ACTIVE", "The UI action is no longer active");
        }
    }

    public synchronized Status status() {
        expireIfNeeded();
        return new Status(armedWorld != null, armedWorld == null ? 0L : expiresAtEpochMillis,
            actionInFlight, armedWorld == null ? 0 : bankWithdrawalsRemaining);
    }

    private void expireIfNeeded() {
        if (armedWorld != null && nanoTime.getAsLong() >= expiresAtNanos) clearArmedState();
    }

    private void clearArmedState() {
        armedWorld = null;
        expiresAtNanos = 0L;
        expiresAtEpochMillis = 0L;
        nextActionAtNanos = 0L;
        actionInFlight = false;
        bankWithdrawalsRemaining = 0;
    }

    public record Status(boolean armed, long expiresAtEpochMillis, boolean actionInFlight,
                         int bankWithdrawalsRemaining) {}

    public static final class GateException extends IllegalStateException {
        private final String code;

        public GateException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
