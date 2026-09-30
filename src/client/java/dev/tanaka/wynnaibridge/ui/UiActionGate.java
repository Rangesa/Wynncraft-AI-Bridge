package dev.tanaka.wynnaibridge.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Process-local, category-scoped gate. Armed state is never persisted. */
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
    private Category category;
    private int allowanceRemaining;
    private boolean allowanceCharged;

    public UiActionGate(LongSupplier nanoTime, LongSupplier epochMillis, long minimumIntervalMillis) {
        this.nanoTime = nanoTime;
        this.epochMillis = epochMillis;
        this.minimumIntervalNanos = Math.max(1L, minimumIntervalMillis) * 1_000_000L;
    }

    /** Legacy no-argument local arm is deliberately restricted to one inventory action. */
    public synchronized long arm(Object world, int seconds) {
        return arm(world, seconds, Category.INVENTORY, 1);
    }

    /** Compatibility alias: the old bank arm still authorizes only bounded Bank withdrawals. */
    public synchronized long arm(Object world, int seconds, int bankWithdrawals) {
        return arm(world, seconds, Category.BANK, bankWithdrawals);
    }

    public synchronized long arm(Object world, int seconds, Category category, int count) {
        if (world == null) throw new GateException("NOT_CONNECTED", "A connected Minecraft world is required to arm UI actions");
        if (actionInFlight) throw new GateException("ACTION_IN_PROGRESS", "A UI action is already in progress");
        if (category == null) throw new GateException("INVALID_CATEGORY", "Choose inventory, bank, skills, or ability");
        if (count < 1 || count > category.maximum()) {
            throw new GateException("INVALID_ACTION_ALLOWANCE", "The requested action allowance exceeds the category limit");
        }
        int duration = Math.max(1, Math.min(seconds, 300));
        armedWorld = world;
        this.category = category;
        allowanceRemaining = count;
        expiresAtNanos = nanoTime.getAsLong() + duration * 1_000_000_000L;
        expiresAtEpochMillis = epochMillis.getAsLong() + duration * 1000L;
        nextActionAtNanos = 0L;
        return expiresAtEpochMillis;
    }

    /** Reserve quota before the first click; failed or uncertain actions still spend it. */
    public synchronized int beginAction(
        String actionKey, Object world, boolean connected, Category requiredCategory, int units
    ) {
        return beginAction(actionKey, world, connected, requiredCategory, units, true);
    }

    /** Ability selection validates all live evidence before consuming its one-node allowance. */
    public synchronized int beginActionDeferredCharge(
        String actionKey, Object world, boolean connected, Category requiredCategory, int units
    ) {
        if (requiredCategory != Category.ABILITY) {
            throw new GateException("WRONG_ARM_CATEGORY", "Deferred allowance charging is only available for ability actions");
        }
        return beginAction(actionKey, world, connected, requiredCategory, units, false);
    }

    private synchronized int beginAction(
        String actionKey, Object world, boolean connected, Category requiredCategory, int units, boolean chargeNow
    ) {
        observeWorld(world, connected);
        if (armedWorld == null) throw new GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        if (actionKey == null || actionKey.isBlank()) throw new GateException("MISSING_REQUEST_ID", "A unique MCP request id is required for UI actions");
        if (recentActions.containsKey(actionKey)) throw new GateException("DUPLICATE_ACTION", "This MCP request id has already been used for a UI action");
        if (actionInFlight) throw new GateException("ACTION_IN_PROGRESS", "Another UI action is already in progress");
        if (category != requiredCategory) {
            throw new GateException("WRONG_ARM_CATEGORY", "Locally arm the " + requiredCategory.commandName() + " category before this operation");
        }
        if (units < 1 || allowanceRemaining < units) {
            throw new GateException("ACTION_ALLOWANCE_EXHAUSTED", "The local " + requiredCategory.commandName() + " action allowance is exhausted");
        }
        long now = nanoTime.getAsLong();
        if (now < nextActionAtNanos) throw new GateException("RATE_LIMITED", "Wait before starting another UI action");

        recentActions.put(actionKey, Boolean.TRUE);
        while (recentActions.size() > RECENT_ACTION_LIMIT) recentActions.remove(recentActions.keySet().iterator().next());
        allowanceCharged = chargeNow;
        if (chargeNow) allowanceRemaining -= units;
        actionInFlight = true;
        nextActionAtNanos = now + minimumIntervalNanos;
        return allowanceRemaining;
    }

    /** Spend the reserved ability allowance immediately before sending its single GUI click. */
    public synchronized int chargeAllowanceBeforeClick(Object world, boolean connected, int units) {
        requireActionActive(world, connected);
        if (category != Category.ABILITY) {
            throw new GateException("WRONG_ARM_CATEGORY", "Ability selection requires the ability arm category");
        }
        if (allowanceCharged) return allowanceRemaining;
        if (units < 1 || allowanceRemaining < units) {
            throw new GateException("ACTION_ALLOWANCE_EXHAUSTED", "The local ability action allowance is exhausted");
        }
        allowanceRemaining -= units;
        allowanceCharged = true;
        return allowanceRemaining;
    }

    /** Legacy method used by older internal callers; maps to one inventory operation. */
    public synchronized void beginAction(String actionKey, Object world, boolean connected) {
        beginAction(actionKey, world, connected, Category.INVENTORY, 1);
    }

    /** Legacy compatibility; quota is now reserved in beginAction before any click. */
    public synchronized int consumeBankWithdrawalAllowance() {
        expireIfNeeded();
        if (armedWorld == null) throw new GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        if (!actionInFlight) throw new GateException("ACTION_NOT_ACTIVE", "The UI action is no longer active");
        if (category != Category.BANK) throw new GateException("WRONG_ARM_CATEGORY", "Locally arm the bank category before withdrawing");
        return allowanceRemaining;
    }

    public synchronized void endAction() { actionInFlight = false; }
    public synchronized void disarm() { clearArmedState(); }

    public synchronized void observeWorld(Object world, boolean connected) {
        expireIfNeeded();
        if (armedWorld != null && (!connected || world == null || world != armedWorld)) clearArmedState();
    }

    public synchronized void requireActionActive(Object world, boolean connected) {
        observeWorld(world, connected);
        if (armedWorld == null) throw new GateException("UI_ACTIONS_DISARMED", "UI actions are not locally armed");
        if (!actionInFlight) throw new GateException("ACTION_NOT_ACTIVE", "The UI action is no longer active");
    }

    public synchronized Status status() {
        expireIfNeeded();
        return new Status(armedWorld != null, armedWorld == null ? 0L : expiresAtEpochMillis,
            actionInFlight, armedWorld == null ? null : category, armedWorld == null ? 0 : allowanceRemaining,
            armedWorld != null && category == Category.BANK ? allowanceRemaining : 0);
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
        category = null;
        allowanceRemaining = 0;
        allowanceCharged = false;
    }

    public enum Category {
        INVENTORY("inventory", 10), BANK("bank", 7), SKILLS("skills", 5), ABILITY("ability", 3);
        private final String commandName;
        private final int maximum;
        Category(String commandName, int maximum) { this.commandName = commandName; this.maximum = maximum; }
        public String commandName() { return commandName; }
        public int maximum() { return maximum; }
        public static Category parse(String value) {
            for (Category category : values()) if (category.commandName.equalsIgnoreCase(value)) return category;
            throw new GateException("INVALID_CATEGORY", "Choose inventory, bank, skills, or ability");
        }
    }

    public record Status(boolean armed, long expiresAtEpochMillis, boolean actionInFlight,
                         Category category, int categoryActionsRemaining, int bankWithdrawalsRemaining) {}

    public static final class GateException extends IllegalStateException {
        private final String code;
        public GateException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
