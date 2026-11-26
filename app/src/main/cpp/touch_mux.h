#ifndef ZERO_INTERRUPT_TOUCH_MUX_H
#define ZERO_INTERRUPT_TOUCH_MUX_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <linux/input.h>

namespace touchmux {

constexpr int kMaxUinputSlots = 16;   // Matches Android InputReader MAX_POINTERS = 16
constexpr int kMaxUhidSlots = 10;     // Standard 10-finger HID Touch Screen Digitizer
constexpr int kMaxPhysSlots = 32;

enum class ActionType : int32_t {
    TAP = 0,
    SWIPE = 1,
    HOLD = 2
};

enum class ExecutionMode : int32_t {
    CONCURRENT = 0,
    SEQUENTIAL = 1
};

enum class MuxBackendMode : int32_t {
    NONE = 0,
    UINPUT = 1,
    UHID = 2,
    HYBRID = 3
};

enum class BackendPref : int32_t {
    AUTO = 0,
    UINPUT = 1,
    UHID = 2,
    HYBRID = 3
};

enum class TargetTriggerMode : int32_t {
    OFF = 0,
    TAP = 1,
    LONG_PRESS = 2
};

enum class TargetTriggerEvent : int32_t {
    TAP = 1,
    HOLD_START = 2,
    HOLD_END = 3,
    NATIVE_TAP_STARTED = 4,
    NATIVE_TAP_STOPPED = 5,
    NATIVE_HOLD_STARTED = 6,
    NATIVE_HOLD_STOPPED = 7
};

struct TargetTriggerZone {
    float x = 0.0f;
    float y = 0.0f;
    float radiusPx = 56.0f;
};

struct NativeClickTarget {
    int32_t id = 0;
    float x = 0.0f;
    float y = 0.0f;
    int64_t intervalMs = 80;
    int64_t pressDurationMs = 25;
    bool microJitter = true;
    bool enabled = true;
    int32_t actionType = 0;       // 0=TAP, 1=SWIPE, 2=HOLD
    float endX = 0.0f;
    float endY = 0.0f;
    int64_t swipeDurationMs = 300;
    int32_t repeatCount = 0;       // 0 = infinite, >=1 = finite repeat cycles
    int32_t stageIndex = 0;        // Sequential stage index (for ExecutionMode::SEQUENTIAL)
    int64_t stageDurationMs = 0;   // >0 for timed concurrent sub-preset stage
    int64_t stageGapMs = 50;       // Wait interval after stage completes
    int32_t stageRepeatCount = 1;  // 0 = infinite stay on stage, >=1 = stage cycles
    int32_t holdInId = 0;          // >0 to continue holding slot carried from previous stage
    int32_t holdOutId = 0;         // >0 to hand off held slot cleanly into next stage without release
};

struct NativeRawMacroEvent {
    int64_t timeMs = 0;
    int32_t fingerId = 0;
    int32_t kind = 0;              // 0=DOWN, 1=MOVE, 2=UP
    float normX = 0.0f;
    float normY = 0.0f;
};

struct ProbeResult {
    bool uinputWritable = false;
    int uinputErrno = 0;
    bool uhidWritable = false;
    int uhidErrno = 0;
    std::string touchDevicePath;
    std::string touchDeviceName;
    bool touchDeviceReadable = false;
    int touchDeviceErrno = 0;
    int physSlotCount = 0;
    bool isTypeB = false;
    int32_t absMinX = 0;
    int32_t absMaxX = 0;
    int32_t absMinY = 0;
    int32_t absMaxY = 0;
};

enum class EngineState : int32_t {
    IDLE = 0,
    SELF_CHECK_PENDING = 1,
    RUNNING_GRABBED = 2,
    ERROR_STATE = -1
};

struct EngineCallbacks {
    // Tier 3 Hybrid zero-GC motion frame callback (called ONLY from engineLoop worker thread)
    std::function<void(int64_t downTimeMs,
                       int64_t eventTimeMs,
                       int32_t action,
                       int32_t pointerCount,
                       const int32_t* pointerIds,
                       const float* xs,
                       const float* ys,
                       const float* pressures,
                       const float* sizes)> onHybridMotionFrame;
    std::function<void()> onVolumeKeyDoubleTap;
    std::function<void()> onEmergencyUngrab;
    std::function<void(const int32_t slots[kMaxUinputSlots])> onSlotStatesChanged;
    std::function<void()> onClickingCompleted;
    std::function<void(int32_t eventType)> onTargetTriggerEvent;
};

class TouchMuxEngine {
public:
    TouchMuxEngine();
    ~TouchMuxEngine();

    TouchMuxEngine(const TouchMuxEngine&) = delete;
    TouchMuxEngine& operator=(const TouchMuxEngine&) = delete;

    static ProbeResult probeDevices();
    static int cleanupOrphanProcesses(const std::string& processName = "");

    void setCallbacks(EngineCallbacks callbacks);
    void setVolumeKeyControlEnabled(bool enabled);
    void setTargetTriggerConfig(int32_t mode,
                                const std::vector<TargetTriggerZone>& zones,
                                int displayW,
                                int displayH,
                                int rotation,
                                int excludeLeft,
                                int excludeTop,
                                int excludeRight,
                                int excludeBottom,
                                float touchSlopPx);

    int initAndGrab(int displayW,
                    int displayH,
                    int rotation,
                    int selfCheckTimeoutMs,
                    int preferredBackend = 0);
    bool confirmSelfCheck();
    void shutdown();

    void setDisplayMetrics(int displayW, int displayH, int rotation);
    void setTargetsAndRunning(const std::vector<NativeClickTarget>& targets,
                              bool running,
                              int displayW,
                              int displayH,
                              int rotation,
                              float jitterRadiusPx,
                              int executionMode = 0,
                              int presetLoopCount = 0,
                              bool forceReset = false);
    void setRawMacroAndRunning(const std::vector<NativeRawMacroEvent>& events,
                               int64_t totalDurationMs,
                               int32_t loopCount,
                               bool running,
                               int displayW,
                               int displayH,
                               int rotation);
    void stopClicking();

    // Direct RPC gesture injection (runs multiplexed seamlessly inside engineLoop)
    void enqueueRpcTap(float x, float y, int64_t durationMs);
    void enqueueRpcSwipe(float x1, float y1, float x2, float y2, int64_t durationMs);
    void setRpcHold(int32_t holdId, bool down, float x, float y);

    // Physical touch recording (non-grabbed evdev reader for macro capture)
    bool startRecording(int displayW,
                        int displayH,
                        int rotation,
                        int excludeLeft,
                        int excludeTop,
                        int excludeRight,
                        int excludeBottom);
    void updateRecordingExcludeRect(int excludeLeft, int excludeTop, int excludeRight, int excludeBottom);
    void getRecordingStats(int64_t outStats[4]) const;
    std::string stopRecording(bool cancel);

    EngineState getState() const;
    MuxBackendMode getBackendMode() const;
    bool didSelfCheckRollback() const;
    std::string getLastError() const;
    std::string getTouchDeviceInfo() const;
    void getSlotStates(int32_t outSlots[kMaxUinputSlots]) const;

private:
    enum class SlotOwner : uint8_t {
        FREE = 0,
        PHYSICAL = 1,
        VIRTUAL = 2
    };

    struct MuxSlotState {
        SlotOwner owner = SlotOwner::FREE;
        int32_t trackingId = -1;
        int32_t ownerSlotOrTargetId = -1;
        int32_t rawX = 0;
        int32_t rawY = 0;
        int32_t major = 0;
        int32_t pressure = 0;
        bool hasExactLogical = false;
        float exactLogicalX = 0.0f;
        float exactLogicalY = 0.0f;
    };

    struct VirtualTargetRuntime {
        NativeClickTarget config;
        bool isPressed = false;
        bool swipeFinalReached = false;
        bool holdLockedDown = false;
        int muxSlot = -1;
        int64_t nextDeadlineNs = 0;
        int64_t swipeStartNs = 0;
        int64_t swipeEndNs = 0;
        float startX = 0.0f;
        float startY = 0.0f;
        float endX = 0.0f;
        float endY = 0.0f;
        int32_t sampledMajor = 8;
        int32_t sampledPressure = 64;
        int32_t completedCycles = 0;
        bool finishedRounds = false;
    };

    struct StageRuntime {
        int32_t stageIndex = 0;
        int64_t durationMs = 0;
        int64_t gapMs = 50;
        int32_t repeatCount = 1;
        int32_t completedRepeats = 0;
        int64_t startDelayUntilNs = 0;
        int64_t stageDeadlineNs = 0;
        bool inGap = false;
        int64_t gapDeadlineNs = 0;
        std::vector<VirtualTargetRuntime> targets;
    };

    struct RpcCommand {
        enum class Kind : uint8_t {
            TAP = 0,
            SWIPE = 1,
            HOLD_DOWN = 2,
            HOLD_UP = 3,
            CLEAR_ALL = 4
        };
        Kind kind = Kind::TAP;
        int32_t id = 0;
        float x1 = 0.0f;
        float y1 = 0.0f;
        float x2 = 0.0f;
        float y2 = 0.0f;
        int64_t durationMs = 25;
    };

    struct RpcGestureRuntime {
        int32_t targetId = 0;   // <= -1000
        bool isSwipe = false;
        bool isHold = false;
        bool swipeFinalReached = false;
        int muxSlot = -1;
        int64_t startNs = 0;
        int64_t endNs = 0;
        int64_t nextDeadlineNs = 0;
        float x1 = 0.0f;
        float y1 = 0.0f;
        float x2 = 0.0f;
        float y2 = 0.0f;
        int32_t major = 8;
        int32_t pressure = 64;
    };

    struct DeviceDesc {
        std::string path;
        std::string name;
        uint16_t bustype = 0;
        uint16_t vendor = 0;
        uint16_t product = 0;
        uint16_t version = 0;
        bool isTypeB = false;
        bool hasTouchMajor = false;
        bool hasWidthMajor = false;
        bool hasPressure = false;
        bool hasBtnToolFinger = false;
        int slotCount = 10;
        struct input_absinfo absX {};
        struct input_absinfo absY {};
        struct input_absinfo absSlot {};
        struct input_absinfo absTracking {};
        struct input_absinfo absTouchMajor {};
        struct input_absinfo absWidthMajor {};
        struct input_absinfo absPressure {};
    };

    static bool findBestTouchscreen(DeviceDesc& outDesc, int& outErrno);
    static std::vector<std::string> findVolumeDownKeyDevices();
    static bool hasActivePhysicalTouch(int fd, const DeviceDesc& desc);
    static void waitForPhysicalTouchIdle(int fd, const DeviceDesc& desc, int maxWaitMs);
    bool setupUinputDevice(const DeviceDesc& desc, int& outErrno);
    bool setupUhidDevice(const DeviceDesc& desc, int& outErrno);
    void handleUhidEvents();
    void closeAllFds();
    void signalWake();

    void ensureKeyWatchThread();
    void stopKeyWatchThread();
    void keyWatchLoop();

    void emitTargetTriggerEvent(TargetTriggerEvent ev);
    void syncEngineTargetTriggerConfig();
    void resetEngineTriggerSlots(bool emitHoldEnd);
    void processEngineTriggerSynFrame(int64_t nowNs);
    void buildSequentialStages(const std::vector<NativeClickTarget>& list, int64_t startNs);
    bool triggerInstantStart(int64_t nowNs);
    bool triggerInstantStop(int64_t nowNs);
    void syncTouchTriggerWatcherState();
    void stopTouchTriggerWatchThread();
    void touchTriggerWatchLoop();

    void engineLoop();
    void handleWakeSignal();
    void handlePhysicalEvents();
    void handleTypeAFrameEnd();
    void handleTimerEvents();
    void stepVirtualTargets(int64_t now);
    void stepRawMacro(int64_t now);
    void stepRpcGestures(int64_t now);
    void rearmTimerFd();

    int allocatePhysicalSlot(int physSlot);
    int allocateVirtualSlot(int32_t targetId);
    void releaseSlot(int slot);
    int countActiveSlots() const;
    int32_t nextTrackingId();

    void queueEvent(uint16_t type, uint16_t code, int32_t value);
    void ensureUinputSlot(int slot);
    void emitSlotDown(int slot, int32_t rx, int32_t ry, int32_t major, int32_t pressure,
                      bool hasExactLogical, float lx, float ly);
    void emitSlotMove(int slot, int32_t rx, int32_t ry, int32_t major, int32_t pressure,
                      bool hasExactLogical, float lx, float ly);
    void emitSlotUp(int slot);
    void emitSyncAndFlush();
    void flushUhidFrame();
    void flushHybridFrame();
    void emitHybridAction(int64_t downTimeMs, int64_t eventTimeMs, int32_t action);
    void publishSlotSnapshotIfChanged(int64_t now);

    void releaseAllVirtualTargetSlots();
    void releaseAllRawMacroSlots();
    void releaseCarriedHoldSlots();
    void releaseAllRpcHoldSlots();
    void forceReleaseAllSlotsAndSync();
    void finishClickingRun();

    static void rawToLogicalDesc(const DeviceDesc& desc,
                                 int dispW,
                                 int dispH,
                                 int rotation,
                                 int32_t rx,
                                 int32_t ry,
                                 float& outLx,
                                 float& outLy);
    void logicalToRaw(float lx, float ly, int32_t& outRx, int32_t& outRy) const;
    void rawToLogical(int32_t rx, int32_t ry, float& outLx, float& outLy) const;
    int32_t sampleAbsRatio(const struct input_absinfo& abs, double meanRatio, double spreadHalfRatio, int32_t fallbackVal);
    int32_t sampleTouchMajor();
    int32_t samplePressure();
    int64_t sampleDurationMs(int64_t baseMs, bool microJitter, int64_t minMs, int64_t maxMs);
    void applySpatialJitter(float& x, float& y, bool microJitter);

    static int64_t nowMonotonicNs();

    mutable std::mutex stateMutex_;
    mutable std::mutex slotSnapshotMutex_;
    std::mutex callbackMutex_;
    EngineCallbacks callbacks_;

    std::thread workerThread_;
    std::atomic<bool> threadRunning_{false};
    std::atomic<int32_t> engineState_{static_cast<int32_t>(EngineState::IDLE)};
    std::atomic<int32_t> activeBackend_{static_cast<int32_t>(MuxBackendMode::NONE)};
    std::atomic<bool> selfCheckPending_{false};
    std::atomic<bool> selfCheckRolledBack_{false};
    std::atomic<bool> volumeKeyControlEnabled_{true};

    std::thread keyWatchThread_;
    std::atomic<bool> keyWatchRunning_{false};
    int keyWatchWakeFd_ = -1;

    int physFd_ = -1;
    int uinputFd_ = -1;
    int uhidFd_ = -1;
    int epollFd_ = -1;
    int timerFd_ = -1;
    int wakeFd_ = -1;

    DeviceDesc deviceDesc_{};
    std::string lastError_;

    // Shared config protected by stateMutex_ (copied on wake_fd_)
    int cfgDisplayW_ = 1080;
    int cfgDisplayH_ = 2400;
    int cfgRotation_ = 0;
    float cfgJitterRadiusPx_ = 4.0f;
    bool cfgClickingRunning_ = false;
    ExecutionMode cfgExecutionMode_ = ExecutionMode::CONCURRENT;
    int32_t cfgPresetLoopCount_ = 0;
    uint64_t cfgForceResetSeq_ = 0;
    std::vector<NativeClickTarget> cfgTargets_;
    bool cfgRawMacroRunning_ = false;
    std::vector<NativeRawMacroEvent> cfgRawMacroEvents_;
    int64_t cfgRawMacroTotalDurationMs_ = 0;
    int32_t cfgRawMacroLoopCount_ = 0;
    uint64_t cfgRawMacroSeq_ = 0;
    std::vector<RpcCommand> cfgRpcCommands_;
    int64_t cfgSelfCheckDeadlineNs_ = 0;
    bool cfgCancelSelfCheckRequested_ = false;
    bool cfgStopThreadRequested_ = false;

    // Worker-thread-only runtime state
    MuxBackendMode workerBackend_ = MuxBackendMode::NONE;
    int activeDisplayW_ = 1080;
    int activeDisplayH_ = 2400;
    int activeRotation_ = 0;
    float activeJitterRadiusPx_ = 4.0f;
    bool activeClickingRunning_ = false;
    ExecutionMode activeExecutionMode_ = ExecutionMode::CONCURRENT;
    int32_t activePresetLoopCount_ = 0;
    uint64_t activeForceResetSeq_ = 0;
    int32_t seqCompletedLoops_ = 0;
    size_t activeStageIdx_ = 0;
    bool activeRawMacroRunning_ = false;
    std::vector<NativeRawMacroEvent> activeRawMacroEvents_;
    int64_t activeRawMacroTotalDurationMs_ = 0;
    int32_t activeRawMacroLoopCount_ = 0;
    uint64_t activeRawMacroSeq_ = 0;
    size_t rawMacroCursor_ = 0;
    int32_t rawMacroCompletedLoops_ = 0;
    int64_t rawMacroLoopStartNs_ = 0;
    int64_t rawMacroNextDeadlineNs_ = 0;
    int64_t selfCheckDeadlineNs_ = 0;

    int physCurrentSlot_ = 0;
    int physToMuxSlot_[kMaxPhysSlots]{};
    MuxSlotState muxSlots_[kMaxUinputSlots]{};
    int32_t snapshotSlots_[kMaxUinputSlots]{};
    int32_t lastEmittedSlotSnapshot_[kMaxUinputSlots]{};
    int64_t lastSlotSnapshotNotifyNs_ = 0;

    int uinputCurrentSlot_ = -1;
    bool btnTouchSent_ = false;
    bool uhidDirty_ = false;
    bool uhidHadActiveContacts_ = false;
    bool hybridDirty_ = false;
    int32_t trackingCounter_ = 100;

    // Type-A fallback state (if panel lacks ABS_MT_SLOT)
    struct TypeAContact {
        bool hasX = false;
        bool hasY = false;
        int32_t x = 0;
        int32_t y = 0;
        int32_t major = 0;
        int32_t pressure = 0;
    };
    TypeAContact typeACurrent_{};
    std::vector<TypeAContact> typeAFrameContacts_;
    std::vector<int> typeAActiveMuxSlots_;

    // Tier 3 Hybrid state machine (tracks what Android currently sees DOWN)
    struct HybridPointerTrack {
        int muxSlot = -1;
        int32_t pointerId = 0;
        int32_t trackingId = -1;
        float x = 0.0f;
        float y = 0.0f;
        float pressure = 0.16f;
        float size = 0.16f;
    };
    std::vector<HybridPointerTrack> hybridReported_;
    int64_t hybridDownTimeMs_ = 0;
    int32_t hybridTmpIds_[kMaxUinputSlots]{};
    float hybridTmpXs_[kMaxUinputSlots]{};
    float hybridTmpYs_[kMaxUinputSlots]{};
    float hybridTmpPressures_[kMaxUinputSlots]{};
    float hybridTmpSizes_[kMaxUinputSlots]{};

    struct RawMacroFingerSlot {
        int muxSlot = -1;
        int32_t major = 8;
        int32_t pressure = 64;
    };

    std::vector<VirtualTargetRuntime> virtualTargets_;
    std::vector<StageRuntime> stages_;
    std::unordered_map<int32_t, RawMacroFingerSlot> rawMacroFingerSlots_;
    std::unordered_map<int32_t, int> carriedHoldSlots_;
    std::vector<RpcGestureRuntime> rpcGestures_;
    int32_t rpcSeqCounter_ = 1000;
    std::vector<struct input_event> pendingWrites_;
    std::mt19937 rng_;

    // Physical touch macro recorder state (O_RDONLY without EVIOCGRAB)
    struct RecordedPoint {
        int64_t tMs = 0;
        float x = 0.0f;
        float y = 0.0f;
    };
    struct RecordedStroke {
        int32_t id = 0;
        int64_t downMs = 0;
        int64_t upMs = 0;
        std::vector<RecordedPoint> points;
    };
    void recordLoop();

    mutable std::mutex recMutex_;
    std::thread recThread_;
    std::atomic<bool> recRunning_{false};
    std::atomic<bool> recStarted_{false};
    int recWakeFd_ = -1;
    int recPhysFd_ = -1;
    DeviceDesc recDesc_{};
    int recDisplayW_ = 1080;
    int recDisplayH_ = 2400;
    int recRotation_ = 0;
    int recExL_ = 0;
    int recExT_ = 0;
    int recExR_ = 0;
    int recExB_ = 0;
    int64_t recFirstTouchNs_ = 0;
    int32_t recActiveFingers_ = 0;
    int32_t recStrokeSeq_ = 0;
    std::vector<RecordedStroke> recStrokes_;

    // Target-zone tap/long-press trigger state
    struct TriggerSlotTrack {
        bool active = false;
        bool candidate = false;
        bool movedTooFar = false;
        bool holdFired = false;
        bool suppressed = false;
        float downX = 0.0f;
        float downY = 0.0f;
        float curX = 0.0f;
        float curY = 0.0f;
        int64_t downNs = 0;
    };

    mutable std::mutex targetTriggerMutex_;
    TargetTriggerMode cfgTtMode_ = TargetTriggerMode::OFF;
    std::vector<TargetTriggerZone> cfgTtZones_;
    int cfgTtDisplayW_ = 1080;
    int cfgTtDisplayH_ = 2400;
    int cfgTtRotation_ = 0;
    int cfgTtExL_ = 0;
    int cfgTtExT_ = 0;
    int cfgTtExR_ = 0;
    int cfgTtExB_ = 0;
    float cfgTtSlopPx_ = 48.0f;
    uint64_t cfgTtVersion_ = 0;

    // Worker-thread-only target trigger state (used inside engineLoop)
    TargetTriggerMode activeTtMode_ = TargetTriggerMode::OFF;
    std::vector<TargetTriggerZone> activeTtZones_;
    int activeTtExL_ = 0;
    int activeTtExT_ = 0;
    int activeTtExR_ = 0;
    int activeTtExB_ = 0;
    float activeTtSlopPx_ = 48.0f;
    uint64_t activeTtVersion_ = 0;
    TriggerSlotTrack engTriggerSlots_[kMaxPhysSlots]{};
    bool engPhysPendingDown_[kMaxPhysSlots]{};
    bool engPhysPendingUp_[kMaxPhysSlots]{};
    int32_t engPhysRawX_[kMaxPhysSlots]{};
    int32_t engPhysRawY_[kMaxPhysSlots]{};
    int64_t engLastTapTriggerNs_ = 0;

    // Non-grabbed fallback target trigger watcher thread (used when engineLoop is idle)
    std::mutex ttWatchLifecycleMutex_;
    std::thread ttWatchThread_;
    std::atomic<bool> ttWatchRunning_{false};
    int ttWatchWakeFd_ = -1;
};

}  // namespace touchmux

#endif  // ZERO_INTERRUPT_TOUCH_MUX_H
