#ifndef ZERO_INTERRUPT_TOUCH_MUX_H
#define ZERO_INTERRUPT_TOUCH_MUX_H

#include <atomic>
#include <cstdint>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include <linux/input.h>

namespace touchmux {

constexpr int kMaxUinputSlots = 16;   // Matches Android InputReader MAX_POINTERS = 16
constexpr int kMaxPhysSlots = 32;

struct NativeClickTarget {
    int32_t id = 0;
    float x = 0.0f;
    float y = 0.0f;
    int64_t intervalMs = 80;
    int64_t pressDurationMs = 25;
    bool microJitter = true;
    bool enabled = true;
};

struct ProbeResult {
    bool uinputWritable = false;
    int uinputErrno = 0;
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

class TouchMuxEngine {
public:
    TouchMuxEngine();
    ~TouchMuxEngine();

    TouchMuxEngine(const TouchMuxEngine&) = delete;
    TouchMuxEngine& operator=(const TouchMuxEngine&) = delete;

    static ProbeResult probeDevices();
    static int cleanupOrphanProcesses(const std::string& processName = "");

    int initAndGrab(int displayW, int displayH, int rotation, int selfCheckTimeoutMs);
    bool confirmSelfCheck();
    void shutdown();

    void setDisplayMetrics(int displayW, int displayH, int rotation);
    void setTargetsAndRunning(const std::vector<NativeClickTarget>& targets,
                              bool running,
                              int displayW,
                              int displayH,
                              int rotation,
                              float jitterRadiusPx);
    void stopClicking();

    EngineState getState() const;
    bool didSelfCheckRollback() const;
    std::string getLastError() const;
    std::string getTouchDeviceInfo() const;

private:
    enum class SlotOwner : uint8_t {
        FREE = 0,
        PHYSICAL = 1,
        VIRTUAL = 2
    };

    struct UinputSlotState {
        SlotOwner owner = SlotOwner::FREE;
        int32_t trackingId = -1;
        int32_t ownerSlotOrTargetId = -1;
    };

    struct VirtualTargetRuntime {
        NativeClickTarget config;
        bool isPressed = false;
        int uinputSlot = -1;
        int64_t nextDeadlineNs = 0;
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
    bool setupUinputDevice(const DeviceDesc& desc, int& outErrno);
    void closeAllFds();
    void signalWake();

    void engineLoop();
    void handleWakeSignal();
    void handlePhysicalEvents();
    void handleTypeAFrameEnd();
    void handleTimerEvents();
    void rearmTimerFd();

    int allocatePhysicalUinputSlot(int physSlot);
    int allocateVirtualUinputSlot(int32_t targetId);
    void releaseUinputSlot(int uinputSlot);
    int countActiveUinputSlots() const;
    int32_t nextTrackingId();

    void queueEvent(uint16_t type, uint16_t code, int32_t value);
    void ensureUinputSlot(int uinputSlot);
    void emitSyncAndFlush();
    void releaseAllVirtualSlotsAndSync();
    void forceReleaseAllSlotsAndSync();

    void logicalToRaw(float lx, float ly, int32_t& outRx, int32_t& outRy);

    static int64_t nowMonotonicNs();

    mutable std::mutex stateMutex_;
    std::thread workerThread_;
    std::atomic<bool> threadRunning_{false};
    std::atomic<int32_t> engineState_{static_cast<int32_t>(EngineState::IDLE)};
    std::atomic<bool> selfCheckPending_{false};
    std::atomic<bool> selfCheckRolledBack_{false};

    int physFd_ = -1;
    int uinputFd_ = -1;
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
    std::vector<NativeClickTarget> cfgTargets_;
    int64_t cfgSelfCheckDeadlineNs_ = 0;
    bool cfgCancelSelfCheckRequested_ = false;
    bool cfgStopThreadRequested_ = false;

    // Worker-thread-only runtime state
    int activeDisplayW_ = 1080;
    int activeDisplayH_ = 2400;
    int activeRotation_ = 0;
    float activeJitterRadiusPx_ = 4.0f;
    bool activeClickingRunning_ = false;
    int64_t selfCheckDeadlineNs_ = 0;

    int physCurrentSlot_ = 0;
    int physToUinputSlot_[kMaxPhysSlots]{};
    UinputSlotState uinputSlots_[kMaxUinputSlots]{};
    int uinputCurrentSlot_ = -1;
    bool btnTouchSent_ = false;
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
    std::vector<int> typeAActiveUinputSlots_;

    std::vector<VirtualTargetRuntime> virtualTargets_;
    std::vector<struct input_event> pendingWrites_;
    std::mt19937 rng_;
};

}  // namespace touchmux

#endif  // ZERO_INTERRUPT_TOUCH_MUX_H
