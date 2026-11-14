#include "touch_mux.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "TouchMuxNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef INPUT_PROP_DIRECT
#define INPUT_PROP_DIRECT 0x01
#endif

#ifndef UI_SET_PROPBIT
#define UI_SET_PROPBIT _IOW(UINPUT_IOCTL_BASE, 110, int)
#endif

#ifndef SYN_DROPPED
#define SYN_DROPPED 3
#endif

#ifndef SYN_MT_REPORT
#define SYN_MT_REPORT 2
#endif

namespace touchmux {

namespace {

constexpr size_t kBitFieldBytes(size_t maxBit) {
    return (maxBit + 7) / 8 + 1;
}

template <size_t N>
bool testBit(const std::array<uint8_t, N>& bits, size_t bit) {
    const size_t byteIdx = bit / 8;
    if (byteIdx >= bits.size()) {
        return false;
    }
    return (bits[byteIdx] & (1u << (bit % 8))) != 0;
}

std::string formatErrno(const char* prefix, int err) {
    return std::string(prefix) + ": " + std::strerror(err) + " (errno=" + std::to_string(err) + ")";
}

}  // namespace

TouchMuxEngine::TouchMuxEngine()
    : rng_(static_cast<uint32_t>(nowMonotonicNs() & 0xFFFFFFFFu)) {
    std::fill(std::begin(physToUinputSlot_), std::end(physToUinputSlot_), -1);
}

TouchMuxEngine::~TouchMuxEngine() {
    shutdown();
}

int64_t TouchMuxEngine::nowMonotonicNs() {
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}

bool TouchMuxEngine::findBestTouchscreen(DeviceDesc& outDesc, int& outErrno) {
    outErrno = ENODEV;
    DIR* dir = opendir("/dev/input");
    if (!dir) {
        outErrno = errno;
        return false;
    }

    int bestScore = -1;
    DeviceDesc best{};

    struct dirent* ent = nullptr;
    while ((ent = readdir(dir)) != nullptr) {
        if (std::strncmp(ent->d_name, "event", 5) != 0) {
            continue;
        }
        std::string path = std::string("/dev/input/") + ent->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            if (errno == EACCES || errno == EPERM) {
                outErrno = errno;
            }
            continue;
        }

        char nameBuf[256] = {};
        if (ioctl(fd, EVIOCGNAME(sizeof(nameBuf) - 1), nameBuf) < 0) {
            std::strncpy(nameBuf, "unknown", sizeof(nameBuf) - 1);
        }

        if (std::strstr(nameBuf, "ZeroInterrupt_TouchMux") != nullptr) {
            close(fd);
            continue;
        }

        std::array<uint8_t, kBitFieldBytes(EV_MAX)> evBits{};
        std::array<uint8_t, kBitFieldBytes(ABS_MAX)> absBits{};
        std::array<uint8_t, kBitFieldBytes(KEY_MAX)> keyBits{};
        std::array<uint8_t, kBitFieldBytes(INPUT_PROP_MAX)> propBits{};

        if (ioctl(fd, EVIOCGBIT(0, evBits.size()), evBits.data()) < 0) {
            close(fd);
            continue;
        }
        if (!testBit(evBits, EV_ABS)) {
            close(fd);
            continue;
        }

        ioctl(fd, EVIOCGBIT(EV_ABS, absBits.size()), absBits.data());
        if (!testBit(absBits, ABS_MT_POSITION_X) || !testBit(absBits, ABS_MT_POSITION_Y)) {
            close(fd);
            continue;
        }

        if (testBit(evBits, EV_KEY)) {
            ioctl(fd, EVIOCGBIT(EV_KEY, keyBits.size()), keyBits.data());
        }
        ioctl(fd, EVIOCGPROP(propBits.size()), propBits.data());

        int score = 10;
        if (testBit(propBits, INPUT_PROP_DIRECT)) score += 100;
        if (testBit(keyBits, BTN_TOUCH)) score += 50;
        if (testBit(absBits, ABS_MT_SLOT)) score += 30;
        if (testBit(absBits, ABS_MT_TRACKING_ID)) score += 20;
        if (testBit(absBits, ABS_MT_TOUCH_MAJOR)) score += 5;
        if (testBit(absBits, ABS_MT_PRESSURE)) score += 5;

        if (score > bestScore) {
            DeviceDesc candidate;
            candidate.path = path;
            candidate.name = nameBuf;
            struct input_id idInfo {};
            if (ioctl(fd, EVIOCGID, &idInfo) == 0) {
                candidate.bustype = idInfo.bustype;
                candidate.vendor = idInfo.vendor;
                candidate.product = idInfo.product;
                candidate.version = idInfo.version;
            }
            ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &candidate.absX);
            ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &candidate.absY);

            candidate.isTypeB = testBit(absBits, ABS_MT_SLOT);
            if (candidate.isTypeB) {
                ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &candidate.absSlot);
                int maxSlot = candidate.absSlot.maximum;
                candidate.slotCount = std::clamp(maxSlot + 1, 1, kMaxPhysSlots);
            } else {
                candidate.slotCount = 10;
            }
            if (testBit(absBits, ABS_MT_TRACKING_ID)) {
                ioctl(fd, EVIOCGABS(ABS_MT_TRACKING_ID), &candidate.absTracking);
            }
            candidate.hasTouchMajor = testBit(absBits, ABS_MT_TOUCH_MAJOR);
            if (candidate.hasTouchMajor) {
                ioctl(fd, EVIOCGABS(ABS_MT_TOUCH_MAJOR), &candidate.absTouchMajor);
            }
            candidate.hasWidthMajor = testBit(absBits, ABS_MT_WIDTH_MAJOR);
            if (candidate.hasWidthMajor) {
                ioctl(fd, EVIOCGABS(ABS_MT_WIDTH_MAJOR), &candidate.absWidthMajor);
            }
            candidate.hasPressure = testBit(absBits, ABS_MT_PRESSURE);
            if (candidate.hasPressure) {
                ioctl(fd, EVIOCGABS(ABS_MT_PRESSURE), &candidate.absPressure);
            }
            candidate.hasBtnToolFinger = testBit(keyBits, BTN_TOOL_FINGER);

            if (candidate.absX.maximum > candidate.absX.minimum &&
                candidate.absY.maximum > candidate.absY.minimum) {
                bestScore = score;
                best = std::move(candidate);
            }
        }
        close(fd);
    }
    closedir(dir);

    if (bestScore < 0) {
        return false;
    }
    outDesc = std::move(best);
    outErrno = 0;
    return true;
}

ProbeResult TouchMuxEngine::probeDevices() {
    ProbeResult result;
    int ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ufd >= 0) {
        result.uinputWritable = true;
        result.uinputErrno = 0;
        close(ufd);
    } else {
        result.uinputWritable = false;
        result.uinputErrno = errno;
    }

    DeviceDesc desc;
    int touchErr = 0;
    if (findBestTouchscreen(desc, touchErr)) {
        result.touchDevicePath = desc.path;
        result.touchDeviceName = desc.name;
        result.touchDeviceReadable = true;
        result.touchDeviceErrno = 0;
        result.physSlotCount = desc.slotCount;
        result.isTypeB = desc.isTypeB;
        result.absMinX = desc.absX.minimum;
        result.absMaxX = desc.absX.maximum;
        result.absMinY = desc.absY.minimum;
        result.absMaxY = desc.absY.maximum;
    } else {
        result.touchDeviceReadable = false;
        result.touchDeviceErrno = touchErr;
    }
    return result;
}

bool TouchMuxEngine::setupUinputDevice(const DeviceDesc& desc, int& outErrno) {
    uinputFd_ = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (uinputFd_ < 0) {
        outErrno = errno;
        lastError_ = formatErrno("open(/dev/uinput) failed", outErrno);
        return false;
    }

    auto setBitOrFail = [this, &outErrno](unsigned long req, int val, const char* label) -> bool {
        if (ioctl(uinputFd_, req, val) < 0) {
            outErrno = errno;
            lastError_ = formatErrno(label, outErrno);
            return false;
        }
        return true;
    };

    if (!setBitOrFail(UI_SET_EVBIT, EV_SYN, "UI_SET_EVBIT(EV_SYN)")) return false;
    if (!setBitOrFail(UI_SET_EVBIT, EV_KEY, "UI_SET_EVBIT(EV_KEY)")) return false;
    if (!setBitOrFail(UI_SET_EVBIT, EV_ABS, "UI_SET_EVBIT(EV_ABS)")) return false;

    if (!setBitOrFail(UI_SET_KEYBIT, BTN_TOUCH, "UI_SET_KEYBIT(BTN_TOUCH)")) return false;
    if (desc.hasBtnToolFinger) {
        ioctl(uinputFd_, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    }

    if (ioctl(uinputFd_, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
        LOGW("UI_SET_PROPBIT(INPUT_PROP_DIRECT) warning: %s", std::strerror(errno));
    }

    if (!setBitOrFail(UI_SET_ABSBIT, ABS_MT_SLOT, "UI_SET_ABSBIT(ABS_MT_SLOT)")) return false;
    if (!setBitOrFail(UI_SET_ABSBIT, ABS_MT_TRACKING_ID, "UI_SET_ABSBIT(ABS_MT_TRACKING_ID)")) return false;
    if (!setBitOrFail(UI_SET_ABSBIT, ABS_MT_POSITION_X, "UI_SET_ABSBIT(ABS_MT_POSITION_X)")) return false;
    if (!setBitOrFail(UI_SET_ABSBIT, ABS_MT_POSITION_Y, "UI_SET_ABSBIT(ABS_MT_POSITION_Y)")) return false;

    if (desc.hasTouchMajor) ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    if (desc.hasWidthMajor) ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_WIDTH_MAJOR);
    if (desc.hasPressure) ioctl(uinputFd_, UI_SET_ABSBIT, ABS_MT_PRESSURE);

    bool configuredModern = false;
#if defined(UI_DEV_SETUP) && defined(UI_ABS_SETUP)
    {
        auto setupAbs = [this](uint16_t code, const struct input_absinfo& info) -> bool {
            struct uinput_abs_setup absSetup {};
            absSetup.code = code;
            absSetup.absinfo = info;
            return ioctl(uinputFd_, UI_ABS_SETUP, &absSetup) == 0;
        };

        struct input_absinfo slotInfo {};
        slotInfo.minimum = 0;
        slotInfo.maximum = kMaxUinputSlots - 1;

        struct input_absinfo trackInfo {};
        trackInfo.minimum = 0;
        trackInfo.maximum = 65535;

        bool absOk = setupAbs(ABS_MT_SLOT, slotInfo) &&
                     setupAbs(ABS_MT_TRACKING_ID, trackInfo) &&
                     setupAbs(ABS_MT_POSITION_X, desc.absX) &&
                     setupAbs(ABS_MT_POSITION_Y, desc.absY);

        if (absOk && desc.hasTouchMajor) setupAbs(ABS_MT_TOUCH_MAJOR, desc.absTouchMajor);
        if (absOk && desc.hasWidthMajor) setupAbs(ABS_MT_WIDTH_MAJOR, desc.absWidthMajor);
        if (absOk && desc.hasPressure) setupAbs(ABS_MT_PRESSURE, desc.absPressure);

        if (absOk) {
            struct uinput_setup devSetup {};
            std::snprintf(devSetup.name, UINPUT_MAX_NAME_SIZE, "ZeroInterrupt_TouchMux");
            devSetup.id.bustype = BUS_VIRTUAL;
            devSetup.id.vendor = desc.vendor != 0 ? desc.vendor : 0x18D1;
            devSetup.id.product = desc.product != 0 ? desc.product : 0x4E11;
            devSetup.id.version = desc.version != 0 ? desc.version : 1;
            if (ioctl(uinputFd_, UI_DEV_SETUP, &devSetup) == 0) {
                configuredModern = true;
            }
        }
    }
#endif

    if (!configuredModern) {
        struct uinput_user_dev uidev {};
        std::snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "ZeroInterrupt_TouchMux");
        uidev.id.bustype = BUS_VIRTUAL;
        uidev.id.vendor = desc.vendor != 0 ? desc.vendor : 0x18D1;
        uidev.id.product = desc.product != 0 ? desc.product : 0x4E11;
        uidev.id.version = desc.version != 0 ? desc.version : 1;

        uidev.absmin[ABS_MT_SLOT] = 0;
        uidev.absmax[ABS_MT_SLOT] = kMaxUinputSlots - 1;
        uidev.absmin[ABS_MT_TRACKING_ID] = 0;
        uidev.absmax[ABS_MT_TRACKING_ID] = 65535;

        uidev.absmin[ABS_MT_POSITION_X] = desc.absX.minimum;
        uidev.absmax[ABS_MT_POSITION_X] = desc.absX.maximum;
        uidev.absfuzz[ABS_MT_POSITION_X] = desc.absX.fuzz;
        uidev.absflat[ABS_MT_POSITION_X] = desc.absX.flat;

        uidev.absmin[ABS_MT_POSITION_Y] = desc.absY.minimum;
        uidev.absmax[ABS_MT_POSITION_Y] = desc.absY.maximum;
        uidev.absfuzz[ABS_MT_POSITION_Y] = desc.absY.fuzz;
        uidev.absflat[ABS_MT_POSITION_Y] = desc.absY.flat;

        if (desc.hasTouchMajor) {
            uidev.absmin[ABS_MT_TOUCH_MAJOR] = desc.absTouchMajor.minimum;
            uidev.absmax[ABS_MT_TOUCH_MAJOR] = desc.absTouchMajor.maximum;
            uidev.absfuzz[ABS_MT_TOUCH_MAJOR] = desc.absTouchMajor.fuzz;
            uidev.absflat[ABS_MT_TOUCH_MAJOR] = desc.absTouchMajor.flat;
        }
        if (desc.hasWidthMajor) {
            uidev.absmin[ABS_MT_WIDTH_MAJOR] = desc.absWidthMajor.minimum;
            uidev.absmax[ABS_MT_WIDTH_MAJOR] = desc.absWidthMajor.maximum;
        }
        if (desc.hasPressure) {
            uidev.absmin[ABS_MT_PRESSURE] = desc.absPressure.minimum;
            uidev.absmax[ABS_MT_PRESSURE] = desc.absPressure.maximum;
            uidev.absfuzz[ABS_MT_PRESSURE] = desc.absPressure.fuzz;
            uidev.absflat[ABS_MT_PRESSURE] = desc.absPressure.flat;
        }

        if (write(uinputFd_, &uidev, sizeof(uidev)) != static_cast<ssize_t>(sizeof(uidev))) {
            outErrno = errno;
            lastError_ = formatErrno("write(uinput_user_dev) failed", outErrno);
            return false;
        }
    }

    if (ioctl(uinputFd_, UI_DEV_CREATE) < 0) {
        outErrno = errno;
        lastError_ = formatErrno("ioctl(UI_DEV_CREATE) failed", outErrno);
        return false;
    }

    outErrno = 0;
    return true;
}

void TouchMuxEngine::closeAllFds() {
    if (epollFd_ >= 0) {
        close(epollFd_);
        epollFd_ = -1;
    }
    if (timerFd_ >= 0) {
        close(timerFd_);
        timerFd_ = -1;
    }
    if (wakeFd_ >= 0) {
        close(wakeFd_);
        wakeFd_ = -1;
    }
    if (physFd_ >= 0) {
        close(physFd_);
        physFd_ = -1;
    }
    if (uinputFd_ >= 0) {
        close(uinputFd_);
        uinputFd_ = -1;
    }
}

void TouchMuxEngine::signalWake() {
    if (wakeFd_ >= 0) {
        uint64_t val = 1;
        ssize_t ignored = write(wakeFd_, &val, sizeof(val));
        (void)ignored;
    }
}

int TouchMuxEngine::initAndGrab(int displayW, int displayH, int rotation, int selfCheckTimeoutMs) {
    if (threadRunning_.load()) {
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            cfgDisplayW_ = std::max(1, displayW);
            cfgDisplayH_ = std::max(1, displayH);
            cfgRotation_ = rotation;
            if (selfCheckTimeoutMs > 0) {
                cfgSelfCheckDeadlineNs_ = nowMonotonicNs() + static_cast<int64_t>(selfCheckTimeoutMs) * 1000000LL;
                selfCheckPending_.store(true);
                selfCheckRolledBack_.store(false);
                engineState_.store(static_cast<int32_t>(EngineState::SELF_CHECK_PENDING));
            }
        }
        signalWake();
        return 0;
    }

    if (workerThread_.joinable()) {
        workerThread_.join();
    }

    DeviceDesc desc;
    int findErr = 0;
    if (!findBestTouchscreen(desc, findErr)) {
        lastError_ = formatErrno("findBestTouchscreen failed", findErr);
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        return -findErr;
    }

    physFd_ = open(desc.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (physFd_ < 0) {
        int err = errno;
        lastError_ = formatErrno("open physical touchscreen failed", err);
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        return -err;
    }

    int uinputErr = 0;
    if (!setupUinputDevice(desc, uinputErr)) {
        closeAllFds();
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        return -uinputErr;
    }

    if (ioctl(physFd_, EVIOCGRAB, 1) < 0) {
        int err = errno;
        lastError_ = formatErrno("ioctl(EVIOCGRAB, 1) failed", err);
        ioctl(uinputFd_, UI_DEV_DESTROY);
        closeAllFds();
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        return -err;
    }

    epollFd_ = epoll_create1(EPOLL_CLOEXEC);
    timerFd_ = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    wakeFd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (epollFd_ < 0 || timerFd_ < 0 || wakeFd_ < 0) {
        int err = errno;
        lastError_ = formatErrno("epoll/timerfd/eventfd creation failed", err);
        ioctl(physFd_, EVIOCGRAB, 0);
        ioctl(uinputFd_, UI_DEV_DESTROY);
        closeAllFds();
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        return -err;
    }

    auto addEpoll = [this](int fd) -> bool {
        struct epoll_event ev {};
        ev.events = EPOLLIN;
        ev.data.fd = fd;
        return epoll_ctl(epollFd_, EPOLL_CTL_ADD, fd, &ev) == 0;
    };

    if (!addEpoll(physFd_) || !addEpoll(timerFd_) || !addEpoll(wakeFd_)) {
        int err = errno;
        lastError_ = formatErrno("epoll_ctl ADD failed", err);
        ioctl(physFd_, EVIOCGRAB, 0);
        ioctl(uinputFd_, UI_DEV_DESTROY);
        closeAllFds();
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        return -err;
    }

    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        deviceDesc_ = desc;
        lastError_.clear();
        cfgDisplayW_ = std::max(1, displayW);
        cfgDisplayH_ = std::max(1, displayH);
        cfgRotation_ = rotation;
        cfgClickingRunning_ = false;
        cfgTargets_.clear();
        cfgCancelSelfCheckRequested_ = false;
        cfgStopThreadRequested_ = false;
        if (selfCheckTimeoutMs > 0) {
            cfgSelfCheckDeadlineNs_ = nowMonotonicNs() + static_cast<int64_t>(selfCheckTimeoutMs) * 1000000LL;
            selfCheckPending_.store(true);
            selfCheckRolledBack_.store(false);
            engineState_.store(static_cast<int32_t>(EngineState::SELF_CHECK_PENDING));
        } else {
            cfgSelfCheckDeadlineNs_ = 0;
            selfCheckPending_.store(false);
            selfCheckRolledBack_.store(false);
            engineState_.store(static_cast<int32_t>(EngineState::RUNNING_GRABBED));
        }
    }

    physCurrentSlot_ = 0;
    std::fill(std::begin(physToUinputSlot_), std::end(physToUinputSlot_), -1);
    for (auto& s : uinputSlots_) {
        s = UinputSlotState{};
    }
    uinputCurrentSlot_ = -1;
    btnTouchSent_ = false;
    typeACurrent_ = TypeAContact{};
    typeAFrameContacts_.clear();
    typeAActiveUinputSlots_.clear();
    virtualTargets_.clear();
    pendingWrites_.clear();

    threadRunning_.store(true);
    workerThread_ = std::thread(&TouchMuxEngine::engineLoop, this);
    signalWake();
    LOGI("TouchMux initialized & grabbed %s (%s), selfCheck=%dms",
         desc.path.c_str(), desc.name.c_str(), selfCheckTimeoutMs);
    return 0;
}

bool TouchMuxEngine::confirmSelfCheck() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!threadRunning_.load() || selfCheckRolledBack_.load()) {
        return false;
    }
    if (engineState_.load() == static_cast<int32_t>(EngineState::ERROR_STATE)) {
        return false;
    }
    cfgSelfCheckDeadlineNs_ = 0;
    cfgCancelSelfCheckRequested_ = true;
    selfCheckPending_.store(false);
    engineState_.store(static_cast<int32_t>(EngineState::RUNNING_GRABBED));
    signalWake();
    LOGI("TouchMux 5s self-check confirmed by user.");
    return true;
}

void TouchMuxEngine::shutdown() {
    if (threadRunning_.load()) {
        {
            std::lock_guard<std::mutex> lock(stateMutex_);
            cfgStopThreadRequested_ = true;
            cfgClickingRunning_ = false;
        }
        signalWake();
    }
    if (workerThread_.joinable()) {
        workerThread_.join();
    }
    selfCheckPending_.store(false);
    engineState_.store(static_cast<int32_t>(EngineState::IDLE));
}

void TouchMuxEngine::setDisplayMetrics(int displayW, int displayH, int rotation) {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgDisplayW_ = std::max(1, displayW);
        cfgDisplayH_ = std::max(1, displayH);
        cfgRotation_ = rotation;
    }
    signalWake();
}

void TouchMuxEngine::setTargetsAndRunning(const std::vector<NativeClickTarget>& targets,
                                          bool running,
                                          int displayW,
                                          int displayH,
                                          int rotation,
                                          float jitterRadiusPx) {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgTargets_ = targets;
        cfgClickingRunning_ = running;
        cfgDisplayW_ = std::max(1, displayW);
        cfgDisplayH_ = std::max(1, displayH);
        cfgRotation_ = rotation;
        cfgJitterRadiusPx_ = std::clamp(jitterRadiusPx, 0.0f, 20.0f);
    }
    signalWake();
}

void TouchMuxEngine::stopClicking() {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgClickingRunning_ = false;
    }
    signalWake();
}

EngineState TouchMuxEngine::getState() const {
    return static_cast<EngineState>(engineState_.load());
}

bool TouchMuxEngine::didSelfCheckRollback() const {
    return selfCheckRolledBack_.load();
}

std::string TouchMuxEngine::getLastError() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    return lastError_;
}

std::string TouchMuxEngine::getTouchDeviceInfo() const {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (deviceDesc_.path.empty()) return "";
    return deviceDesc_.path + " (" + deviceDesc_.name + ")";
}

int32_t TouchMuxEngine::nextTrackingId() {
    trackingCounter_++;
    if (trackingCounter_ > 65000) {
        trackingCounter_ = 100;
    }
    return trackingCounter_;
}

int TouchMuxEngine::countActiveUinputSlots() const {
    int cnt = 0;
    for (const auto& s : uinputSlots_) {
        if (s.owner != SlotOwner::FREE) cnt++;
    }
    return cnt;
}

int TouchMuxEngine::allocatePhysicalUinputSlot(int physSlot) {
    if (physSlot >= 0 && physSlot < kMaxUinputSlots && uinputSlots_[physSlot].owner == SlotOwner::FREE) {
        uinputSlots_[physSlot].owner = SlotOwner::PHYSICAL;
        uinputSlots_[physSlot].trackingId = nextTrackingId();
        uinputSlots_[physSlot].ownerSlotOrTargetId = physSlot;
        return physSlot;
    }
    for (int i = 0; i < kMaxUinputSlots; ++i) {
        if (uinputSlots_[i].owner == SlotOwner::FREE) {
            uinputSlots_[i].owner = SlotOwner::PHYSICAL;
            uinputSlots_[i].trackingId = nextTrackingId();
            uinputSlots_[i].ownerSlotOrTargetId = physSlot;
            return i;
        }
    }
    return -1;
}

int TouchMuxEngine::allocateVirtualUinputSlot(int32_t targetId) {
    // Allocate from high slots downwards (15..0) to avoid physical slot indices (0..9)
    for (int i = kMaxUinputSlots - 1; i >= 0; --i) {
        if (uinputSlots_[i].owner == SlotOwner::FREE) {
            uinputSlots_[i].owner = SlotOwner::VIRTUAL;
            uinputSlots_[i].trackingId = nextTrackingId();
            uinputSlots_[i].ownerSlotOrTargetId = targetId;
            return i;
        }
    }
    return -1;
}

void TouchMuxEngine::releaseUinputSlot(int uinputSlot) {
    if (uinputSlot >= 0 && uinputSlot < kMaxUinputSlots) {
        uinputSlots_[uinputSlot] = UinputSlotState{};
    }
}

void TouchMuxEngine::queueEvent(uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev {};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    pendingWrites_.push_back(ev);
}

void TouchMuxEngine::ensureUinputSlot(int uinputSlot) {
    if (uinputCurrentSlot_ != uinputSlot) {
        queueEvent(EV_ABS, ABS_MT_SLOT, uinputSlot);
        uinputCurrentSlot_ = uinputSlot;
    }
}

void TouchMuxEngine::emitSyncAndFlush() {
    if (pendingWrites_.empty()) {
        return;
    }
    const bool anyActive = countActiveUinputSlots() > 0;
    if (anyActive != btnTouchSent_) {
        queueEvent(EV_KEY, BTN_TOUCH, anyActive ? 1 : 0);
        if (deviceDesc_.hasBtnToolFinger) {
            queueEvent(EV_KEY, BTN_TOOL_FINGER, anyActive ? 1 : 0);
        }
        btnTouchSent_ = anyActive;
    }
    queueEvent(EV_SYN, SYN_REPORT, 0);

    const uint8_t* ptr = reinterpret_cast<const uint8_t*>(pendingWrites_.data());
    size_t remaining = pendingWrites_.size() * sizeof(struct input_event);
    while (remaining > 0) {
        ssize_t written = write(uinputFd_, ptr, remaining);
        if (written > 0) {
            ptr += written;
            remaining -= static_cast<size_t>(written);
        } else if (errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
    pendingWrites_.clear();
}

void TouchMuxEngine::releaseAllVirtualSlotsAndSync() {
    bool changed = false;
    for (auto& vt : virtualTargets_) {
        if (vt.isPressed && vt.uinputSlot >= 0) {
            ensureUinputSlot(vt.uinputSlot);
            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            releaseUinputSlot(vt.uinputSlot);
            vt.uinputSlot = -1;
            vt.isPressed = false;
            changed = true;
        }
    }
    for (int i = 0; i < kMaxUinputSlots; ++i) {
        if (uinputSlots_[i].owner == SlotOwner::VIRTUAL) {
            ensureUinputSlot(i);
            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            releaseUinputSlot(i);
            changed = true;
        }
    }
    if (changed) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::forceReleaseAllSlotsAndSync() {
    bool changed = false;
    for (int i = 0; i < kMaxUinputSlots; ++i) {
        if (uinputSlots_[i].owner != SlotOwner::FREE) {
            ensureUinputSlot(i);
            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            releaseUinputSlot(i);
            changed = true;
        }
    }
    std::fill(std::begin(physToUinputSlot_), std::end(physToUinputSlot_), -1);
    typeAActiveUinputSlots_.clear();
    for (auto& vt : virtualTargets_) {
        vt.isPressed = false;
        vt.uinputSlot = -1;
    }
    if (changed || btnTouchSent_) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::logicalToRaw(float lx, float ly, int32_t& outRx, int32_t& outRy) {
    const float maxDispX = static_cast<float>(std::max(1, activeDisplayW_ - 1));
    const float maxDispY = static_cast<float>(std::max(1, activeDisplayH_ - 1));
    const float u = std::clamp(lx / maxDispX, 0.0f, 1.0f);
    const float v = std::clamp(ly / maxDispY, 0.0f, 1.0f);

    float uRaw = u;
    float vRaw = v;
    switch (activeRotation_) {
        case 1:  // Surface.ROTATION_90
            uRaw = 1.0f - v;
            vRaw = u;
            break;
        case 2:  // Surface.ROTATION_180
            uRaw = 1.0f - u;
            vRaw = 1.0f - v;
            break;
        case 3:  // Surface.ROTATION_270
            uRaw = v;
            vRaw = 1.0f - u;
            break;
        default: // Surface.ROTATION_0
            uRaw = u;
            vRaw = v;
            break;
    }

    const int64_t spanX = static_cast<int64_t>(deviceDesc_.absX.maximum) - deviceDesc_.absX.minimum;
    const int64_t spanY = static_cast<int64_t>(deviceDesc_.absY.maximum) - deviceDesc_.absY.minimum;
    int32_t rx = deviceDesc_.absX.minimum + static_cast<int32_t>(std::llround(uRaw * static_cast<double>(spanX)));
    int32_t ry = deviceDesc_.absY.minimum + static_cast<int32_t>(std::llround(vRaw * static_cast<double>(spanY)));

    outRx = std::clamp(rx, deviceDesc_.absX.minimum, deviceDesc_.absX.maximum);
    outRy = std::clamp(ry, deviceDesc_.absY.minimum, deviceDesc_.absY.maximum);
}

void TouchMuxEngine::handleWakeSignal() {
    uint64_t drained = 0;
    while (read(wakeFd_, &drained, sizeof(drained)) > 0) {
    }

    std::vector<NativeClickTarget> nextTargets;
    bool nextRunning = false;
    bool stopThread = false;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        activeDisplayW_ = cfgDisplayW_;
        activeDisplayH_ = cfgDisplayH_;
        activeRotation_ = cfgRotation_;
        activeJitterRadiusPx_ = cfgJitterRadiusPx_;
        nextRunning = cfgClickingRunning_;
        nextTargets = cfgTargets_;
        stopThread = cfgStopThreadRequested_;
        if (cfgCancelSelfCheckRequested_) {
            selfCheckDeadlineNs_ = 0;
            cfgCancelSelfCheckRequested_ = false;
        } else if (selfCheckPending_.load()) {
            selfCheckDeadlineNs_ = cfgSelfCheckDeadlineNs_;
        }
    }

    if (stopThread) {
        threadRunning_.store(false);
        return;
    }

    if (!nextRunning) {
        activeClickingRunning_ = false;
        releaseAllVirtualSlotsAndSync();
        virtualTargets_.clear();
        for (const auto& t : nextTargets) {
            if (!t.enabled) continue;
            VirtualTargetRuntime rt;
            rt.config = t;
            virtualTargets_.push_back(rt);
        }
        rearmTimerFd();
        return;
    }

    const int64_t now = nowMonotonicNs();
    std::vector<VirtualTargetRuntime> rebuilt;
    rebuilt.reserve(nextTargets.size());

    // Keep state for existing targets; release virtual slots of removed/disabled targets
    for (auto& oldRt : virtualTargets_) {
        bool stillEnabled = false;
        for (const auto& nt : nextTargets) {
            if (nt.id == oldRt.config.id && nt.enabled) {
                stillEnabled = true;
                break;
            }
        }
        if (!stillEnabled && oldRt.isPressed && oldRt.uinputSlot >= 0) {
            ensureUinputSlot(oldRt.uinputSlot);
            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            releaseUinputSlot(oldRt.uinputSlot);
            oldRt.uinputSlot = -1;
            oldRt.isPressed = false;
        }
    }
    emitSyncAndFlush();

    for (size_t i = 0; i < nextTargets.size(); ++i) {
        const auto& nt = nextTargets[i];
        if (!nt.enabled) continue;
        bool matched = false;
        for (const auto& oldRt : virtualTargets_) {
            if (oldRt.config.id == nt.id && activeClickingRunning_) {
                VirtualTargetRuntime updated = oldRt;
                updated.config = nt;
                rebuilt.push_back(updated);
                matched = true;
                break;
            }
        }
        if (!matched) {
            VirtualTargetRuntime fresh;
            fresh.config = nt;
            fresh.isPressed = false;
            fresh.uinputSlot = -1;
            fresh.nextDeadlineNs = now + static_cast<int64_t>(i) * 500000LL;
            rebuilt.push_back(fresh);
        }
    }

    virtualTargets_ = std::move(rebuilt);
    activeClickingRunning_ = true;

    // Immediately fire targets due now
    handleTimerEvents();
}

void TouchMuxEngine::handleTypeAFrameEnd() {
    // Map Type-A contacts to Type-B uinput slots cleanly
    const size_t newCount = std::min(typeAFrameContacts_.size(), static_cast<size_t>(kMaxUinputSlots));
    while (typeAActiveUinputSlots_.size() > newCount) {
        int slot = typeAActiveUinputSlots_.back();
        typeAActiveUinputSlots_.pop_back();
        ensureUinputSlot(slot);
        queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
        releaseUinputSlot(slot);
    }
    while (typeAActiveUinputSlots_.size() < newCount) {
        int slot = allocatePhysicalUinputSlot(static_cast<int>(typeAActiveUinputSlots_.size()));
        if (slot < 0) break;
        typeAActiveUinputSlots_.push_back(slot);
        ensureUinputSlot(slot);
        queueEvent(EV_ABS, ABS_MT_TRACKING_ID, uinputSlots_[slot].trackingId);
    }
    for (size_t i = 0; i < typeAActiveUinputSlots_.size(); ++i) {
        int slot = typeAActiveUinputSlots_[i];
        const auto& c = typeAFrameContacts_[i];
        ensureUinputSlot(slot);
        if (c.hasX) queueEvent(EV_ABS, ABS_MT_POSITION_X, c.x);
        if (c.hasY) queueEvent(EV_ABS, ABS_MT_POSITION_Y, c.y);
        if (deviceDesc_.hasTouchMajor && c.major > 0) queueEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, c.major);
        if (deviceDesc_.hasPressure && c.pressure > 0) queueEvent(EV_ABS, ABS_MT_PRESSURE, c.pressure);
    }
    typeAFrameContacts_.clear();
    typeACurrent_ = TypeAContact{};
    emitSyncAndFlush();
}

void TouchMuxEngine::handlePhysicalEvents() {
    struct input_event buf[64];
    while (true) {
        ssize_t n = read(physFd_, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                break;
            }
            if (errno == EINTR) {
                continue;
            }
            LOGE("read(physFd_) error: %s", std::strerror(errno));
            break;
        }
        if (n == 0) {
            break;
        }
        const size_t count = static_cast<size_t>(n) / sizeof(struct input_event);
        for (size_t i = 0; i < count; ++i) {
            const struct input_event& ev = buf[i];

            if (!deviceDesc_.isTypeB) {
                if (ev.type == EV_ABS) {
                    if (ev.code == ABS_MT_POSITION_X) {
                        typeACurrent_.hasX = true;
                        typeACurrent_.x = ev.value;
                    } else if (ev.code == ABS_MT_POSITION_Y) {
                        typeACurrent_.hasY = true;
                        typeACurrent_.y = ev.value;
                    } else if (ev.code == ABS_MT_TOUCH_MAJOR) {
                        typeACurrent_.major = ev.value;
                    } else if (ev.code == ABS_MT_PRESSURE) {
                        typeACurrent_.pressure = ev.value;
                    }
                } else if (ev.type == EV_SYN && ev.code == SYN_MT_REPORT) {
                    if (typeACurrent_.hasX || typeACurrent_.hasY) {
                        typeAFrameContacts_.push_back(typeACurrent_);
                    }
                    typeACurrent_ = TypeAContact{};
                } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    handleTypeAFrameEnd();
                } else if (ev.type == EV_SYN && ev.code == SYN_DROPPED) {
                    typeAFrameContacts_.clear();
                    typeACurrent_ = TypeAContact{};
                    handleTypeAFrameEnd();
                }
                continue;
            }

            // Type-B physical touchscreen handling
            if (ev.type == EV_SYN) {
                if (ev.code == SYN_REPORT) {
                    emitSyncAndFlush();
                } else if (ev.code == SYN_DROPPED) {
                    LOGW("SYN_DROPPED on physical touchscreen; resetting physical slots.");
                    for (int p = 0; p < kMaxPhysSlots; ++p) {
                        int u = physToUinputSlot_[p];
                        if (u >= 0) {
                            ensureUinputSlot(u);
                            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
                            releaseUinputSlot(u);
                            physToUinputSlot_[p] = -1;
                        }
                    }
                    emitSyncAndFlush();
                }
                continue;
            }

            if (ev.type == EV_KEY) {
                if (ev.code == BTN_TOUCH || ev.code == BTN_TOOL_FINGER) {
                    // Computed dynamically in emitSyncAndFlush() from total active slots
                    continue;
                }
                continue;
            }

            if (ev.type == EV_ABS) {
                if (ev.code == ABS_MT_SLOT) {
                    physCurrentSlot_ = std::clamp(ev.value, 0, kMaxPhysSlots - 1);
                } else if (ev.code == ABS_MT_TRACKING_ID) {
                    if (ev.value >= 0) {
                        int u = physToUinputSlot_[physCurrentSlot_];
                        if (u < 0) {
                            u = allocatePhysicalUinputSlot(physCurrentSlot_);
                            physToUinputSlot_[physCurrentSlot_] = u;
                        } else {
                            // Reassign tracking ID if kernel replaced tracking ID directly
                            uinputSlots_[u].trackingId = nextTrackingId();
                        }
                        if (u >= 0) {
                            ensureUinputSlot(u);
                            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, uinputSlots_[u].trackingId);
                        }
                    } else {
                        int u = physToUinputSlot_[physCurrentSlot_];
                        if (u >= 0) {
                            ensureUinputSlot(u);
                            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
                            releaseUinputSlot(u);
                            physToUinputSlot_[physCurrentSlot_] = -1;
                        }
                    }
                } else {
                    // Coordinates or touch metrics for current physical slot
                    int u = physToUinputSlot_[physCurrentSlot_];
                    if (u < 0 && (ev.code == ABS_MT_POSITION_X || ev.code == ABS_MT_POSITION_Y)) {
                        // Finger was already down prior to EVIOCGRAB; adopt smoothly
                        u = allocatePhysicalUinputSlot(physCurrentSlot_);
                        physToUinputSlot_[physCurrentSlot_] = u;
                        if (u >= 0) {
                            ensureUinputSlot(u);
                            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, uinputSlots_[u].trackingId);
                        }
                    }
                    if (u >= 0) {
                        ensureUinputSlot(u);
                        queueEvent(EV_ABS, ev.code, ev.value);
                    }
                }
            }
        }
    }
}

void TouchMuxEngine::handleTimerEvents() {
    uint64_t expirations = 0;
    while (read(timerFd_, &expirations, sizeof(expirations)) > 0) {
    }

    const int64_t now = nowMonotonicNs();

    if (selfCheckPending_.load() && selfCheckDeadlineNs_ > 0 && now >= selfCheckDeadlineNs_) {
        LOGW("TouchMux 5s self-check timed out! Rolling back EVIOCGRAB safely.");
        selfCheckPending_.store(false);
        selfCheckRolledBack_.store(true);
        activeClickingRunning_ = false;
        threadRunning_.store(false);
        engineState_.store(static_cast<int32_t>(EngineState::IDLE));
        return;
    }

    if (activeClickingRunning_) {
        std::uniform_real_distribution<float> jitterDist(-activeJitterRadiusPx_, activeJitterRadiusPx_);
        bool anyEmitted = false;

        for (auto& vt : virtualTargets_) {
            if (!vt.config.enabled) continue;
            if (vt.nextDeadlineNs > now) continue;

            if (!vt.isPressed) {
                int slot = allocateVirtualUinputSlot(vt.config.id);
                if (slot < 0) {
                    // All 16 slots currently occupied; retry shortly without disturbing physical touches
                    vt.nextDeadlineNs = now + 5000000LL;
                    continue;
                }
                float targetX = vt.config.x;
                float targetY = vt.config.y;
                if (vt.config.microJitter && activeJitterRadiusPx_ > 0.0f) {
                    targetX += jitterDist(rng_);
                    targetY += jitterDist(rng_);
                }
                int32_t rx = 0;
                int32_t ry = 0;
                logicalToRaw(targetX, targetY, rx, ry);

                ensureUinputSlot(slot);
                queueEvent(EV_ABS, ABS_MT_TRACKING_ID, uinputSlots_[slot].trackingId);
                queueEvent(EV_ABS, ABS_MT_POSITION_X, rx);
                queueEvent(EV_ABS, ABS_MT_POSITION_Y, ry);
                if (deviceDesc_.hasTouchMajor) {
                    int32_t major = std::clamp(8, deviceDesc_.absTouchMajor.minimum,
                                               std::max(deviceDesc_.absTouchMajor.minimum, deviceDesc_.absTouchMajor.maximum));
                    queueEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, major);
                }
                if (deviceDesc_.hasPressure) {
                    int32_t pressure = std::clamp(64, deviceDesc_.absPressure.minimum,
                                                  std::max(deviceDesc_.absPressure.minimum, deviceDesc_.absPressure.maximum));
                    queueEvent(EV_ABS, ABS_MT_PRESSURE, pressure);
                }

                vt.uinputSlot = slot;
                vt.isPressed = true;
                int64_t pressNs = std::clamp(vt.config.pressDurationMs, int64_t{8}, int64_t{5000}) * 1000000LL;
                vt.nextDeadlineNs = now + pressNs;
                anyEmitted = true;
            } else {
                if (vt.uinputSlot >= 0) {
                    ensureUinputSlot(vt.uinputSlot);
                    queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
                    releaseUinputSlot(vt.uinputSlot);
                }
                vt.uinputSlot = -1;
                vt.isPressed = false;
                int64_t intervalNs = std::clamp(vt.config.intervalMs, int64_t{5}, int64_t{60000}) * 1000000LL;
                vt.nextDeadlineNs = now + intervalNs;
                anyEmitted = true;
            }
        }

        if (anyEmitted) {
            emitSyncAndFlush();
        }
    }

    rearmTimerFd();
}

void TouchMuxEngine::rearmTimerFd() {
    if (timerFd_ < 0) return;

    int64_t earliestNs = 0;
    if (selfCheckPending_.load() && selfCheckDeadlineNs_ > 0) {
        earliestNs = selfCheckDeadlineNs_;
    }
    if (activeClickingRunning_) {
        for (const auto& vt : virtualTargets_) {
            if (!vt.config.enabled) continue;
            if (earliestNs == 0 || vt.nextDeadlineNs < earliestNs) {
                earliestNs = vt.nextDeadlineNs;
            }
        }
    }

    struct itimerspec its {};
    if (earliestNs > 0) {
        const int64_t now = nowMonotonicNs();
        int64_t deltaNs = earliestNs - now;
        if (deltaNs < 100000LL) {
            deltaNs = 100000LL;  // Minimum 100us so timerfd is always armed in the future
        }
        its.it_value.tv_sec = static_cast<time_t>(deltaNs / 1000000000LL);
        its.it_value.tv_nsec = static_cast<long>(deltaNs % 1000000000LL);
    }
    timerfd_settime(timerFd_, 0, &its, nullptr);
}

void TouchMuxEngine::engineLoop() {
    setpriority(PRIO_PROCESS, 0, -10);

    constexpr int kMaxEvents = 8;
    struct epoll_event events[kMaxEvents];

    while (threadRunning_.load()) {
        int nfds = epoll_wait(epollFd_, events, kMaxEvents, -1);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            LOGE("epoll_wait failed: %s", std::strerror(errno));
            break;
        }
        for (int i = 0; i < nfds; ++i) {
            int fd = events[i].data.fd;
            if (fd == physFd_) {
                handlePhysicalEvents();
            } else if (fd == timerFd_) {
                handleTimerEvents();
            } else if (fd == wakeFd_) {
                handleWakeSignal();
            }
        }
    }

    forceReleaseAllSlotsAndSync();
    if (physFd_ >= 0) {
        ioctl(physFd_, EVIOCGRAB, 0);
    }
    if (uinputFd_ >= 0) {
        ioctl(uinputFd_, UI_DEV_DESTROY);
    }
    closeAllFds();
    LOGI("TouchMux engineLoop exited cleanly and released EVIOCGRAB.");
}

}  // namespace touchmux
