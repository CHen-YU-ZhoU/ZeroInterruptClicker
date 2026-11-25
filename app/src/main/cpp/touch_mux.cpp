#include "touch_mux.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <iomanip>
#include <limits>
#include <linux/uhid.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sstream>
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

#ifndef KEY_VOLUMEDOWN
#define KEY_VOLUMEDOWN 114
#endif

#ifndef EVIOCGMTSLOTS
#define EVIOCGMTSLOTS(len) _IOC(_IOC_READ, 'E', 0x0a, len)
#endif

namespace touchmux {

namespace {

constexpr int32_t kAndroidActionDown = 0;
constexpr int32_t kAndroidActionUp = 1;
constexpr int32_t kAndroidActionMove = 2;
constexpr int32_t kAndroidActionPointerDown = 5;
constexpr int32_t kAndroidActionPointerUp = 6;
constexpr int32_t kAndroidActionPointerIndexShift = 8;

constexpr int64_t kSwipeStepNs = 4000000LL;               // 4ms high-density interpolation (~250Hz)
constexpr int64_t kVolDoubleTapWindowNs = 420000000LL;    // 420ms double-tap window
constexpr int64_t kVolEmergencyHoldNs = 3000000000LL;     // 3.0s hold for EVIOCGRAB emergency release
constexpr int64_t kTargetTapDebounceNs = 60000000LL;      // 60ms debounce for ultra-responsive target-zone tap toggle

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

std::string readProcCmdline(const char* cmdlinePath) {
    int fd = open(cmdlinePath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return "";
    }
    char buf[256] = {};
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) {
        return "";
    }
    size_t len = 0;
    while (len < static_cast<size_t>(n) && buf[len] != '\0') {
        ++len;
    }
    while (len > 0 && (buf[len - 1] == ' ' || buf[len - 1] == '\r' || buf[len - 1] == '\n')) {
        --len;
    }
    return std::string(buf, len);
}

std::mutex& cleanupMutex() {
    static std::mutex m;
    return m;
}

void appendHidItem(std::vector<uint8_t>& out, uint8_t tagBase, int32_t val) {
    if (val >= -128 && val <= 127) {
        out.push_back(static_cast<uint8_t>(tagBase | 0x01));
        out.push_back(static_cast<uint8_t>(val & 0xFF));
    } else if (val >= -32768 && val <= 32767) {
        out.push_back(static_cast<uint8_t>(tagBase | 0x02));
        out.push_back(static_cast<uint8_t>(val & 0xFF));
        out.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
    } else {
        out.push_back(static_cast<uint8_t>(tagBase | 0x03));
        const uint32_t u = static_cast<uint32_t>(val);
        out.push_back(static_cast<uint8_t>(u & 0xFF));
        out.push_back(static_cast<uint8_t>((u >> 8) & 0xFF));
        out.push_back(static_cast<uint8_t>((u >> 16) & 0xFF));
        out.push_back(static_cast<uint8_t>((u >> 24) & 0xFF));
    }
}

std::vector<uint8_t> buildUhidTouchDescriptor(int32_t minX,
                                              int32_t maxX,
                                              int32_t minY,
                                              int32_t maxY,
                                              uint8_t xyBits) {
    std::vector<uint8_t> r;
    r.reserve(512);

    // Usage Page (Digitizers), Usage (Touch Screen), Collection (Application)
    r.insert(r.end(), {0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01});
    // Report ID (1): Multi-touch Input Frame
    r.insert(r.end(), {0x85, 0x01});

    for (int slot = 0; slot < kMaxUhidSlots; ++slot) {
        // Usage (Finger), Collection (Logical)
        r.insert(r.end(), {0x09, 0x22, 0xA1, 0x02});
        // Tip Switch (1 bit) + 7 padding bits
        r.insert(r.end(), {
            0x09, 0x42,        // Usage (Tip Switch)
            0x15, 0x00,        // Logical Min (0)
            0x25, 0x01,        // Logical Max (1)
            0x75, 0x01,        // Report Size (1)
            0x95, 0x01,        // Report Count (1)
            0x81, 0x02,        // Input (Data,Var,Abs)
            0x75, 0x07,        // Report Size (7)
            0x95, 0x01,        // Report Count (1)
            0x81, 0x03         // Input (Const,Ary,Abs)
        });
        // Contact Identifier (8 bits, 0..15)
        r.insert(r.end(), {
            0x09, 0x51,        // Usage (Contact Identifier)
            0x15, 0x00,        // Logical Min (0)
            0x25, 0x0F,        // Logical Max (15)
            0x75, 0x08,        // Report Size (8)
            0x95, 0x01,        // Report Count (1)
            0x81, 0x02         // Input (Data,Var,Abs)
        });
        // X coordinate
        r.insert(r.end(), {0x05, 0x01, 0x09, 0x30});  // Usage Page (Generic Desktop), Usage (X)
        appendHidItem(r, 0x14, minX);                  // Logical Min
        appendHidItem(r, 0x24, maxX);                  // Logical Max
        r.insert(r.end(), {0x35, 0x00});               // Physical Min (0)
        appendHidItem(r, 0x44, 1000);                  // Physical Max (1000)
        r.insert(r.end(), {0x75, xyBits, 0x95, 0x01, 0x81, 0x02});
        // Y coordinate
        r.insert(r.end(), {0x09, 0x31});               // Usage (Y)
        appendHidItem(r, 0x14, minY);                  // Logical Min
        appendHidItem(r, 0x24, maxY);                  // Logical Max
        r.insert(r.end(), {0x35, 0x00});
        appendHidItem(r, 0x44, 1000);
        r.insert(r.end(), {0x75, xyBits, 0x95, 0x01, 0x81, 0x02});
        // Tip Pressure (0..255)
        r.insert(r.end(), {
            0x05, 0x0D,        // Usage Page (Digitizers)
            0x09, 0x30,        // Usage (Tip Pressure)
            0x15, 0x00,
            0x26, 0xFF, 0x00,
            0x75, 0x08,
            0x95, 0x01,
            0x81, 0x02
        });
        // End Collection (Logical)
        r.push_back(0xC0);
    }

    // Contact Count (Input, 8 bits)
    r.insert(r.end(), {
        0x05, 0x0D,
        0x09, 0x54,            // Usage (Contact Count)
        0x15, 0x00,
        0x25, static_cast<uint8_t>(kMaxUhidSlots),
        0x75, 0x08,
        0x95, 0x01,
        0x81, 0x02
    });

    // Feature Report ID (2): Contact Count Maximum
    r.insert(r.end(), {
        0x85, 0x02,            // Report ID (2)
        0x09, 0x55,            // Usage (Contact Count Maximum)
        0x15, 0x00,
        0x25, static_cast<uint8_t>(kMaxUhidSlots),
        0x75, 0x08,
        0x95, 0x01,
        0xB1, 0x02             // Feature (Data,Var,Abs)
    });

    // End Collection (Application)
    r.push_back(0xC0);
    return r;
}

}  // namespace

TouchMuxEngine::TouchMuxEngine()
    : rng_(static_cast<uint32_t>(nowMonotonicNs() & 0xFFFFFFFFu)) {
    std::fill(std::begin(physToMuxSlot_), std::end(physToMuxSlot_), -1);
    std::fill(std::begin(snapshotSlots_), std::end(snapshotSlots_), 0);
    std::fill(std::begin(lastEmittedSlotSnapshot_), std::end(lastEmittedSlotSnapshot_), 0);
}

TouchMuxEngine::~TouchMuxEngine() {
    {
        std::lock_guard<std::mutex> lock(targetTriggerMutex_);
        cfgTtMode_ = TargetTriggerMode::OFF;
        cfgTtZones_.clear();
        ++cfgTtVersion_;
    }
    stopTouchTriggerWatchThread();
    stopRecording(true);
    shutdown();
    stopKeyWatchThread();
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

        // Ignore virtual devices created by our own engine (both uinput and uhid)
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

        auto isPreferredPath = [](const std::string& a, const std::string& b) -> bool {
            if (b.empty()) return true;
            if (a.size() != b.size()) return a.size() < b.size();
            return a < b;
        };
        if (score > bestScore || (score == bestScore && isPreferredPath(path, best.path))) {
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

std::vector<std::string> TouchMuxEngine::findVolumeDownKeyDevices() {
    std::vector<std::string> paths;
    DIR* dir = opendir("/dev/input");
    if (!dir) {
        return paths;
    }

    struct dirent* ent = nullptr;
    while ((ent = readdir(dir)) != nullptr) {
        if (std::strncmp(ent->d_name, "event", 5) != 0) {
            continue;
        }
        std::string path = std::string("/dev/input/") + ent->d_name;
        int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }
        std::array<uint8_t, kBitFieldBytes(EV_MAX)> evBits{};
        std::array<uint8_t, kBitFieldBytes(KEY_MAX)> keyBits{};
        if (ioctl(fd, EVIOCGBIT(0, evBits.size()), evBits.data()) >= 0 &&
            testBit(evBits, EV_KEY) &&
            ioctl(fd, EVIOCGBIT(EV_KEY, keyBits.size()), keyBits.data()) >= 0 &&
            testBit(keyBits, KEY_VOLUMEDOWN)) {
            paths.push_back(path);
        }
        close(fd);
    }
    closedir(dir);
    return paths;
}

bool TouchMuxEngine::hasActivePhysicalTouch(int fd, const DeviceDesc& desc) {
    std::array<uint8_t, kBitFieldBytes(KEY_MAX)> keyState {};
    if (ioctl(fd, EVIOCGKEY(keyState.size()), keyState.data()) >= 0 &&
        testBit(keyState, BTN_TOUCH)) {
        return true;
    }
    if (desc.isTypeB) {
        const int slots = std::clamp(desc.slotCount, 1, kMaxPhysSlots);
        struct {
            uint32_t code;
            int32_t values[kMaxPhysSlots];
        } req {};
        req.code = ABS_MT_TRACKING_ID;
        std::fill(std::begin(req.values), std::end(req.values), -1);
        const size_t reqBytes = sizeof(uint32_t) + static_cast<size_t>(slots) * sizeof(int32_t);
        if (ioctl(fd, EVIOCGMTSLOTS(reqBytes), &req) >= 0) {
            for (int i = 0; i < slots; ++i) {
                if (req.values[i] >= 0) {
                    return true;
                }
            }
        }
    }
    return false;
}

void TouchMuxEngine::waitForPhysicalTouchIdle(int fd, const DeviceDesc& desc, int maxWaitMs) {
    if (fd < 0 || maxWaitMs <= 0) {
        return;
    }
    const int64_t deadlineNs =
        nowMonotonicNs() + static_cast<int64_t>(maxWaitMs) * 1000000LL;
    bool sawActive = false;
    struct input_event drainBuf[64];

    for (;;) {
        bool drainedAny = false;
        while (read(fd, drainBuf, sizeof(drainBuf)) > 0) {
            drainedAny = true;
        }

        if (!hasActivePhysicalTouch(fd, desc)) {
            if (sawActive || drainedAny) {
                // Give Android InputReader / SpotController a brief window to consume the final UP/SYN_REPORT
                usleep(20000);
                while (read(fd, drainBuf, sizeof(drainBuf)) > 0) {
                }
                if (hasActivePhysicalTouch(fd, desc)) {
                    sawActive = true;
                    continue;
                }
                LOGI("Physical touchscreen %s reached idle before EVIOCGRAB.", desc.path.c_str());
            }
            break;
        }

        sawActive = true;
        const int64_t nowNs = nowMonotonicNs();
        if (nowNs >= deadlineNs) {
            LOGW("Timed out waiting %dms for physical touchscreen %s to become idle before EVIOCGRAB.",
                 maxWaitMs, desc.path.c_str());
            break;
        }
        const int64_t remMs = (deadlineNs - nowNs + 999999LL) / 1000000LL;
        const int pollMs = static_cast<int>(std::min<int64_t>(remMs, 20LL));
        struct pollfd pfd {};
        pfd.fd = fd;
        pfd.events = POLLIN;
        poll(&pfd, 1, std::max(1, pollMs));
    }
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

    int hfd = open("/dev/uhid", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (hfd >= 0) {
        result.uhidWritable = true;
        result.uhidErrno = 0;
        close(hfd);
    } else {
        result.uhidWritable = false;
        result.uhidErrno = errno;
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

int TouchMuxEngine::cleanupOrphanProcesses(const std::string& processName) {
    std::lock_guard<std::mutex> lock(cleanupMutex());

    std::string targetName = processName;
    if (targetName.empty()) {
        targetName = readProcCmdline("/proc/self/cmdline");
    }
    if (targetName.empty()) {
        targetName = "com.zerointerrupt.clicker:touchmux";
    }
    if (targetName.find(":touchmux") == std::string::npos) {
        LOGW("cleanupOrphanProcesses skipped unsafe target '%s'", targetName.c_str());
        return 0;
    }

    const pid_t selfPid = getpid();
    DIR* procDir = opendir("/proc");
    if (!procDir) {
        LOGW("cleanupOrphanProcesses opendir(/proc) failed: %s", std::strerror(errno));
        return 0;
    }

    std::vector<pid_t> stalePids;
    struct dirent* ent = nullptr;
    while ((ent = readdir(procDir)) != nullptr) {
        if (ent->d_name[0] < '0' || ent->d_name[0] > '9') {
            continue;
        }
        char* endPtr = nullptr;
        long parsed = std::strtol(ent->d_name, &endPtr, 10);
        if (endPtr == ent->d_name || *endPtr != '\0' || parsed <= 1) {
            continue;
        }
        pid_t pid = static_cast<pid_t>(parsed);
        if (pid == selfPid) {
            continue;
        }
        std::string cmdPath = std::string("/proc/") + ent->d_name + "/cmdline";
        if (readProcCmdline(cmdPath.c_str()) == targetName) {
            stalePids.push_back(pid);
        }
    }
    closedir(procDir);

    if (stalePids.empty()) {
        return 0;
    }

    LOGI("Reclaiming %zu orphan '%s' process(es)...", stalePids.size(), targetName.c_str());
    int signaled = 0;
    for (pid_t pid : stalePids) {
        if (kill(pid, SIGTERM) == 0) {
            ++signaled;
        } else if (errno != ESRCH) {
            LOGW("kill(%d, SIGTERM) failed: %s", static_cast<int>(pid), std::strerror(errno));
        }
    }

    auto anyStillActive = [&stalePids, &targetName]() -> bool {
        for (pid_t pid : stalePids) {
            if (kill(pid, 0) != 0) {
                continue;
            }
            std::string cmdPath = "/proc/" + std::to_string(static_cast<int>(pid)) + "/cmdline";
            if (readProcCmdline(cmdPath.c_str()) == targetName) {
                return true;
            }
        }
        return false;
    };

    for (int i = 0; i < 18 && anyStillActive(); ++i) {
        usleep(20000);
    }
    if (anyStillActive()) {
        for (pid_t pid : stalePids) {
            if (kill(pid, 0) == 0) {
                if (kill(pid, SIGKILL) == 0) {
                    LOGW("Sent SIGKILL to lingering orphan pid %d", static_cast<int>(pid));
                }
            }
        }
        for (int i = 0; i < 15 && anyStillActive(); ++i) {
            usleep(20000);
        }
    }

    usleep(120000);
    return signaled;
}

void TouchMuxEngine::setCallbacks(EngineCallbacks callbacks) {
    {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        callbacks_ = std::move(callbacks);
    }
    ensureKeyWatchThread();
}

void TouchMuxEngine::setVolumeKeyControlEnabled(bool enabled) {
    volumeKeyControlEnabled_.store(enabled);
}

void TouchMuxEngine::setTargetTriggerConfig(int32_t mode,
                                            const std::vector<TargetTriggerZone>& zones,
                                            int displayW,
                                            int displayH,
                                            int rotation,
                                            int excludeLeft,
                                            int excludeTop,
                                            int excludeRight,
                                            int excludeBottom,
                                            float touchSlopPx) {
    TargetTriggerMode safeMode = TargetTriggerMode::OFF;
    if (mode == static_cast<int32_t>(TargetTriggerMode::TAP)) {
        safeMode = TargetTriggerMode::TAP;
    } else if (mode == static_cast<int32_t>(TargetTriggerMode::LONG_PRESS)) {
        safeMode = TargetTriggerMode::LONG_PRESS;
    }
    {
        std::lock_guard<std::mutex> lock(targetTriggerMutex_);
        cfgTtMode_ = safeMode;
        cfgTtZones_ = zones;
        cfgTtDisplayW_ = std::max(1, displayW);
        cfgTtDisplayH_ = std::max(1, displayH);
        cfgTtRotation_ = rotation;
        cfgTtExL_ = excludeLeft;
        cfgTtExT_ = excludeTop;
        cfgTtExR_ = excludeRight;
        cfgTtExB_ = excludeBottom;
        cfgTtSlopPx_ = std::clamp(touchSlopPx, 12.0f, 160.0f);
        ++cfgTtVersion_;
    }
    if (threadRunning_.load()) {
        signalWake();
    }
    syncTouchTriggerWatcherState();
}

void TouchMuxEngine::emitTargetTriggerEvent(TargetTriggerEvent ev) {
    std::function<void(int32_t)> cb;
    {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        cb = callbacks_.onTargetTriggerEvent;
    }
    if (cb) {
        cb(static_cast<int32_t>(ev));
    }
}

void TouchMuxEngine::resetEngineTriggerSlots(bool emitHoldEnd) {
    const int64_t nowNs = nowMonotonicNs();
    for (int s = 0; s < kMaxPhysSlots; ++s) {
        if (emitHoldEnd && engTriggerSlots_[s].holdFired) {
            if (triggerInstantStop(nowNs)) {
                emitTargetTriggerEvent(TargetTriggerEvent::NATIVE_HOLD_STOPPED);
            } else {
                emitTargetTriggerEvent(TargetTriggerEvent::HOLD_END);
            }
        }
        engTriggerSlots_[s] = TriggerSlotTrack{};
        engPhysPendingDown_[s] = false;
        engPhysPendingUp_[s] = false;
    }
}

void TouchMuxEngine::buildSequentialStages(const std::vector<NativeClickTarget>& list, int64_t startNs) {
    stages_.clear();
    activeStageIdx_ = 0;
    for (const auto& t : list) {
        if (!t.enabled) continue;
        if (stages_.empty() || stages_.back().stageIndex != t.stageIndex) {
            StageRuntime st;
            st.stageIndex = t.stageIndex;
            st.durationMs = std::max<int64_t>(0, t.stageDurationMs);
            st.gapMs = std::clamp<int64_t>(t.stageGapMs, 0, 60000);
            st.repeatCount = std::max<int32_t>(0, t.stageRepeatCount);
            st.startDelayUntilNs = startNs;
            stages_.push_back(std::move(st));
        }
        VirtualTargetRuntime rt;
        rt.config = t;
        rt.nextDeadlineNs = startNs;
        stages_.back().targets.push_back(rt);
    }
}

bool TouchMuxEngine::triggerInstantStart(int64_t nowNs) {
    if (activeClickingRunning_ || activeRawMacroRunning_) {
        return false;
    }
    std::vector<NativeClickTarget> nextTargets;
    ExecutionMode nextExecMode = ExecutionMode::CONCURRENT;
    int32_t nextPresetLoopCount = 0;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (cfgTargets_.empty()) {
            return false;
        }
        bool anyEnabled = false;
        for (const auto& t : cfgTargets_) {
            if (t.enabled) {
                anyEnabled = true;
                break;
            }
        }
        if (!anyEnabled) {
            return false;
        }
        nextTargets = cfgTargets_;
        nextExecMode = cfgExecutionMode_;
        nextPresetLoopCount = cfgPresetLoopCount_;
        activeDisplayW_ = cfgDisplayW_;
        activeDisplayH_ = cfgDisplayH_;
        activeRotation_ = cfgRotation_;
        activeJitterRadiusPx_ = cfgJitterRadiusPx_;
        cfgRawMacroRunning_ = false;
        cfgClickingRunning_ = true;
    }

    activeExecutionMode_ = nextExecMode;
    activePresetLoopCount_ = nextPresetLoopCount;
    seqCompletedLoops_ = 0;
    releaseAllVirtualTargetSlots();
    virtualTargets_.clear();
    stages_.clear();
    activeStageIdx_ = 0;

    if (nextExecMode == ExecutionMode::SEQUENTIAL) {
        buildSequentialStages(nextTargets, nowNs);
    } else {
        virtualTargets_.reserve(nextTargets.size());
        for (const auto& nt : nextTargets) {
            if (!nt.enabled) continue;
            VirtualTargetRuntime fresh;
            fresh.config = nt;
            fresh.isPressed = false;
            fresh.muxSlot = -1;
            fresh.nextDeadlineNs = nowNs + static_cast<int64_t>(virtualTargets_.size()) * 500000LL;
            virtualTargets_.push_back(fresh);
        }
    }

    const bool hasPayload = (nextExecMode == ExecutionMode::SEQUENTIAL)
                                ? !stages_.empty()
                                : !virtualTargets_.empty();
    if (!hasPayload) {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgClickingRunning_ = false;
        return false;
    }

    activeClickingRunning_ = true;
    stepVirtualTargets(nowNs);
    rearmTimerFd();
    publishSlotSnapshotIfChanged(nowNs);
    return true;
}

bool TouchMuxEngine::triggerInstantStop(int64_t nowNs) {
    if (!activeClickingRunning_ && !activeRawMacroRunning_) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgClickingRunning_ = false;
        cfgRawMacroRunning_ = false;
    }
    activeClickingRunning_ = false;
    activeRawMacroRunning_ = false;
    seqCompletedLoops_ = 0;
    releaseAllVirtualTargetSlots();
    releaseAllRawMacroSlots();
    releaseCarriedHoldSlots();
    emitSyncAndFlush();
    rearmTimerFd();
    publishSlotSnapshotIfChanged(nowNs);
    return true;
}

void TouchMuxEngine::syncEngineTargetTriggerConfig() {
    TargetTriggerMode nextMode = TargetTriggerMode::OFF;
    std::vector<TargetTriggerZone> nextZones;
    int nextExL = 0, nextExT = 0, nextExR = 0, nextExB = 0;
    float nextSlop = 48.0f;
    uint64_t nextVer = 0;
    {
        std::lock_guard<std::mutex> lock(targetTriggerMutex_);
        if (cfgTtVersion_ == activeTtVersion_) {
            return;
        }
        nextMode = cfgTtMode_;
        nextZones = cfgTtZones_;
        nextExL = cfgTtExL_;
        nextExT = cfgTtExT_;
        nextExR = cfgTtExR_;
        nextExB = cfgTtExB_;
        nextSlop = cfgTtSlopPx_;
        nextVer = cfgTtVersion_;
    }

    const bool modeChanged = (nextMode != activeTtMode_);
    activeTtMode_ = nextMode;
    activeTtZones_ = std::move(nextZones);
    activeTtExL_ = nextExL;
    activeTtExT_ = nextExT;
    activeTtExR_ = nextExR;
    activeTtExB_ = nextExB;
    activeTtSlopPx_ = nextSlop;
    activeTtVersion_ = nextVer;

    if (activeTtMode_ == TargetTriggerMode::OFF || activeTtZones_.empty() || modeChanged) {
        resetEngineTriggerSlots(true);
    }
}

void TouchMuxEngine::processEngineTriggerSynFrame(int64_t nowNs) {
    if (activeTtMode_ == TargetTriggerMode::OFF || activeTtZones_.empty()) {
        for (int s = 0; s < kMaxPhysSlots; ++s) {
            engPhysPendingDown_[s] = false;
            engPhysPendingUp_[s] = false;
        }
        return;
    }

    auto insideExclude = [&](float x, float y) -> bool {
        if (activeTtExR_ <= activeTtExL_ || activeTtExB_ <= activeTtExT_) return false;
        return x >= static_cast<float>(activeTtExL_ - 8) &&
               x <= static_cast<float>(activeTtExR_ + 8) &&
               y >= static_cast<float>(activeTtExT_ - 8) &&
               y <= static_cast<float>(activeTtExB_ + 8);
    };
    auto insideAnyZone = [&](float x, float y) -> bool {
        for (const auto& z : activeTtZones_) {
            const float dx = x - z.x;
            const float dy = y - z.y;
            if ((dx * dx + dy * dy) <= (z.radiusPx * z.radiusPx)) {
                return true;
            }
        }
        return false;
    };
    auto anyHoldActive = [&]() -> bool {
        for (int k = 0; k < kMaxPhysSlots; ++k) {
            if (engTriggerSlots_[k].active && engTriggerSlots_[k].holdFired) {
                return true;
            }
        }
        return false;
    };

    for (int s = 0; s < kMaxPhysSlots; ++s) {
        auto& tr = engTriggerSlots_[s];
        float lx = 0.0f;
        float ly = 0.0f;
        rawToLogical(engPhysRawX_[s], engPhysRawY_[s], lx, ly);

        if (engPhysPendingDown_[s]) {
            engPhysPendingDown_[s] = false;
            if (tr.holdFired) {
                if (triggerInstantStop(nowNs)) {
                    emitTargetTriggerEvent(TargetTriggerEvent::NATIVE_HOLD_STOPPED);
                } else {
                    emitTargetTriggerEvent(TargetTriggerEvent::HOLD_END);
                }
            }
            tr = TriggerSlotTrack{};
            tr.active = true;
            tr.downX = lx;
            tr.downY = ly;
            tr.curX = lx;
            tr.curY = ly;
            tr.downNs = nowNs;
            if (!insideExclude(lx, ly) && insideAnyZone(lx, ly)) {
                if (activeTtMode_ == TargetTriggerMode::LONG_PRESS) {
                    if (!anyHoldActive() && !activeClickingRunning_ && !activeRawMacroRunning_) {
                        tr.candidate = true;
                        tr.holdFired = true;
                        tr.suppressed = true;
                        int u = physToMuxSlot_[s];
                        if (u >= 0) {
                            emitSlotUp(u);
                            physToMuxSlot_[s] = -1;
                        }
                        emitSyncAndFlush();
                        if (triggerInstantStart(nowNs)) {
                            emitTargetTriggerEvent(TargetTriggerEvent::NATIVE_HOLD_STARTED);
                        } else {
                            emitTargetTriggerEvent(TargetTriggerEvent::HOLD_START);
                        }
                    }
                } else {
                    tr.candidate = true;
                }
            }
        } else if (tr.active && !engPhysPendingUp_[s]) {
            tr.curX = lx;
            tr.curY = ly;
            if (activeTtMode_ == TargetTriggerMode::TAP && tr.candidate && !tr.movedTooFar) {
                if (std::hypot(tr.curX - tr.downX, tr.curY - tr.downY) > activeTtSlopPx_) {
                    tr.movedTooFar = true;
                }
            }
        }

        if (engPhysPendingUp_[s]) {
            engPhysPendingUp_[s] = false;
            if (tr.active) {
                if (activeTtMode_ == TargetTriggerMode::TAP && tr.candidate && !tr.movedTooFar) {
                    if (std::hypot(lx - tr.downX, ly - tr.downY) <= activeTtSlopPx_ &&
                        (nowNs - engLastTapTriggerNs_) >= kTargetTapDebounceNs) {
                        engLastTapTriggerNs_ = nowNs;
                        emitSyncAndFlush();
                        if (activeClickingRunning_ || activeRawMacroRunning_) {
                            if (triggerInstantStop(nowNs)) {
                                emitTargetTriggerEvent(TargetTriggerEvent::NATIVE_TAP_STOPPED);
                            } else {
                                emitTargetTriggerEvent(TargetTriggerEvent::TAP);
                            }
                        } else {
                            if (triggerInstantStart(nowNs)) {
                                emitTargetTriggerEvent(TargetTriggerEvent::NATIVE_TAP_STARTED);
                            } else {
                                emitTargetTriggerEvent(TargetTriggerEvent::TAP);
                            }
                        }
                    }
                }
                if (tr.holdFired) {
                    if (triggerInstantStop(nowNs)) {
                        emitTargetTriggerEvent(TargetTriggerEvent::NATIVE_HOLD_STOPPED);
                    } else {
                        emitTargetTriggerEvent(TargetTriggerEvent::HOLD_END);
                    }
                }
            }
            tr = TriggerSlotTrack{};
        }
    }
}

void TouchMuxEngine::stopTouchTriggerWatchThread() {
    std::lock_guard<std::mutex> lifeLock(ttWatchLifecycleMutex_);
    if (ttWatchRunning_.exchange(false)) {
        if (ttWatchWakeFd_ >= 0) {
            uint64_t val = 1;
            ssize_t ignored = write(ttWatchWakeFd_, &val, sizeof(val));
            (void)ignored;
        }
    }
    if (ttWatchThread_.joinable()) {
        ttWatchThread_.join();
    }
    if (ttWatchWakeFd_ >= 0) {
        close(ttWatchWakeFd_);
        ttWatchWakeFd_ = -1;
    }
}

void TouchMuxEngine::syncTouchTriggerWatcherState() {
    std::lock_guard<std::mutex> lifeLock(ttWatchLifecycleMutex_);
    bool shouldRun = false;
    {
        std::lock_guard<std::mutex> cfgLock(targetTriggerMutex_);
        shouldRun = (cfgTtMode_ != TargetTriggerMode::OFF) &&
                    !cfgTtZones_.empty() &&
                    !threadRunning_.load() &&
                    !recRunning_.load();
    }

    if (!shouldRun) {
        if (ttWatchRunning_.exchange(false)) {
            if (ttWatchWakeFd_ >= 0) {
                uint64_t val = 1;
                ssize_t ignored = write(ttWatchWakeFd_, &val, sizeof(val));
                (void)ignored;
            }
        }
        if (ttWatchThread_.joinable()) {
            ttWatchThread_.join();
        }
        if (ttWatchWakeFd_ >= 0) {
            close(ttWatchWakeFd_);
            ttWatchWakeFd_ = -1;
        }
        return;
    }

    if (ttWatchRunning_.load()) {
        if (ttWatchWakeFd_ >= 0) {
            uint64_t val = 1;
            ssize_t ignored = write(ttWatchWakeFd_, &val, sizeof(val));
            (void)ignored;
        }
        return;
    }

    if (ttWatchThread_.joinable()) {
        ttWatchThread_.join();
    }
    if (ttWatchWakeFd_ >= 0) {
        close(ttWatchWakeFd_);
        ttWatchWakeFd_ = -1;
    }
    ttWatchWakeFd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (ttWatchWakeFd_ < 0) {
        return;
    }
    ttWatchRunning_.store(true);
    ttWatchThread_ = std::thread(&TouchMuxEngine::touchTriggerWatchLoop, this);
}

void TouchMuxEngine::touchTriggerWatchLoop() {
    DeviceDesc desc{};
    int err = 0;
    if (!findBestTouchscreen(desc, err)) {
        ttWatchRunning_.store(false);
        return;
    }
    int fd = open(desc.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        ttWatchRunning_.store(false);
        return;
    }

    struct input_event drainBuf[64];
    while (read(fd, drainBuf, sizeof(drainBuf)) > 0) {
    }

    int curSlot = 0;
    if (desc.isTypeB) {
        struct input_absinfo absSlotNow {};
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &absSlotNow) == 0) {
            curSlot = std::clamp(absSlotNow.value, 0, kMaxPhysSlots - 1);
        }
    }

    TriggerSlotTrack slots[kMaxPhysSlots]{};
    bool pendingDown[kMaxPhysSlots]{};
    bool pendingUp[kMaxPhysSlots]{};
    int32_t rawX[kMaxPhysSlots]{};
    int32_t rawY[kMaxPhysSlots]{};
    const int32_t midX = (desc.absX.minimum + desc.absX.maximum) / 2;
    const int32_t midY = (desc.absY.minimum + desc.absY.maximum) / 2;
    for (int s = 0; s < kMaxPhysSlots; ++s) {
        rawX[s] = midX;
        rawY[s] = midY;
    }

    TargetTriggerMode mode = TargetTriggerMode::OFF;
    std::vector<TargetTriggerZone> zones;
    int dispW = 1080, dispH = 2400, rot = 0;
    int exL = 0, exT = 0, exR = 0, exB = 0;
    float slopPx = 48.0f;
    uint64_t loadedVer = 0;
    int64_t lastTapNs = 0;

    auto reloadCfg = [&]() {
        std::lock_guard<std::mutex> lock(targetTriggerMutex_);
        if (cfgTtVersion_ == loadedVer) return;
        const bool modeChanged = (cfgTtMode_ != mode);
        mode = cfgTtMode_;
        zones = cfgTtZones_;
        dispW = cfgTtDisplayW_;
        dispH = cfgTtDisplayH_;
        rot = cfgTtRotation_;
        exL = cfgTtExL_;
        exT = cfgTtExT_;
        exR = cfgTtExR_;
        exB = cfgTtExB_;
        slopPx = cfgTtSlopPx_;
        loadedVer = cfgTtVersion_;
        if (mode == TargetTriggerMode::OFF || zones.empty() || modeChanged) {
            for (int s = 0; s < kMaxPhysSlots; ++s) {
                if (slots[s].holdFired) {
                    emitTargetTriggerEvent(TargetTriggerEvent::HOLD_END);
                }
                slots[s] = TriggerSlotTrack{};
                pendingDown[s] = false;
                pendingUp[s] = false;
            }
        }
    };
    reloadCfg();

    auto insideExclude = [&](float x, float y) -> bool {
        if (exR <= exL || exB <= exT) return false;
        return x >= static_cast<float>(exL - 8) &&
               x <= static_cast<float>(exR + 8) &&
               y >= static_cast<float>(exT - 8) &&
               y <= static_cast<float>(exB + 8);
    };
    auto insideAnyZone = [&](float x, float y) -> bool {
        for (const auto& z : zones) {
            const float dx = x - z.x;
            const float dy = y - z.y;
            if ((dx * dx + dy * dy) <= (z.radiusPx * z.radiusPx)) {
                return true;
            }
        }
        return false;
    };
    auto anyHoldActive = [&]() -> bool {
        for (int k = 0; k < kMaxPhysSlots; ++k) {
            if (slots[k].active && slots[k].holdFired) return true;
        }
        return false;
    };

    auto commitSyn = [&](int64_t nowNs) {
        if (mode == TargetTriggerMode::OFF || zones.empty()) {
            for (int s = 0; s < kMaxPhysSlots; ++s) {
                pendingDown[s] = false;
                pendingUp[s] = false;
            }
            return;
        }
        for (int s = 0; s < kMaxPhysSlots; ++s) {
            auto& tr = slots[s];
            float lx = 0.0f, ly = 0.0f;
            rawToLogicalDesc(desc, dispW, dispH, rot, rawX[s], rawY[s], lx, ly);

            if (pendingDown[s]) {
                pendingDown[s] = false;
                if (tr.holdFired) {
                    emitTargetTriggerEvent(TargetTriggerEvent::HOLD_END);
                }
                tr = TriggerSlotTrack{};
                tr.active = true;
                tr.downX = lx;
                tr.downY = ly;
                tr.curX = lx;
                tr.curY = ly;
                tr.downNs = nowNs;
                if (!insideExclude(lx, ly) && insideAnyZone(lx, ly)) {
                    if (mode == TargetTriggerMode::LONG_PRESS) {
                        if (!anyHoldActive()) {
                            tr.candidate = true;
                            tr.holdFired = true;
                            emitTargetTriggerEvent(TargetTriggerEvent::HOLD_START);
                        }
                    } else {
                        tr.candidate = true;
                    }
                }
            } else if (tr.active && !pendingUp[s]) {
                tr.curX = lx;
                tr.curY = ly;
                if (mode == TargetTriggerMode::TAP && tr.candidate && !tr.movedTooFar) {
                    if (std::hypot(tr.curX - tr.downX, tr.curY - tr.downY) > slopPx) {
                        tr.movedTooFar = true;
                    }
                }
            }

            if (pendingUp[s]) {
                pendingUp[s] = false;
                if (tr.active) {
                    if (mode == TargetTriggerMode::TAP && tr.candidate && !tr.movedTooFar) {
                        if (std::hypot(lx - tr.downX, ly - tr.downY) <= slopPx &&
                            (nowNs - lastTapNs) >= kTargetTapDebounceNs) {
                            lastTapNs = nowNs;
                            emitTargetTriggerEvent(TargetTriggerEvent::TAP);
                        }
                    }
                    if (tr.holdFired) {
                        emitTargetTriggerEvent(TargetTriggerEvent::HOLD_END);
                    }
                }
                tr = TriggerSlotTrack{};
            }
        }
    };

    TypeAContact taCur{};
    std::vector<TypeAContact> taContacts;
    auto commitTypeA = [&](int64_t nowNs) {
        bool usedSlot[kMaxPhysSlots]{};
        int assigned[16]{};
        const size_t cnt = std::min<size_t>(taContacts.size(), 16);
        for (size_t c = 0; c < cnt; ++c) {
            assigned[c] = -1;
            int bestS = -1;
            int64_t bestD2 = std::numeric_limits<int64_t>::max();
            for (int s = 0; s < kMaxPhysSlots; ++s) {
                if (usedSlot[s] || !slots[s].active) continue;
                int64_t dx = static_cast<int64_t>(taContacts[c].x) - rawX[s];
                int64_t dy = static_cast<int64_t>(taContacts[c].y) - rawY[s];
                int64_t d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestS = s;
                }
            }
            if (bestS >= 0) {
                usedSlot[bestS] = true;
                assigned[c] = bestS;
            }
        }
        for (int s = 0; s < kMaxPhysSlots; ++s) {
            if (slots[s].active && !usedSlot[s]) pendingUp[s] = true;
        }
        for (size_t c = 0; c < cnt; ++c) {
            int s = assigned[c];
            if (s < 0) {
                for (int k = 0; k < kMaxPhysSlots; ++k) {
                    if (!slots[k].active && !usedSlot[k]) {
                        s = k;
                        usedSlot[k] = true;
                        pendingDown[s] = true;
                        break;
                    }
                }
            }
            if (s >= 0) {
                rawX[s] = taContacts[c].x;
                rawY[s] = taContacts[c].y;
            }
        }
        taContacts.clear();
        taCur = TypeAContact{};
        commitSyn(nowNs);
    };

    struct pollfd pfds[2] = {
        {ttWatchWakeFd_, POLLIN, 0},
        {fd, POLLIN, 0}
    };
    struct input_event evBuf[64];

    LOGI("touchTriggerWatchLoop started on %s (non-grabbed)", desc.path.c_str());

    while (ttWatchRunning_.load()) {
        int rc = poll(pfds, 2, -1);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (pfds[0].revents & POLLIN) {
            uint64_t drained = 0;
            while (read(ttWatchWakeFd_, &drained, sizeof(drained)) > 0) {}
            if (!ttWatchRunning_.load()) break;
            reloadCfg();
            if (mode == TargetTriggerMode::OFF || zones.empty()) break;
        }

        if (pfds[1].revents & POLLIN) {
            while (ttWatchRunning_.load()) {
                ssize_t n = read(fd, evBuf, sizeof(evBuf));
                if (n < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                    if (errno == EINTR) continue;
                    ttWatchRunning_.store(false);
                    break;
                }
                if (n == 0) break;

                const size_t count = static_cast<size_t>(n) / sizeof(struct input_event);
                for (size_t i = 0; i < count; ++i) {
                    const auto& ev = evBuf[i];
                    if (!desc.isTypeB) {
                        if (ev.type == EV_ABS) {
                            if (ev.code == ABS_MT_POSITION_X) {
                                taCur.hasX = true;
                                taCur.x = ev.value;
                            } else if (ev.code == ABS_MT_POSITION_Y) {
                                taCur.hasY = true;
                                taCur.y = ev.value;
                            }
                        } else if (ev.type == EV_SYN && ev.code == SYN_MT_REPORT) {
                            if (taCur.hasX || taCur.hasY) taContacts.push_back(taCur);
                            taCur = TypeAContact{};
                        } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                            commitTypeA(nowMonotonicNs());
                        } else if (ev.type == EV_SYN && ev.code == SYN_DROPPED) {
                            taContacts.clear();
                            taCur = TypeAContact{};
                            commitTypeA(nowMonotonicNs());
                        }
                        continue;
                    }

                    if (ev.type == EV_SYN) {
                        if (ev.code == SYN_REPORT) {
                            commitSyn(nowMonotonicNs());
                        } else if (ev.code == SYN_DROPPED) {
                            for (int s = 0; s < kMaxPhysSlots; ++s) {
                                if (slots[s].active) pendingUp[s] = true;
                            }
                            commitSyn(nowMonotonicNs());
                        }
                    } else if (ev.type == EV_ABS) {
                        if (ev.code == ABS_MT_SLOT) {
                            curSlot = std::clamp(ev.value, 0, kMaxPhysSlots - 1);
                        } else if (ev.code == ABS_MT_TRACKING_ID) {
                            if (ev.value >= 0) {
                                pendingDown[curSlot] = true;
                                pendingUp[curSlot] = false;
                            } else if (slots[curSlot].active || pendingDown[curSlot]) {
                                pendingUp[curSlot] = true;
                            }
                        } else if (ev.code == ABS_MT_POSITION_X) {
                            rawX[curSlot] = ev.value;
                            if (!slots[curSlot].active && !pendingDown[curSlot]) {
                                pendingDown[curSlot] = true;
                            }
                        } else if (ev.code == ABS_MT_POSITION_Y) {
                            rawY[curSlot] = ev.value;
                            if (!slots[curSlot].active && !pendingDown[curSlot]) {
                                pendingDown[curSlot] = true;
                            }
                        }
                    }
                }
            }
        }
    }

    close(fd);
    ttWatchRunning_.store(false);
    LOGI("touchTriggerWatchLoop exited.");
}

void TouchMuxEngine::ensureKeyWatchThread() {
    if (keyWatchRunning_.load()) {
        return;
    }
    if (keyWatchThread_.joinable()) {
        keyWatchThread_.join();
    }
    keyWatchWakeFd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (keyWatchWakeFd_ < 0) {
        return;
    }
    keyWatchRunning_.store(true);
    keyWatchThread_ = std::thread(&TouchMuxEngine::keyWatchLoop, this);
}

void TouchMuxEngine::stopKeyWatchThread() {
    if (keyWatchRunning_.exchange(false)) {
        if (keyWatchWakeFd_ >= 0) {
            uint64_t val = 1;
            ssize_t ignored = write(keyWatchWakeFd_, &val, sizeof(val));
            (void)ignored;
        }
    }
    if (keyWatchThread_.joinable()) {
        keyWatchThread_.join();
    }
    if (keyWatchWakeFd_ >= 0) {
        close(keyWatchWakeFd_);
        keyWatchWakeFd_ = -1;
    }
}

void TouchMuxEngine::keyWatchLoop() {
    std::vector<std::string> paths = findVolumeDownKeyDevices();
    std::vector<int> fds;
    for (const auto& p : paths) {
        int fd = open(p.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd >= 0) {
            fds.push_back(fd);
        }
    }

    if (fds.empty()) {
        LOGI("keyWatchLoop: no readable KEY_VOLUMEDOWN evdev node; sleeping quietly.");
        keyWatchRunning_.store(false);
        return;
    }

    LOGI("keyWatchLoop: monitoring %zu non-grabbed KEY_VOLUMEDOWN node(s)", fds.size());

    bool volDownHeld = false;
    bool emergencyFiredForHold = false;
    int64_t holdStartNs = 0;
    int64_t prevTapDownNs = 0;

    std::vector<struct pollfd> pfds;
    pfds.reserve(fds.size() + 1);

    while (keyWatchRunning_.load()) {
        pfds.clear();
        pfds.push_back({keyWatchWakeFd_, POLLIN, 0});
        for (int fd : fds) {
            pfds.push_back({fd, POLLIN, 0});
        }

        const int timeoutMs = volDownHeld ? 150 : 1500;
        int rc = poll(pfds.data(), static_cast<nfds_t>(pfds.size()), timeoutMs);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }

        if (pfds[0].revents & POLLIN) {
            uint64_t drained = 0;
            while (read(keyWatchWakeFd_, &drained, sizeof(drained)) > 0) {}
            if (!keyWatchRunning_.load()) break;
        }

        const int64_t now = nowMonotonicNs();
        for (size_t idx = 1; idx < pfds.size(); ++idx) {
            if (!(pfds[idx].revents & POLLIN)) continue;
            struct input_event buf[16];
            while (true) {
                ssize_t n = read(pfds[idx].fd, buf, sizeof(buf));
                if (n <= 0) break;
                const size_t count = static_cast<size_t>(n) / sizeof(struct input_event);
                for (size_t i = 0; i < count; ++i) {
                    const auto& ev = buf[i];
                    if (ev.type != EV_KEY || ev.code != KEY_VOLUMEDOWN) continue;
                    const int64_t evNow = nowMonotonicNs();
                    if (ev.value == 1) {
                        volDownHeld = true;
                        emergencyFiredForHold = false;
                        holdStartNs = evNow;
                        if (prevTapDownNs > 0 && (evNow - prevTapDownNs) <= kVolDoubleTapWindowNs) {
                            prevTapDownNs = 0;
                            if (volumeKeyControlEnabled_.load()) {
                                std::function<void()> cb;
                                {
                                    std::lock_guard<std::mutex> lock(callbackMutex_);
                                    cb = callbacks_.onVolumeKeyDoubleTap;
                                }
                                if (cb) cb();
                            }
                        } else {
                            prevTapDownNs = evNow;
                        }
                    } else if (ev.value == 0) {
                        if (volDownHeld && (evNow - holdStartNs) > kVolDoubleTapWindowNs) {
                            // Long press is not a tap
                            prevTapDownNs = 0;
                        }
                        volDownHeld = false;
                        emergencyFiredForHold = false;
                    }
                }
            }
        }

        // Check mandatory 3s hold emergency EVIOCGRAB rollback even if engine loop were wedged
        if (volDownHeld && !emergencyFiredForHold && (now - holdStartNs) >= kVolEmergencyHoldNs) {
            if (threadRunning_.load()) {
                emergencyFiredForHold = true;
                LOGW("Emergency rollback triggered: KEY_VOLUMEDOWN held >= 3.0s! Releasing EVIOCGRAB.");
                {
                    std::lock_guard<std::mutex> lock(stateMutex_);
                    cfgClickingRunning_ = false;
                    cfgRawMacroRunning_ = false;
                    cfgStopThreadRequested_ = true;
                    lastError_ = "Emergency rollback triggered via 3s Volume-Down hold";
                }
                if (physFd_ >= 0) {
                    ioctl(physFd_, EVIOCGRAB, 0);
                }
                selfCheckPending_.store(false);
                signalWake();

                std::function<void()> cb;
                {
                    std::lock_guard<std::mutex> lock(callbackMutex_);
                    cb = callbacks_.onEmergencyUngrab;
                }
                if (cb) cb();
            }
        }
    }

    for (int fd : fds) {
        close(fd);
    }
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

bool TouchMuxEngine::setupUhidDevice(const DeviceDesc& desc, int& outErrno) {
    uhidFd_ = open("/dev/uhid", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (uhidFd_ < 0) {
        outErrno = errno;
        lastError_ = formatErrno("open(/dev/uhid) failed", outErrno);
        return false;
    }

    const uint8_t xyBits = (desc.absX.minimum < 0 || desc.absX.maximum > 32767 ||
                            desc.absY.minimum < 0 || desc.absY.maximum > 32767)
                               ? 32
                               : 16;
    std::vector<uint8_t> rd = buildUhidTouchDescriptor(
        desc.absX.minimum,
        desc.absX.maximum,
        desc.absY.minimum,
        desc.absY.maximum,
        xyBits
    );

    if (rd.size() > HID_MAX_DESCRIPTOR_SIZE) {
        outErrno = EINVAL;
        lastError_ = "UHID report descriptor exceeded HID_MAX_DESCRIPTOR_SIZE";
        close(uhidFd_);
        uhidFd_ = -1;
        return false;
    }

    struct uhid_event ev {};
    ev.type = UHID_CREATE2;
    std::snprintf(reinterpret_cast<char*>(ev.u.create2.name),
                  sizeof(ev.u.create2.name),
                  "ZeroInterrupt_TouchMux_HID");
    std::snprintf(reinterpret_cast<char*>(ev.u.create2.phys),
                  sizeof(ev.u.create2.phys),
                  "uhid/touchmux");
    std::snprintf(reinterpret_cast<char*>(ev.u.create2.uniq),
                  sizeof(ev.u.create2.uniq),
                  "zi-mt-01");
    ev.u.create2.rd_size = static_cast<uint16_t>(rd.size());
    ev.u.create2.bus = BUS_VIRTUAL;
    ev.u.create2.vendor = desc.vendor != 0 ? desc.vendor : 0x18D1;
    ev.u.create2.product = desc.product != 0 ? desc.product : 0x4E12;
    ev.u.create2.version = 1;
    ev.u.create2.country = 0;
    std::memcpy(ev.u.create2.rd_data, rd.data(), rd.size());

    if (write(uhidFd_, &ev, sizeof(ev)) != static_cast<ssize_t>(sizeof(ev))) {
        outErrno = errno;
        lastError_ = formatErrno("write(UHID_CREATE2) failed", outErrno);
        close(uhidFd_);
        uhidFd_ = -1;
        return false;
    }

    // Actively service UHID_GET_REPORT (Feature Report Contact Count Maximum) across the 150ms settle window
    const int64_t settleDeadlineNs = nowMonotonicNs() + 150000000LL;
    struct pollfd pfd {uhidFd_, POLLIN, 0};
    while (true) {
        int64_t remainingMs = (settleDeadlineNs - nowMonotonicNs()) / 1000000LL;
        if (remainingMs <= 0) break;
        int rc = poll(&pfd, 1, static_cast<int>(std::min<int64_t>(remainingMs, 25)));
        if (rc > 0 && (pfd.revents & POLLIN)) {
            handleUhidEvents();
        }
    }
    handleUhidEvents();

    outErrno = 0;
    LOGI("Tier 2 UHID 10-finger touch digitizer created & settled (%zu-byte descriptor, xyBits=%u)",
         rd.size(), static_cast<unsigned>(xyBits));
    return true;
}

void TouchMuxEngine::handleUhidEvents() {
    if (uhidFd_ < 0) return;
    struct uhid_event ev {};
    while (true) {
        ssize_t n = read(uhidFd_, &ev, sizeof(ev));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;

        if (ev.type == UHID_GET_REPORT) {
            struct uhid_event reply {};
            reply.type = UHID_GET_REPORT_REPLY;
            reply.u.get_report_reply.id = ev.u.get_report.id;
            if (ev.u.get_report.rnum == 2 && ev.u.get_report.rtype == UHID_FEATURE_REPORT) {
                reply.u.get_report_reply.err = 0;
                reply.u.get_report_reply.size = 2;
                reply.u.get_report_reply.data[0] = 0x02;
                reply.u.get_report_reply.data[1] = static_cast<uint8_t>(kMaxUhidSlots);
                LOGI("UHID_GET_REPORT Feature rnum=2 -> Contact Count Max = %d", kMaxUhidSlots);
            } else {
                reply.u.get_report_reply.err = EIO;
                reply.u.get_report_reply.size = 0;
            }
            ssize_t ignored = write(uhidFd_, &reply, sizeof(reply));
            (void)ignored;
        }
    }
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
    if (uhidFd_ >= 0) {
        close(uhidFd_);
        uhidFd_ = -1;
    }
}

void TouchMuxEngine::signalWake() {
    if (wakeFd_ >= 0) {
        uint64_t val = 1;
        ssize_t ignored = write(wakeFd_, &val, sizeof(val));
        (void)ignored;
    }
}

int TouchMuxEngine::initAndGrab(int displayW,
                                int displayH,
                                int rotation,
                                int selfCheckTimeoutMs,
                                int preferredBackend) {
    stopRecording(true);
    ensureKeyWatchThread();

    if (threadRunning_.load()) {
        const MuxBackendMode curBackend = static_cast<MuxBackendMode>(activeBackend_.load());
        if (preferredBackend > 0 && static_cast<int32_t>(curBackend) != preferredBackend) {
            LOGI("Backend override requested (%d -> %d); cycling engine cleanly.",
                 static_cast<int>(curBackend), preferredBackend);
            shutdown();
        } else {
            {
                std::lock_guard<std::mutex> lock(stateMutex_);
                cfgDisplayW_ = std::max(1, displayW);
                cfgDisplayH_ = std::max(1, displayH);
                cfgRotation_ = rotation;
                if (selfCheckTimeoutMs > 0) {
                    cfgSelfCheckDeadlineNs_ =
                        nowMonotonicNs() + static_cast<int64_t>(selfCheckTimeoutMs) * 1000000LL;
                    selfCheckPending_.store(true);
                    selfCheckRolledBack_.store(false);
                    engineState_.store(static_cast<int32_t>(EngineState::SELF_CHECK_PENDING));
                }
            }
            signalWake();
            return 0;
        }
    }

    stopTouchTriggerWatchThread();

    if (workerThread_.joinable()) {
        workerThread_.join();
    }

    // Reclaim any stale :touchmux UserService left over from app updates, kills, or Shizuku restarts.
    cleanupOrphanProcesses();

    DeviceDesc desc;
    int findErr = 0;
    if (!findBestTouchscreen(desc, findErr)) {
        lastError_ = formatErrno("findBestTouchscreen failed", findErr);
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
        return -findErr;
    }

    physFd_ = open(desc.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (physFd_ < 0) {
        int err = errno;
        lastError_ = formatErrno("open physical touchscreen failed", err);
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
        return -err;
    }

    MuxBackendMode chosenBackend = MuxBackendMode::NONE;
    int lastTierErr = EACCES;

    auto tryUinput = [&]() -> bool {
        int err = 0;
        if (setupUinputDevice(desc, err)) {
            chosenBackend = MuxBackendMode::UINPUT;
            return true;
        }
        lastTierErr = err;
        return false;
    };

    auto tryUhid = [&]() -> bool {
        int err = 0;
        if (setupUhidDevice(desc, err)) {
            chosenBackend = MuxBackendMode::UHID;
            return true;
        }
        lastTierErr = err;
        return false;
    };

    auto hasHybridCb = [&]() -> bool {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        return static_cast<bool>(callbacks_.onHybridMotionFrame);
    };

    switch (static_cast<BackendPref>(preferredBackend)) {
        case BackendPref::UINPUT:
            if (!tryUinput()) {
                closeAllFds();
                engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
                activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
                return -lastTierErr;
            }
            break;
        case BackendPref::UHID:
            if (!tryUhid()) {
                closeAllFds();
                engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
                activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
                return -lastTierErr;
            }
            break;
        case BackendPref::HYBRID:
            if (!hasHybridCb()) {
                lastError_ = "Hybrid callback not registered";
                closeAllFds();
                engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
                activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
                return -EINVAL;
            }
            chosenBackend = MuxBackendMode::HYBRID;
            break;
        case BackendPref::AUTO:
        default:
            if (!tryUinput()) {
                LOGW("Tier 1 uinput unavailable (%s); trying Tier 2 UHID...", lastError_.c_str());
                if (!tryUhid()) {
                    LOGW("Tier 2 UHID unavailable (%s); checking Tier 3 Hybrid evdev+inject...",
                         lastError_.c_str());
                    if (hasHybridCb()) {
                        chosenBackend = MuxBackendMode::HYBRID;
                        lastError_.clear();
                    } else {
                        closeAllFds();
                        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
                        activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
                        return -lastTierErr;
                    }
                }
            }
            break;
    }

    waitForPhysicalTouchIdle(physFd_, desc, 800);

    if (ioctl(physFd_, EVIOCGRAB, 1) < 0) {
        int err = errno;
        if (err == EBUSY) {
            LOGW("EVIOCGRAB hit EBUSY on %s; cleaning orphans and retrying once...", desc.path.c_str());
            cleanupOrphanProcesses();
            usleep(150000);
            if (ioctl(physFd_, EVIOCGRAB, 1) == 0) {
                err = 0;
            } else {
                err = errno;
            }
        }
        if (err != 0) {
            lastError_ = formatErrno("ioctl(EVIOCGRAB, 1) failed", err);
            if (uinputFd_ >= 0) ioctl(uinputFd_, UI_DEV_DESTROY);
            if (uhidFd_ >= 0) {
                struct uhid_event d {};
                d.type = UHID_DESTROY;
                ssize_t ignored = write(uhidFd_, &d, sizeof(d));
                (void)ignored;
            }
            closeAllFds();
            engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
            activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
            return -err;
        }
    }

    struct input_event postGrabDrain[64];
    while (read(physFd_, postGrabDrain, sizeof(postGrabDrain)) > 0) {
    }

    epollFd_ = epoll_create1(EPOLL_CLOEXEC);
    timerFd_ = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    wakeFd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (epollFd_ < 0 || timerFd_ < 0 || wakeFd_ < 0) {
        int err = errno;
        lastError_ = formatErrno("epoll/timerfd/eventfd creation failed", err);
        ioctl(physFd_, EVIOCGRAB, 0);
        if (uinputFd_ >= 0) ioctl(uinputFd_, UI_DEV_DESTROY);
        closeAllFds();
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
        return -err;
    }

    auto addEpoll = [this](int fd) -> bool {
        struct epoll_event ev {};
        ev.events = EPOLLIN;
        ev.data.fd = fd;
        return epoll_ctl(epollFd_, EPOLL_CTL_ADD, fd, &ev) == 0;
    };

    bool epollOk = addEpoll(physFd_) && addEpoll(timerFd_) && addEpoll(wakeFd_);
    if (epollOk && uhidFd_ >= 0) {
        epollOk = addEpoll(uhidFd_);
    }
    if (!epollOk) {
        int err = errno;
        lastError_ = formatErrno("epoll_ctl ADD failed", err);
        ioctl(physFd_, EVIOCGRAB, 0);
        if (uinputFd_ >= 0) ioctl(uinputFd_, UI_DEV_DESTROY);
        closeAllFds();
        engineState_.store(static_cast<int32_t>(EngineState::ERROR_STATE));
        activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
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
        cfgExecutionMode_ = ExecutionMode::CONCURRENT;
        cfgPresetLoopCount_ = 0;
        cfgTargets_.clear();
        cfgRawMacroRunning_ = false;
        cfgRawMacroEvents_.clear();
        cfgRawMacroTotalDurationMs_ = 0;
        cfgRawMacroLoopCount_ = 0;
        cfgRpcCommands_.clear();
        cfgCancelSelfCheckRequested_ = false;
        cfgStopThreadRequested_ = false;
        if (selfCheckTimeoutMs > 0) {
            cfgSelfCheckDeadlineNs_ =
                nowMonotonicNs() + static_cast<int64_t>(selfCheckTimeoutMs) * 1000000LL;
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

    workerBackend_ = chosenBackend;
    activeBackend_.store(static_cast<int32_t>(chosenBackend));

    physCurrentSlot_ = 0;
    if (desc.isTypeB) {
        struct input_absinfo absSlotNow {};
        if (ioctl(physFd_, EVIOCGABS(ABS_MT_SLOT), &absSlotNow) == 0) {
            physCurrentSlot_ = std::clamp(absSlotNow.value, 0, kMaxPhysSlots - 1);
        }
    }
    const int32_t initMidX = (desc.absX.minimum + desc.absX.maximum) / 2;
    const int32_t initMidY = (desc.absY.minimum + desc.absY.maximum) / 2;
    for (int s = 0; s < kMaxPhysSlots; ++s) {
        engTriggerSlots_[s] = TriggerSlotTrack{};
        engPhysPendingDown_[s] = false;
        engPhysPendingUp_[s] = false;
        engPhysRawX_[s] = initMidX;
        engPhysRawY_[s] = initMidY;
    }
    activeTtMode_ = TargetTriggerMode::OFF;
    activeTtZones_.clear();
    activeTtVersion_ = 0;
    engLastTapTriggerNs_ = 0;
    std::fill(std::begin(physToMuxSlot_), std::end(physToMuxSlot_), -1);
    for (auto& s : muxSlots_) {
        s = MuxSlotState{};
    }
    {
        std::lock_guard<std::mutex> sl(slotSnapshotMutex_);
        std::fill(std::begin(snapshotSlots_), std::end(snapshotSlots_), 0);
    }
    std::fill(std::begin(lastEmittedSlotSnapshot_), std::end(lastEmittedSlotSnapshot_), 0);
    lastSlotSnapshotNotifyNs_ = 0;

    uinputCurrentSlot_ = -1;
    btnTouchSent_ = false;
    uhidDirty_ = false;
    uhidHadActiveContacts_ = false;
    hybridDirty_ = false;
    hybridReported_.clear();
    hybridDownTimeMs_ = 0;
    typeACurrent_ = TypeAContact{};
    typeAFrameContacts_.clear();
    typeAActiveMuxSlots_.clear();
    virtualTargets_.clear();
    stages_.clear();
    rawMacroFingerSlots_.clear();
    rpcGestures_.clear();
    pendingWrites_.clear();
    activePresetLoopCount_ = 0;
    seqCompletedLoops_ = 0;
    activeStageIdx_ = 0;
    activeRawMacroRunning_ = false;
    activeRawMacroEvents_.clear();
    activeRawMacroTotalDurationMs_ = 0;
    activeRawMacroLoopCount_ = 0;
    rawMacroCursor_ = 0;
    rawMacroCompletedLoops_ = 0;
    rawMacroLoopStartNs_ = 0;
    rawMacroNextDeadlineNs_ = 0;

    threadRunning_.store(true);
    workerThread_ = std::thread(&TouchMuxEngine::engineLoop, this);
    signalWake();
    LOGI("TouchMux initialized [backend=%d] & grabbed %s (%s), selfCheck=%dms",
         static_cast<int>(chosenBackend), desc.path.c_str(), desc.name.c_str(), selfCheckTimeoutMs);
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
            cfgRawMacroRunning_ = false;
        }
        signalWake();
    }
    if (workerThread_.joinable()) {
        workerThread_.join();
    }
    selfCheckPending_.store(false);
    activeBackend_.store(static_cast<int32_t>(MuxBackendMode::NONE));
    engineState_.store(static_cast<int32_t>(EngineState::IDLE));
    syncTouchTriggerWatcherState();
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
                                          float jitterRadiusPx,
                                          int executionMode,
                                          int presetLoopCount,
                                          bool forceReset) {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgRawMacroRunning_ = false;
        cfgTargets_ = targets;
        cfgClickingRunning_ = running;
        cfgDisplayW_ = std::max(1, displayW);
        cfgDisplayH_ = std::max(1, displayH);
        cfgRotation_ = rotation;
        cfgJitterRadiusPx_ = std::clamp(jitterRadiusPx, 0.0f, 20.0f);
        cfgExecutionMode_ = (executionMode == static_cast<int>(ExecutionMode::SEQUENTIAL))
                                ? ExecutionMode::SEQUENTIAL
                                : ExecutionMode::CONCURRENT;
        cfgPresetLoopCount_ = std::clamp(presetLoopCount, 0, 9999);
        if (forceReset) {
            ++cfgForceResetSeq_;
        }
    }
    signalWake();
}

void TouchMuxEngine::setRawMacroAndRunning(const std::vector<NativeRawMacroEvent>& events,
                                           int64_t totalDurationMs,
                                           int32_t loopCount,
                                           bool running,
                                           int displayW,
                                           int displayH,
                                           int rotation) {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgClickingRunning_ = false;
        cfgRawMacroEvents_ = events;
        cfgRawMacroTotalDurationMs_ = std::max<int64_t>(8, totalDurationMs);
        cfgRawMacroLoopCount_ = std::clamp(loopCount, 0, 9999);
        cfgRawMacroRunning_ = running && !events.empty();
        cfgDisplayW_ = std::max(1, displayW);
        cfgDisplayH_ = std::max(1, displayH);
        cfgRotation_ = rotation;
        ++cfgRawMacroSeq_;
    }
    signalWake();
}

void TouchMuxEngine::stopClicking() {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgClickingRunning_ = false;
        cfgRawMacroRunning_ = false;
        cfgRpcCommands_.push_back(RpcCommand{RpcCommand::Kind::CLEAR_ALL, 0, 0, 0, 0, 0, 0});
    }
    signalWake();
}

void TouchMuxEngine::enqueueRpcTap(float x, float y, int64_t durationMs) {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        RpcCommand cmd;
        cmd.kind = RpcCommand::Kind::TAP;
        cmd.x1 = x;
        cmd.y1 = y;
        cmd.durationMs = std::clamp<int64_t>(durationMs, 8, 10000);
        cfgRpcCommands_.push_back(cmd);
    }
    signalWake();
}

void TouchMuxEngine::enqueueRpcSwipe(float x1, float y1, float x2, float y2, int64_t durationMs) {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        RpcCommand cmd;
        cmd.kind = RpcCommand::Kind::SWIPE;
        cmd.x1 = x1;
        cmd.y1 = y1;
        cmd.x2 = x2;
        cmd.y2 = y2;
        cmd.durationMs = std::clamp<int64_t>(durationMs, 8, 15000);
        cfgRpcCommands_.push_back(cmd);
    }
    signalWake();
}

void TouchMuxEngine::setRpcHold(int32_t holdId, bool down, float x, float y) {
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        RpcCommand cmd;
        cmd.kind = down ? RpcCommand::Kind::HOLD_DOWN : RpcCommand::Kind::HOLD_UP;
        cmd.id = holdId;
        cmd.x1 = x;
        cmd.y1 = y;
        cfgRpcCommands_.push_back(cmd);
    }
    signalWake();
}

EngineState TouchMuxEngine::getState() const {
    return static_cast<EngineState>(engineState_.load());
}

MuxBackendMode TouchMuxEngine::getBackendMode() const {
    return static_cast<MuxBackendMode>(activeBackend_.load());
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

void TouchMuxEngine::getSlotStates(int32_t outSlots[kMaxUinputSlots]) const {
    std::lock_guard<std::mutex> lock(slotSnapshotMutex_);
    std::memcpy(outSlots, snapshotSlots_, sizeof(int32_t) * kMaxUinputSlots);
}

int32_t TouchMuxEngine::nextTrackingId() {
    trackingCounter_++;
    if (trackingCounter_ > 65000) {
        trackingCounter_ = 100;
    }
    return trackingCounter_;
}

int TouchMuxEngine::countActiveSlots() const {
    int cnt = 0;
    for (const auto& s : muxSlots_) {
        if (s.owner != SlotOwner::FREE) cnt++;
    }
    return cnt;
}

int TouchMuxEngine::allocatePhysicalSlot(int physSlot) {
    const int maxSlots = (workerBackend_ == MuxBackendMode::UHID) ? kMaxUhidSlots : kMaxUinputSlots;
    if (physSlot >= 0 && physSlot < maxSlots && muxSlots_[physSlot].owner == SlotOwner::FREE) {
        muxSlots_[physSlot] = MuxSlotState{};
        muxSlots_[physSlot].owner = SlotOwner::PHYSICAL;
        muxSlots_[physSlot].trackingId = nextTrackingId();
        muxSlots_[physSlot].ownerSlotOrTargetId = physSlot;
        muxSlots_[physSlot].pressure = deviceDesc_.hasPressure ? std::max(1, deviceDesc_.absPressure.maximum / 6) : 64;
        muxSlots_[physSlot].major = deviceDesc_.hasTouchMajor ? std::max(1, deviceDesc_.absTouchMajor.maximum / 6) : 8;
        return physSlot;
    }
    for (int i = 0; i < maxSlots; ++i) {
        if (muxSlots_[i].owner == SlotOwner::FREE) {
            muxSlots_[i] = MuxSlotState{};
            muxSlots_[i].owner = SlotOwner::PHYSICAL;
            muxSlots_[i].trackingId = nextTrackingId();
            muxSlots_[i].ownerSlotOrTargetId = physSlot;
            muxSlots_[i].pressure = deviceDesc_.hasPressure ? std::max(1, deviceDesc_.absPressure.maximum / 6) : 64;
            muxSlots_[i].major = deviceDesc_.hasTouchMajor ? std::max(1, deviceDesc_.absTouchMajor.maximum / 6) : 8;
            return i;
        }
    }
    return -1;
}

int TouchMuxEngine::allocateVirtualSlot(int32_t targetId) {
    const int maxSlots = (workerBackend_ == MuxBackendMode::UHID) ? kMaxUhidSlots : kMaxUinputSlots;
    // Allocate from high slots downwards within backend limit to avoid physical slot indices (0..N)
    for (int i = maxSlots - 1; i >= 0; --i) {
        if (muxSlots_[i].owner == SlotOwner::FREE) {
            muxSlots_[i] = MuxSlotState{};
            muxSlots_[i].owner = SlotOwner::VIRTUAL;
            muxSlots_[i].trackingId = nextTrackingId();
            muxSlots_[i].ownerSlotOrTargetId = targetId;
            return i;
        }
    }
    return -1;
}

void TouchMuxEngine::releaseSlot(int slot) {
    if (slot >= 0 && slot < kMaxUinputSlots) {
        muxSlots_[slot] = MuxSlotState{};
    }
}

void TouchMuxEngine::queueEvent(uint16_t type, uint16_t code, int32_t value) {
    if (workerBackend_ != MuxBackendMode::UINPUT) {
        return;
    }
    struct input_event ev {};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    pendingWrites_.push_back(ev);
}

void TouchMuxEngine::ensureUinputSlot(int slot) {
    if (workerBackend_ != MuxBackendMode::UINPUT) {
        return;
    }
    if (uinputCurrentSlot_ != slot) {
        queueEvent(EV_ABS, ABS_MT_SLOT, slot);
        uinputCurrentSlot_ = slot;
    }
}

void TouchMuxEngine::emitSlotDown(int slot,
                                  int32_t rx,
                                  int32_t ry,
                                  int32_t major,
                                  int32_t pressure,
                                  bool hasExactLogical,
                                  float lx,
                                  float ly) {
    if (slot < 0 || slot >= kMaxUinputSlots) return;
    muxSlots_[slot].rawX = rx;
    muxSlots_[slot].rawY = ry;
    muxSlots_[slot].major = major;
    muxSlots_[slot].pressure = pressure;
    muxSlots_[slot].hasExactLogical = hasExactLogical;
    muxSlots_[slot].exactLogicalX = lx;
    muxSlots_[slot].exactLogicalY = ly;

    uhidDirty_ = true;
    hybridDirty_ = true;

    if (workerBackend_ == MuxBackendMode::UINPUT) {
        ensureUinputSlot(slot);
        queueEvent(EV_ABS, ABS_MT_TRACKING_ID, muxSlots_[slot].trackingId);
        queueEvent(EV_ABS, ABS_MT_POSITION_X, rx);
        queueEvent(EV_ABS, ABS_MT_POSITION_Y, ry);
        if (deviceDesc_.hasTouchMajor) {
            queueEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, major);
        }
        if (deviceDesc_.hasPressure) {
            queueEvent(EV_ABS, ABS_MT_PRESSURE, pressure);
        }
    }
}

void TouchMuxEngine::emitSlotMove(int slot,
                                  int32_t rx,
                                  int32_t ry,
                                  int32_t major,
                                  int32_t pressure,
                                  bool hasExactLogical,
                                  float lx,
                                  float ly) {
    if (slot < 0 || slot >= kMaxUinputSlots) return;
    muxSlots_[slot].rawX = rx;
    muxSlots_[slot].rawY = ry;
    muxSlots_[slot].major = major;
    muxSlots_[slot].pressure = pressure;
    muxSlots_[slot].hasExactLogical = hasExactLogical;
    muxSlots_[slot].exactLogicalX = lx;
    muxSlots_[slot].exactLogicalY = ly;

    uhidDirty_ = true;
    hybridDirty_ = true;

    if (workerBackend_ == MuxBackendMode::UINPUT) {
        ensureUinputSlot(slot);
        queueEvent(EV_ABS, ABS_MT_POSITION_X, rx);
        queueEvent(EV_ABS, ABS_MT_POSITION_Y, ry);
        if (deviceDesc_.hasTouchMajor) {
            queueEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, major);
        }
        if (deviceDesc_.hasPressure) {
            queueEvent(EV_ABS, ABS_MT_PRESSURE, pressure);
        }
    }
}

void TouchMuxEngine::emitSlotUp(int slot) {
    if (slot < 0 || slot >= kMaxUinputSlots) return;
    if (workerBackend_ == MuxBackendMode::UINPUT) {
        ensureUinputSlot(slot);
        queueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
    }
    releaseSlot(slot);
    uhidDirty_ = true;
    hybridDirty_ = true;
}

void TouchMuxEngine::publishSlotSnapshotIfChanged(int64_t now) {
    int32_t curr[kMaxUinputSlots];
    bool allFree = true;
    for (int i = 0; i < kMaxUinputSlots; ++i) {
        curr[i] = static_cast<int32_t>(muxSlots_[i].owner);
        if (curr[i] != 0) allFree = false;
    }
    {
        std::lock_guard<std::mutex> sl(slotSnapshotMutex_);
        std::memcpy(snapshotSlots_, curr, sizeof(curr));
    }

    if (std::memcmp(curr, lastEmittedSlotSnapshot_, sizeof(curr)) == 0) {
        return;
    }
    if (!allFree && (now - lastSlotSnapshotNotifyNs_) < 50000000LL) {
        return;
    }

    std::memcpy(lastEmittedSlotSnapshot_, curr, sizeof(curr));
    lastSlotSnapshotNotifyNs_ = now;

    std::function<void(const int32_t[kMaxUinputSlots])> cb;
    {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        cb = callbacks_.onSlotStatesChanged;
    }
    if (cb) {
        cb(curr);
    }
}

void TouchMuxEngine::flushUhidFrame() {
    if (uhidFd_ < 0 || !uhidDirty_) return;
    uhidDirty_ = false;

    const bool xy32 = (deviceDesc_.absX.minimum < 0 || deviceDesc_.absX.maximum > 32767 ||
                       deviceDesc_.absY.minimum < 0 || deviceDesc_.absY.maximum > 32767);
    const size_t xyBytes = xy32 ? 4 : 2;
    const size_t contactStride = 3 + 2 * xyBytes;
    const size_t reportLen = 1 + static_cast<size_t>(kMaxUhidSlots) * contactStride + 1;

    struct uhid_event ev {};
    ev.type = UHID_INPUT2;
    ev.u.input2.size = static_cast<uint16_t>(reportLen);
    uint8_t* data = ev.u.input2.data;
    std::memset(data, 0, reportLen);
    data[0] = 0x01;  // Report ID 1

    int activeCount = 0;
    for (int s = 0; s < kMaxUhidSlots; ++s) {
        uint8_t* slotPtr = data + 1 + static_cast<size_t>(s) * contactStride;
        slotPtr[1] = static_cast<uint8_t>(s & 0x0F);  // Fixed ContactID = s (0..9)
        if (muxSlots_[s].owner == SlotOwner::FREE) {
            slotPtr[0] = 0x00;  // Tip Switch = 0 (released/idle)
            continue;
        }
        slotPtr[0] = 0x01;  // Tip Switch = 1

        int32_t rx = std::clamp(muxSlots_[s].rawX, deviceDesc_.absX.minimum, deviceDesc_.absX.maximum);
        int32_t ry = std::clamp(muxSlots_[s].rawY, deviceDesc_.absY.minimum, deviceDesc_.absY.maximum);
        if (xy32) {
            std::memcpy(slotPtr + 2, &rx, 4);
            std::memcpy(slotPtr + 6, &ry, 4);
        } else {
            uint16_t rx16 = static_cast<uint16_t>(rx);
            uint16_t ry16 = static_cast<uint16_t>(ry);
            std::memcpy(slotPtr + 2, &rx16, 2);
            std::memcpy(slotPtr + 4, &ry16, 2);
        }

        uint8_t pByte = 40;
        if (deviceDesc_.hasPressure) {
            int64_t pSpan = static_cast<int64_t>(deviceDesc_.absPressure.maximum) - deviceDesc_.absPressure.minimum;
            if (pSpan > 0) {
                double normP = static_cast<double>(muxSlots_[s].pressure - deviceDesc_.absPressure.minimum) /
                               static_cast<double>(pSpan);
                pByte = static_cast<uint8_t>(std::clamp(std::llround(normP * 255.0), 1LL, 255LL));
            }
        }
        slotPtr[2 + 2 * xyBytes] = pByte;
        activeCount++;
    }

    if (activeCount == 0 && !uhidHadActiveContacts_) {
        return;
    }
    uhidHadActiveContacts_ = (activeCount > 0);
    data[1 + static_cast<size_t>(kMaxUhidSlots) * contactStride] = static_cast<uint8_t>(activeCount);

    ssize_t ignored = write(uhidFd_, &ev, sizeof(ev));
    (void)ignored;
}

void TouchMuxEngine::emitHybridAction(int64_t downTimeMs, int64_t eventTimeMs, int32_t action) {
    const int32_t count = static_cast<int32_t>(std::min<size_t>(hybridReported_.size(), kMaxUinputSlots));
    if (count <= 0) return;

    for (int32_t i = 0; i < count; ++i) {
        const auto& p = hybridReported_[i];
        hybridTmpIds_[i] = p.muxSlot;
        hybridTmpXs_[i] = p.x;
        hybridTmpYs_[i] = p.y;
        hybridTmpPressures_[i] = p.pressure;
        hybridTmpSizes_[i] = p.size;
    }

    std::lock_guard<std::mutex> lock(callbackMutex_);
    if (callbacks_.onHybridMotionFrame) {
        callbacks_.onHybridMotionFrame(
            downTimeMs,
            eventTimeMs,
            action,
            count,
            hybridTmpIds_,
            hybridTmpXs_,
            hybridTmpYs_,
            hybridTmpPressures_,
            hybridTmpSizes_
        );
    }
}

void TouchMuxEngine::flushHybridFrame() {
    if (!hybridDirty_) return;
    hybridDirty_ = false;

    const int64_t eventTimeMs = nowMonotonicNs() / 1000000LL;

    auto calcNormalizedPressure = [this](int slot) -> float {
        if (!deviceDesc_.hasPressure) return 0.16f;
        int64_t span = static_cast<int64_t>(deviceDesc_.absPressure.maximum) - deviceDesc_.absPressure.minimum;
        if (span <= 0) return 0.16f;
        double v = static_cast<double>(muxSlots_[slot].pressure - deviceDesc_.absPressure.minimum) /
                   static_cast<double>(span);
        return static_cast<float>(std::clamp(v, 0.05, 1.0));
    };

    auto calcNormalizedSize = [this](int slot) -> float {
        if (!deviceDesc_.hasTouchMajor) return 0.16f;
        int64_t span = static_cast<int64_t>(deviceDesc_.absTouchMajor.maximum) - deviceDesc_.absTouchMajor.minimum;
        if (span <= 0) return 0.16f;
        double v = static_cast<double>(muxSlots_[slot].major - deviceDesc_.absTouchMajor.minimum) /
                   static_cast<double>(span);
        return static_cast<float>(std::clamp(v, 0.04, 1.0));
    };

    auto slotToCoords = [this](int slot, float& outLx, float& outLy) {
        if (muxSlots_[slot].hasExactLogical) {
            outLx = muxSlots_[slot].exactLogicalX;
            outLy = muxSlots_[slot].exactLogicalY;
        } else {
            rawToLogical(muxSlots_[slot].rawX, muxSlots_[slot].rawY, outLx, outLy);
        }
    };

    // Step 1: Lift any previously reported slot that is now FREE or changed trackingId
    for (size_t idx = 0; idx < hybridReported_.size();) {
        const int s = hybridReported_[idx].muxSlot;
        const bool stillSame = (s >= 0 && s < kMaxUinputSlots &&
                                muxSlots_[s].owner != SlotOwner::FREE &&
                                muxSlots_[s].trackingId == hybridReported_[idx].trackingId);
        if (!stillSame) {
            if (hybridReported_.size() == 1) {
                emitHybridAction(hybridDownTimeMs_, eventTimeMs, kAndroidActionUp);
                hybridReported_.clear();
                hybridDownTimeMs_ = 0;
            } else {
                const int32_t ptrIdx = static_cast<int32_t>(idx);
                const int32_t action = kAndroidActionPointerUp | (ptrIdx << kAndroidActionPointerIndexShift);
                emitHybridAction(hybridDownTimeMs_, eventTimeMs, action);
                hybridReported_.erase(hybridReported_.begin() + static_cast<std::ptrdiff_t>(idx));
            }
        } else {
            ++idx;
        }
    }

    // Step 2: Detect newly active slots not yet in hybridReported_
    for (int s = 0; s < kMaxUinputSlots; ++s) {
        if (muxSlots_[s].owner == SlotOwner::FREE) continue;
        bool alreadyDown = false;
        for (const auto& rp : hybridReported_) {
            if (rp.muxSlot == s) {
                alreadyDown = true;
                break;
            }
        }
        if (alreadyDown) continue;
        if (hybridReported_.size() >= static_cast<size_t>(kMaxUinputSlots)) continue;

        HybridPointerTrack track;
        track.muxSlot = s;
        track.trackingId = muxSlots_[s].trackingId;
        slotToCoords(s, track.x, track.y);
        track.pressure = calcNormalizedPressure(s);
        track.size = calcNormalizedSize(s);
        hybridReported_.push_back(track);

        if (hybridReported_.size() == 1) {
            hybridDownTimeMs_ = eventTimeMs;
            emitHybridAction(hybridDownTimeMs_, eventTimeMs, kAndroidActionDown);
        } else {
            const int32_t ptrIdx = static_cast<int32_t>(hybridReported_.size()) - 1;
            const int32_t action = kAndroidActionPointerDown | (ptrIdx << kAndroidActionPointerIndexShift);
            emitHybridAction(hybridDownTimeMs_, eventTimeMs, action);
        }
    }

    // Step 3: Emit MOVE if any currently reported active pointer moved
    if (!hybridReported_.empty()) {
        bool anyMoved = false;
        for (auto& rp : hybridReported_) {
            float nx = 0.0f;
            float ny = 0.0f;
            slotToCoords(rp.muxSlot, nx, ny);
            float np = calcNormalizedPressure(rp.muxSlot);
            float ns = calcNormalizedSize(rp.muxSlot);
            if (std::fabs(nx - rp.x) > 0.05f || std::fabs(ny - rp.y) > 0.05f ||
                std::fabs(np - rp.pressure) > 0.02f) {
                rp.x = nx;
                rp.y = ny;
                rp.pressure = np;
                rp.size = ns;
                anyMoved = true;
            }
        }
        if (anyMoved) {
            emitHybridAction(hybridDownTimeMs_, eventTimeMs, kAndroidActionMove);
        }
    }
}

void TouchMuxEngine::emitSyncAndFlush() {
    publishSlotSnapshotIfChanged(nowMonotonicNs());

    if (workerBackend_ == MuxBackendMode::UHID) {
        pendingWrites_.clear();
        flushUhidFrame();
        return;
    }

    if (workerBackend_ == MuxBackendMode::HYBRID) {
        pendingWrites_.clear();
        flushHybridFrame();
        return;
    }

    // Tier 1: uinput incremental EV_SYN frame
    if (pendingWrites_.empty()) {
        return;
    }
    const bool anyActive = countActiveSlots() > 0;
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

void TouchMuxEngine::releaseCarriedHoldSlots() {
    bool changed = false;
    for (const auto& kv : carriedHoldSlots_) {
        int slot = kv.second;
        if (slot >= 0 && slot < kMaxUinputSlots && muxSlots_[slot].owner == SlotOwner::VIRTUAL) {
            emitSlotUp(slot);
            changed = true;
        }
    }
    carriedHoldSlots_.clear();
    if (changed) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::releaseAllVirtualTargetSlots() {
    bool changed = false;
    for (auto& vt : virtualTargets_) {
        if (vt.isPressed && vt.muxSlot >= 0) {
            emitSlotUp(vt.muxSlot);
            vt.muxSlot = -1;
            vt.isPressed = false;
            vt.holdLockedDown = false;
            vt.swipeFinalReached = false;
            changed = true;
        }
    }
    for (auto& st : stages_) {
        for (auto& vt : st.targets) {
            if (vt.isPressed && vt.muxSlot >= 0) {
                emitSlotUp(vt.muxSlot);
                vt.muxSlot = -1;
                vt.isPressed = false;
                vt.holdLockedDown = false;
                vt.swipeFinalReached = false;
                changed = true;
            }
        }
    }
    for (const auto& kv : carriedHoldSlots_) {
        int slot = kv.second;
        if (slot >= 0 && slot < kMaxUinputSlots && muxSlots_[slot].owner == SlotOwner::VIRTUAL) {
            emitSlotUp(slot);
            changed = true;
        }
    }
    carriedHoldSlots_.clear();
    // Also release any stray VIRTUAL target slot (> -1000 targetId belongs to targets, <= -1000 is RPC)
    for (int i = 0; i < kMaxUinputSlots; ++i) {
        if (muxSlots_[i].owner == SlotOwner::VIRTUAL && muxSlots_[i].ownerSlotOrTargetId > -1000) {
            emitSlotUp(i);
            changed = true;
        }
    }
    if (changed) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::releaseAllRawMacroSlots() {
    bool changed = false;
    for (const auto& kv : rawMacroFingerSlots_) {
        int slot = kv.second.muxSlot;
        if (slot >= 0 && slot < kMaxUinputSlots && muxSlots_[slot].owner == SlotOwner::VIRTUAL) {
            emitSlotUp(slot);
            changed = true;
        }
    }
    rawMacroFingerSlots_.clear();
    if (changed) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::releaseAllRpcHoldSlots() {
    bool changed = false;
    for (auto& rg : rpcGestures_) {
        if (rg.muxSlot >= 0) {
            emitSlotUp(rg.muxSlot);
            changed = true;
        }
    }
    rpcGestures_.clear();
    if (changed) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::forceReleaseAllSlotsAndSync() {
    bool changed = false;
    for (int i = 0; i < kMaxUinputSlots; ++i) {
        if (muxSlots_[i].owner != SlotOwner::FREE) {
            emitSlotUp(i);
            changed = true;
        }
    }
    std::fill(std::begin(physToMuxSlot_), std::end(physToMuxSlot_), -1);
    typeAActiveMuxSlots_.clear();
    for (auto& vt : virtualTargets_) {
        vt.isPressed = false;
        vt.holdLockedDown = false;
        vt.swipeFinalReached = false;
        vt.muxSlot = -1;
    }
    for (auto& st : stages_) {
        st.stageDeadlineNs = 0;
        st.inGap = false;
        for (auto& vt : st.targets) {
            vt.isPressed = false;
            vt.holdLockedDown = false;
            vt.swipeFinalReached = false;
            vt.muxSlot = -1;
        }
    }
    rawMacroFingerSlots_.clear();
    activeRawMacroRunning_ = false;
    rawMacroNextDeadlineNs_ = 0;
    rpcGestures_.clear();
    carriedHoldSlots_.clear();
    if (changed || btnTouchSent_ || uhidHadActiveContacts_ || !hybridReported_.empty()) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::finishClickingRun() {
    releaseAllRawMacroSlots();
    releaseAllVirtualTargetSlots();
    activeClickingRunning_ = false;
    activeRawMacroRunning_ = false;
    activeStageIdx_ = 0;
    seqCompletedLoops_ = 0;
    rawMacroCursor_ = 0;
    rawMacroCompletedLoops_ = 0;
    rawMacroNextDeadlineNs_ = 0;
    for (auto& vt : virtualTargets_) {
        vt.completedCycles = 0;
        vt.finishedRounds = false;
    }
    for (auto& st : stages_) {
        st.completedRepeats = 0;
        st.stageDeadlineNs = 0;
        st.startDelayUntilNs = 0;
        st.inGap = false;
        st.gapDeadlineNs = 0;
        for (auto& vt : st.targets) {
            vt.completedCycles = 0;
            vt.finishedRounds = false;
        }
    }
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        cfgClickingRunning_ = false;
        cfgRawMacroRunning_ = false;
    }
    std::function<void()> cb;
    {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        cb = callbacks_.onClickingCompleted;
    }
    if (cb) {
        cb();
    }
    LOGI("TouchMux preset run completed automatically.");
}

void TouchMuxEngine::logicalToRaw(float lx, float ly, int32_t& outRx, int32_t& outRy) const {
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

void TouchMuxEngine::rawToLogicalDesc(const DeviceDesc& desc,
                                      int dispW,
                                      int dispH,
                                      int rotation,
                                      int32_t rx,
                                      int32_t ry,
                                      float& outLx,
                                      float& outLy) {
    const int64_t spanX = std::max<int64_t>(1, static_cast<int64_t>(desc.absX.maximum) - desc.absX.minimum);
    const int64_t spanY = std::max<int64_t>(1, static_cast<int64_t>(desc.absY.maximum) - desc.absY.minimum);
    const float uRaw = std::clamp(static_cast<float>(rx - desc.absX.minimum) / static_cast<float>(spanX), 0.0f, 1.0f);
    const float vRaw = std::clamp(static_cast<float>(ry - desc.absY.minimum) / static_cast<float>(spanY), 0.0f, 1.0f);

    float u = uRaw;
    float v = vRaw;
    switch (rotation) {
        case 1:  // Inverse of ROTATION_90 (uRaw = 1 - v, vRaw = u)
            u = vRaw;
            v = 1.0f - uRaw;
            break;
        case 2:  // Inverse of ROTATION_180 (uRaw = 1 - u, vRaw = 1 - v)
            u = 1.0f - uRaw;
            v = 1.0f - vRaw;
            break;
        case 3:  // Inverse of ROTATION_270 (uRaw = v, vRaw = 1 - u)
            u = 1.0f - vRaw;
            v = uRaw;
            break;
        default: // ROTATION_0
            u = uRaw;
            v = vRaw;
            break;
    }

    const float maxDispX = static_cast<float>(std::max(1, dispW - 1));
    const float maxDispY = static_cast<float>(std::max(1, dispH - 1));
    outLx = std::clamp(u * maxDispX, 0.0f, maxDispX);
    outLy = std::clamp(v * maxDispY, 0.0f, maxDispY);
}

void TouchMuxEngine::rawToLogical(int32_t rx, int32_t ry, float& outLx, float& outLy) const {
    rawToLogicalDesc(deviceDesc_, activeDisplayW_, activeDisplayH_, activeRotation_, rx, ry, outLx, outLy);
}

int32_t TouchMuxEngine::sampleAbsRatio(const struct input_absinfo& abs,
                                         double meanRatio,
                                         double spreadHalfRatio,
                                         int32_t fallbackVal) {
    const int64_t span = static_cast<int64_t>(abs.maximum) - static_cast<int64_t>(abs.minimum);
    if (span <= 0) return fallbackVal;
    std::normal_distribution<double> pctDist(meanRatio, spreadHalfRatio / 2.5);
    double pct = std::clamp(pctDist(rng_), meanRatio - spreadHalfRatio, meanRatio + spreadHalfRatio);
    int64_t val = static_cast<int64_t>(abs.minimum) + std::llround(static_cast<double>(span) * pct);
    return static_cast<int32_t>(std::clamp<int64_t>(val, abs.minimum, abs.maximum));
}

int32_t TouchMuxEngine::sampleTouchMajor() {
    if (!deviceDesc_.hasTouchMajor) return 8;
    return sampleAbsRatio(deviceDesc_.absTouchMajor, 0.16, 0.03, 8);
}

int32_t TouchMuxEngine::samplePressure() {
    if (!deviceDesc_.hasPressure) return 64;
    return sampleAbsRatio(deviceDesc_.absPressure, 0.16, 0.03, 64);
}

int64_t TouchMuxEngine::sampleDurationMs(int64_t baseMs,
                                         bool microJitter,
                                         int64_t minMs,
                                         int64_t maxMs) {
    int64_t clampedBase = std::clamp(baseMs, minMs, maxMs);
    if (!microJitter) {
        return clampedBase;
    }
    std::normal_distribution<double> factorDist(1.0, 0.04);
    double factor = std::clamp(factorDist(rng_), 0.90, 1.10);
    return std::clamp<int64_t>(std::llround(static_cast<double>(clampedBase) * factor), minMs, maxMs);
}

void TouchMuxEngine::applySpatialJitter(float& x, float& y, bool microJitter) {
    if (!microJitter || activeJitterRadiusPx_ <= 0.0f) return;
    std::normal_distribution<float> dist(0.0f, activeJitterRadiusPx_ * 0.45f);
    float dx = std::clamp(dist(rng_), -activeJitterRadiusPx_, activeJitterRadiusPx_);
    float dy = std::clamp(dist(rng_), -activeJitterRadiusPx_, activeJitterRadiusPx_);
    x += dx;
    y += dy;
}

void TouchMuxEngine::handleWakeSignal() {
    uint64_t drained = 0;
    while (read(wakeFd_, &drained, sizeof(drained)) > 0) {
    }

    syncEngineTargetTriggerConfig();

    std::vector<NativeClickTarget> nextTargets;
    std::vector<NativeRawMacroEvent> nextRawEvents;
    std::vector<RpcCommand> pendingRpc;
    bool nextRunning = false;
    bool nextRawRunning = false;
    bool rawEventsUpdated = false;
    int64_t nextRawTotalDurMs = 0;
    int32_t nextRawLoopCount = 0;
    uint64_t nextRawSeq = 0;
    ExecutionMode nextExecMode = ExecutionMode::CONCURRENT;
    int32_t nextPresetLoopCount = 0;
    uint64_t nextForceResetSeq = 0;
    bool stopThread = false;
    {
        std::lock_guard<std::mutex> lock(stateMutex_);
        activeDisplayW_ = cfgDisplayW_;
        activeDisplayH_ = cfgDisplayH_;
        activeRotation_ = cfgRotation_;
        activeJitterRadiusPx_ = cfgJitterRadiusPx_;
        nextRunning = cfgClickingRunning_;
        nextExecMode = cfgExecutionMode_;
        nextPresetLoopCount = cfgPresetLoopCount_;
        nextForceResetSeq = cfgForceResetSeq_;
        nextTargets = cfgTargets_;
        nextRawRunning = cfgRawMacroRunning_;
        nextRawTotalDurMs = cfgRawMacroTotalDurationMs_;
        nextRawLoopCount = cfgRawMacroLoopCount_;
        nextRawSeq = cfgRawMacroSeq_;
        if (nextRawSeq != activeRawMacroSeq_) {
            nextRawEvents = cfgRawMacroEvents_;
            rawEventsUpdated = true;
        }
        pendingRpc.swap(cfgRpcCommands_);
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

    const int64_t now = nowMonotonicNs();

    // Enqueue pending one-shot RPC commands
    for (const auto& cmd : pendingRpc) {
        if (cmd.kind == RpcCommand::Kind::CLEAR_ALL) {
            releaseAllRpcHoldSlots();
            continue;
        }
        if (cmd.kind == RpcCommand::Kind::HOLD_UP) {
            for (size_t i = 0; i < rpcGestures_.size();) {
                if (rpcGestures_[i].isHold && rpcGestures_[i].targetId == (-20000 - cmd.id)) {
                    if (rpcGestures_[i].muxSlot >= 0) {
                        emitSlotUp(rpcGestures_[i].muxSlot);
                    }
                    rpcGestures_.erase(rpcGestures_.begin() + static_cast<std::ptrdiff_t>(i));
                } else {
                    ++i;
                }
            }
            emitSyncAndFlush();
            continue;
        }
        if (cmd.kind == RpcCommand::Kind::HOLD_DOWN) {
            int32_t holdTargetId = -20000 - cmd.id;
            bool updated = false;
            for (auto& rg : rpcGestures_) {
                if (rg.isHold && rg.targetId == holdTargetId && rg.muxSlot >= 0) {
                    int32_t rx = 0, ry = 0;
                    logicalToRaw(cmd.x1, cmd.y1, rx, ry);
                    emitSlotMove(rg.muxSlot, rx, ry, rg.major, rg.pressure, true, cmd.x1, cmd.y1);
                    rg.x1 = cmd.x1;
                    rg.y1 = cmd.y1;
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                int slot = allocateVirtualSlot(holdTargetId);
                if (slot >= 0) {
                    RpcGestureRuntime rg;
                    rg.targetId = holdTargetId;
                    rg.isHold = true;
                    rg.muxSlot = slot;
                    rg.x1 = cmd.x1;
                    rg.y1 = cmd.y1;
                    rg.major = sampleTouchMajor();
                    rg.pressure = samplePressure();
                    rg.nextDeadlineNs = std::numeric_limits<int64_t>::max();
                    int32_t rx = 0, ry = 0;
                    logicalToRaw(rg.x1, rg.y1, rx, ry);
                    emitSlotDown(slot, rx, ry, rg.major, rg.pressure, true, rg.x1, rg.y1);
                    rpcGestures_.push_back(rg);
                }
            }
            emitSyncAndFlush();
            continue;
        }

        // TAP or SWIPE
        int32_t tid = -1000 - (++rpcSeqCounter_);
        int slot = allocateVirtualSlot(tid);
        if (slot >= 0) {
            RpcGestureRuntime rg;
            rg.targetId = tid;
            rg.isSwipe = (cmd.kind == RpcCommand::Kind::SWIPE);
            rg.muxSlot = slot;
            rg.x1 = cmd.x1;
            rg.y1 = cmd.y1;
            rg.x2 = cmd.x2;
            rg.y2 = cmd.y2;
            rg.major = sampleTouchMajor();
            rg.pressure = samplePressure();
            rg.startNs = now;
            rg.endNs = now + cmd.durationMs * 1000000LL;
            rg.nextDeadlineNs = rg.isSwipe ? std::min(rg.endNs, now + kSwipeStepNs) : rg.endNs;
            int32_t rx = 0, ry = 0;
            logicalToRaw(rg.x1, rg.y1, rx, ry);
            emitSlotDown(slot, rx, ry, rg.major, rg.pressure, true, rg.x1, rg.y1);
            emitSyncAndFlush();
            rpcGestures_.push_back(rg);
        }
    }

    // Handle raw macro playback mode
    if (nextRawRunning) {
        if (activeClickingRunning_) {
            activeClickingRunning_ = false;
            releaseAllVirtualTargetSlots();
        }
        if (rawEventsUpdated || !activeRawMacroRunning_) {
            releaseAllRawMacroSlots();
            if (rawEventsUpdated) {
                activeRawMacroEvents_ = std::move(nextRawEvents);
                activeRawMacroSeq_ = nextRawSeq;
            }
            activeRawMacroTotalDurationMs_ = std::max<int64_t>(8, nextRawTotalDurMs);
            activeRawMacroLoopCount_ = std::clamp(nextRawLoopCount, 0, 9999);
            rawMacroCursor_ = 0;
            rawMacroCompletedLoops_ = 0;
            rawMacroLoopStartNs_ = now;
            rawMacroNextDeadlineNs_ = now;
            activeRawMacroRunning_ = !activeRawMacroEvents_.empty();
        }
        handleTimerEvents();
        return;
    }
    if (activeRawMacroRunning_) {
        activeRawMacroRunning_ = false;
        releaseAllRawMacroSlots();
        rawMacroNextDeadlineNs_ = 0;
    }
    if (rawEventsUpdated) {
        activeRawMacroEvents_.clear();
        activeRawMacroSeq_ = nextRawSeq;
    }

    // Handle loop targets update
    if (!nextRunning) {
        activeClickingRunning_ = false;
        activeExecutionMode_ = nextExecMode;
        activePresetLoopCount_ = nextPresetLoopCount;
        activeForceResetSeq_ = nextForceResetSeq;
        seqCompletedLoops_ = 0;
        releaseAllVirtualTargetSlots();
        virtualTargets_.clear();
        stages_.clear();
        activeStageIdx_ = 0;
        if (nextExecMode == ExecutionMode::SEQUENTIAL) {
            buildSequentialStages(nextTargets, now);
        } else {
            for (const auto& t : nextTargets) {
                if (!t.enabled) continue;
                VirtualTargetRuntime rt;
                rt.config = t;
                virtualTargets_.push_back(rt);
            }
        }
        handleTimerEvents();
        return;
    }

    const bool freshStart = !activeClickingRunning_ || (nextForceResetSeq != activeForceResetSeq_);
    activeForceResetSeq_ = nextForceResetSeq;
    const bool execModeChanged = (activeExecutionMode_ != nextExecMode) || freshStart;
    activeExecutionMode_ = nextExecMode;
    activePresetLoopCount_ = nextPresetLoopCount;

    if (nextExecMode == ExecutionMode::SEQUENTIAL) {
        releaseAllVirtualTargetSlots();
        virtualTargets_.clear();
        seqCompletedLoops_ = 0;
        buildSequentialStages(nextTargets, now);
    } else {
        stages_.clear();
        activeStageIdx_ = 0;
        auto isCompatibleLiveTarget = [](const NativeClickTarget& oldCfg, const NativeClickTarget& newCfg) {
            return newCfg.id == oldCfg.id &&
                   newCfg.enabled &&
                   oldCfg.actionType == newCfg.actionType &&
                   std::hypot(oldCfg.x - newCfg.x, oldCfg.y - newCfg.y) <= 24.0f &&
                   std::hypot(oldCfg.endX - newCfg.endX, oldCfg.endY - newCfg.endY) <= 24.0f;
        };
        if (execModeChanged) {
            releaseAllVirtualTargetSlots();
        } else {
            // Release removed or disabled targets when staying in same execution mode
            for (auto& oldRt : virtualTargets_) {
                bool stillEnabled = false;
                for (const auto& nt : nextTargets) {
                    if (isCompatibleLiveTarget(oldRt.config, nt)) {
                        stillEnabled = true;
                        break;
                    }
                }
                if (!stillEnabled && oldRt.isPressed && oldRt.muxSlot >= 0) {
                    emitSlotUp(oldRt.muxSlot);
                    oldRt.muxSlot = -1;
                    oldRt.isPressed = false;
                    oldRt.holdLockedDown = false;
                }
            }
            emitSyncAndFlush();
        }

        std::vector<VirtualTargetRuntime> rebuilt;
        rebuilt.reserve(nextTargets.size());

        for (size_t i = 0; i < nextTargets.size(); ++i) {
            const auto& nt = nextTargets[i];
            if (!nt.enabled) continue;
            bool matched = false;
            if (!execModeChanged) {
                for (const auto& oldRt : virtualTargets_) {
                    if (isCompatibleLiveTarget(oldRt.config, nt) && activeClickingRunning_) {
                        VirtualTargetRuntime updated = oldRt;
                        updated.config = nt;
                        if (nt.repeatCount == 0 && updated.finishedRounds) {
                            updated.finishedRounds = false;
                            updated.completedCycles = 0;
                            if (!updated.isPressed) {
                                updated.nextDeadlineNs = now;
                            }
                        } else if (nt.repeatCount > 0 && updated.completedCycles < nt.repeatCount && updated.finishedRounds) {
                            updated.finishedRounds = false;
                            if (!updated.isPressed) {
                                updated.nextDeadlineNs = now;
                            }
                        }
                        rebuilt.push_back(updated);
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                VirtualTargetRuntime fresh;
                fresh.config = nt;
                fresh.isPressed = false;
                fresh.muxSlot = -1;
                fresh.nextDeadlineNs = now + static_cast<int64_t>(rebuilt.size()) * 500000LL;
                rebuilt.push_back(fresh);
            }
        }

        virtualTargets_ = std::move(rebuilt);
    }
    activeClickingRunning_ = true;

    handleTimerEvents();
}

void TouchMuxEngine::handleTypeAFrameEnd() {
    const int maxSlots = (workerBackend_ == MuxBackendMode::UHID) ? kMaxUhidSlots : kMaxUinputSlots;
    const size_t newCount = std::min(typeAFrameContacts_.size(), static_cast<size_t>(maxSlots));
    while (typeAActiveMuxSlots_.size() > newCount) {
        int slot = typeAActiveMuxSlots_.back();
        typeAActiveMuxSlots_.pop_back();
        emitSlotUp(slot);
    }
    while (typeAActiveMuxSlots_.size() < newCount) {
        int slot = allocatePhysicalSlot(static_cast<int>(typeAActiveMuxSlots_.size()));
        if (slot < 0) break;
        typeAActiveMuxSlots_.push_back(slot);
        ensureUinputSlot(slot);
        queueEvent(EV_ABS, ABS_MT_TRACKING_ID, muxSlots_[slot].trackingId);
    }
    for (size_t i = 0; i < typeAActiveMuxSlots_.size(); ++i) {
        int slot = typeAActiveMuxSlots_[i];
        const auto& c = typeAFrameContacts_[i];
        int32_t rx = c.hasX ? c.x : muxSlots_[slot].rawX;
        int32_t ry = c.hasY ? c.y : muxSlots_[slot].rawY;
        int32_t maj = c.major > 0 ? c.major : muxSlots_[slot].major;
        int32_t prs = c.pressure > 0 ? c.pressure : muxSlots_[slot].pressure;
        emitSlotMove(slot, rx, ry, maj, prs, false, 0.0f, 0.0f);
    }
    if (activeTtMode_ != TargetTriggerMode::OFF && !activeTtZones_.empty()) {
        bool usedSlot[kMaxPhysSlots]{};
        int assigned[16]{};
        const size_t cnt = std::min<size_t>(typeAFrameContacts_.size(), 16);
        for (size_t c = 0; c < cnt; ++c) {
            assigned[c] = -1;
            int bestS = -1;
            int64_t bestD2 = std::numeric_limits<int64_t>::max();
            for (int s = 0; s < kMaxPhysSlots; ++s) {
                if (usedSlot[s] || !engTriggerSlots_[s].active) continue;
                int64_t dx = static_cast<int64_t>(typeAFrameContacts_[c].x) - engPhysRawX_[s];
                int64_t dy = static_cast<int64_t>(typeAFrameContacts_[c].y) - engPhysRawY_[s];
                int64_t d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestS = s;
                }
            }
            if (bestS >= 0) {
                usedSlot[bestS] = true;
                assigned[c] = bestS;
            }
        }
        for (int s = 0; s < kMaxPhysSlots; ++s) {
            if (engTriggerSlots_[s].active && !usedSlot[s]) engPhysPendingUp_[s] = true;
        }
        for (size_t c = 0; c < cnt; ++c) {
            int s = assigned[c];
            if (s < 0) {
                for (int k = 0; k < kMaxPhysSlots; ++k) {
                    if (!engTriggerSlots_[k].active && !usedSlot[k]) {
                        s = k;
                        usedSlot[k] = true;
                        engPhysPendingDown_[s] = true;
                        break;
                    }
                }
            }
            if (s >= 0) {
                if (typeAFrameContacts_[c].hasX) engPhysRawX_[s] = typeAFrameContacts_[c].x;
                if (typeAFrameContacts_[c].hasY) engPhysRawY_[s] = typeAFrameContacts_[c].y;
            }
        }
    }
    typeAFrameContacts_.clear();
    typeACurrent_ = TypeAContact{};
    processEngineTriggerSynFrame(nowMonotonicNs());
    emitSyncAndFlush();
}

void TouchMuxEngine::handlePhysicalEvents() {
    struct input_event buf[64];
    while (true) {
        ssize_t n = read(physFd_, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            if (errno == EINTR) continue;
            LOGE("read(physFd_) error: %s", std::strerror(errno));
            break;
        }
        if (n == 0) break;

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
                    processEngineTriggerSynFrame(nowMonotonicNs());
                    emitSyncAndFlush();
                } else if (ev.code == SYN_DROPPED) {
                    LOGW("SYN_DROPPED on physical touchscreen; resetting physical slots.");
                    for (int p = 0; p < kMaxPhysSlots; ++p) {
                        int u = physToMuxSlot_[p];
                        if (u >= 0) {
                            emitSlotUp(u);
                            physToMuxSlot_[p] = -1;
                        }
                        if (engTriggerSlots_[p].active) {
                            engPhysPendingUp_[p] = true;
                        }
                    }
                    processEngineTriggerSynFrame(nowMonotonicNs());
                    emitSyncAndFlush();
                }
                continue;
            }

            if (ev.type == EV_KEY) {
                continue;
            }

            if (ev.type == EV_ABS) {
                if (ev.code == ABS_MT_SLOT) {
                    physCurrentSlot_ = std::clamp(ev.value, 0, kMaxPhysSlots - 1);
                } else if (ev.code == ABS_MT_TRACKING_ID) {
                    if (ev.value >= 0) {
                        engPhysPendingDown_[physCurrentSlot_] = true;
                        engPhysPendingUp_[physCurrentSlot_] = false;
                        engTriggerSlots_[physCurrentSlot_].suppressed = false;
                        int u = physToMuxSlot_[physCurrentSlot_];
                        if (u < 0) {
                            u = allocatePhysicalSlot(physCurrentSlot_);
                            physToMuxSlot_[physCurrentSlot_] = u;
                        } else {
                            muxSlots_[u].trackingId = nextTrackingId();
                        }
                        if (u >= 0) {
                            ensureUinputSlot(u);
                            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, muxSlots_[u].trackingId);
                            uhidDirty_ = true;
                            hybridDirty_ = true;
                        }
                    } else {
                        if (engTriggerSlots_[physCurrentSlot_].active || engPhysPendingDown_[physCurrentSlot_]) {
                            engPhysPendingUp_[physCurrentSlot_] = true;
                        }
                        int u = physToMuxSlot_[physCurrentSlot_];
                        if (u >= 0) {
                            emitSlotUp(u);
                            physToMuxSlot_[physCurrentSlot_] = -1;
                        }
                    }
                } else {
                    if (ev.code == ABS_MT_POSITION_X) {
                        engPhysRawX_[physCurrentSlot_] = ev.value;
                    } else if (ev.code == ABS_MT_POSITION_Y) {
                        engPhysRawY_[physCurrentSlot_] = ev.value;
                    }
                    if (engTriggerSlots_[physCurrentSlot_].suppressed) {
                        continue;
                    }
                    if (!engTriggerSlots_[physCurrentSlot_].active &&
                        !engPhysPendingDown_[physCurrentSlot_] &&
                        (ev.code == ABS_MT_POSITION_X || ev.code == ABS_MT_POSITION_Y)) {
                        engPhysPendingDown_[physCurrentSlot_] = true;
                    }
                    int u = physToMuxSlot_[physCurrentSlot_];
                    if (u < 0 && (ev.code == ABS_MT_POSITION_X || ev.code == ABS_MT_POSITION_Y)) {
                        u = allocatePhysicalSlot(physCurrentSlot_);
                        physToMuxSlot_[physCurrentSlot_] = u;
                        if (u >= 0) {
                            ensureUinputSlot(u);
                            queueEvent(EV_ABS, ABS_MT_TRACKING_ID, muxSlots_[u].trackingId);
                        }
                    }
                    if (u >= 0) {
                        if (ev.code == ABS_MT_POSITION_X) {
                            muxSlots_[u].rawX = ev.value;
                            muxSlots_[u].hasExactLogical = false;
                        } else if (ev.code == ABS_MT_POSITION_Y) {
                            muxSlots_[u].rawY = ev.value;
                            muxSlots_[u].hasExactLogical = false;
                        } else if (ev.code == ABS_MT_TOUCH_MAJOR) {
                            muxSlots_[u].major = ev.value;
                        } else if (ev.code == ABS_MT_PRESSURE) {
                            muxSlots_[u].pressure = ev.value;
                        }
                        uhidDirty_ = true;
                        hybridDirty_ = true;
                        ensureUinputSlot(u);
                        queueEvent(EV_ABS, ev.code, ev.value);
                    }
                }
            }
        }
    }
}

void TouchMuxEngine::stepRpcGestures(int64_t now) {
    bool anyEmitted = false;
    for (size_t i = 0; i < rpcGestures_.size();) {
        auto& rg = rpcGestures_[i];
        if (rg.isHold || rg.nextDeadlineNs > now) {
            ++i;
            continue;
        }

        if (rg.isSwipe && !rg.swipeFinalReached && now < rg.endNs) {
            double total = static_cast<double>(std::max<int64_t>(1, rg.endNs - rg.startNs));
            double t = std::clamp(static_cast<double>(now - rg.startNs) / total, 0.0, 1.0);
            float cx = rg.x1 + (rg.x2 - rg.x1) * static_cast<float>(t);
            float cy = rg.y1 + (rg.y2 - rg.y1) * static_cast<float>(t);
            int32_t rx = 0, ry = 0;
            logicalToRaw(cx, cy, rx, ry);
            emitSlotMove(rg.muxSlot, rx, ry, rg.major, rg.pressure, true, cx, cy);
            rg.nextDeadlineNs = std::min(rg.endNs, now + kSwipeStepNs);
            anyEmitted = true;
            ++i;
        } else if (rg.isSwipe && !rg.swipeFinalReached) {
            int32_t rx = 0, ry = 0;
            logicalToRaw(rg.x2, rg.y2, rx, ry);
            emitSlotMove(rg.muxSlot, rx, ry, rg.major, rg.pressure, true, rg.x2, rg.y2);
            rg.swipeFinalReached = true;
            rg.nextDeadlineNs = now + kSwipeStepNs;
            anyEmitted = true;
            ++i;
        } else {
            if (rg.muxSlot >= 0) {
                emitSlotUp(rg.muxSlot);
                anyEmitted = true;
            }
            rpcGestures_.erase(rpcGestures_.begin() + static_cast<std::ptrdiff_t>(i));
        }
    }

    if (anyEmitted) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::stepVirtualTargets(int64_t now) {
    if (!activeClickingRunning_) {
        return;
    }
    if (activeExecutionMode_ == ExecutionMode::SEQUENTIAL && stages_.empty()) {
        return;
    }
    if (activeExecutionMode_ != ExecutionMode::SEQUENTIAL && virtualTargets_.empty()) {
        return;
    }

    bool anyEmitted = false;

    auto handleSingleTargetTick = [&](VirtualTargetRuntime& vt) -> bool {
        // Returns true if this target JUST COMPLETED (released) its gesture cycle
        if (vt.holdLockedDown || vt.finishedRounds) {
            return false;
        }
        if (vt.nextDeadlineNs > now) {
            return false;
        }

        const ActionType act = static_cast<ActionType>(vt.config.actionType);
        const bool useTapJitter = (act == ActionType::TAP) && vt.config.microJitter;

        if (!vt.isPressed) {
            int slot = -1;
            bool carriedIn = false;
            if (vt.config.holdInId > 0) {
                auto it = carriedHoldSlots_.find(vt.config.holdInId);
                if (it != carriedHoldSlots_.end()) {
                    int candidate = it->second;
                    carriedHoldSlots_.erase(it);
                    if (candidate >= 0 && candidate < kMaxUinputSlots &&
                        muxSlots_[candidate].owner == SlotOwner::VIRTUAL) {
                        slot = candidate;
                        muxSlots_[slot].ownerSlotOrTargetId = vt.config.id;
                        carriedIn = true;
                    }
                }
            }
            if (slot < 0) {
                slot = allocateVirtualSlot(vt.config.id);
                if (slot < 0) {
                    vt.nextDeadlineNs = now + 5000000LL;
                    return false;
                }
            }
            vt.sampledMajor = sampleTouchMajor();
            vt.sampledPressure = samplePressure();
            if (!carriedIn) {
                float sx = vt.config.x;
                float sy = vt.config.y;
                applySpatialJitter(sx, sy, useTapJitter);

                vt.startX = sx;
                vt.startY = sy;
                if (act == ActionType::SWIPE) {
                    vt.endX = vt.config.endX;
                    vt.endY = vt.config.endY;
                }

                int32_t rx = 0, ry = 0;
                logicalToRaw(sx, sy, rx, ry);
                emitSlotDown(slot, rx, ry, vt.sampledMajor, vt.sampledPressure, true, sx, sy);
                anyEmitted = true;
            } else {
                vt.startX = vt.config.x;
                vt.startY = vt.config.y;
                if (act == ActionType::SWIPE) {
                    vt.endX = vt.config.endX;
                    vt.endY = vt.config.endY;
                }
            }

            vt.muxSlot = slot;
            vt.isPressed = true;
            vt.swipeFinalReached = false;

            if (act == ActionType::SWIPE) {
                int64_t durMs = sampleDurationMs(vt.config.swipeDurationMs, false, 8, 15000);
                vt.swipeStartNs = now;
                vt.swipeEndNs = now + durMs * 1000000LL;
                vt.nextDeadlineNs = std::min(vt.swipeEndNs, now + kSwipeStepNs);
            } else if (act == ActionType::HOLD && vt.config.repeatCount == 0) {
                vt.holdLockedDown = true;
                vt.nextDeadlineNs = std::numeric_limits<int64_t>::max();
            } else {
                int64_t pressMs = sampleDurationMs(
                    vt.config.pressDurationMs,
                    useTapJitter,
                    8,
                    act == ActionType::HOLD ? 30000 : 5000
                );
                vt.nextDeadlineNs = now + pressMs * 1000000LL;
            }
            return false;
        }

        // Currently pressed: handle SWIPE 4ms interpolation steps
        if (act == ActionType::SWIPE && !vt.swipeFinalReached) {
            if (now < vt.swipeEndNs) {
                double total = static_cast<double>(std::max<int64_t>(1, vt.swipeEndNs - vt.swipeStartNs));
                double t = std::clamp(static_cast<double>(now - vt.swipeStartNs) / total, 0.0, 1.0);
                float cx = vt.startX + (vt.endX - vt.startX) * static_cast<float>(t);
                float cy = vt.startY + (vt.endY - vt.startY) * static_cast<float>(t);
                int32_t rx = 0, ry = 0;
                logicalToRaw(cx, cy, rx, ry);
                emitSlotMove(vt.muxSlot, rx, ry, vt.sampledMajor, vt.sampledPressure, true, cx, cy);
                vt.nextDeadlineNs = std::min(vt.swipeEndNs, now + kSwipeStepNs);
                anyEmitted = true;
                return false;
            } else {
                int32_t rx = 0, ry = 0;
                logicalToRaw(vt.endX, vt.endY, rx, ry);
                emitSlotMove(vt.muxSlot, rx, ry, vt.sampledMajor, vt.sampledPressure, true, vt.endX, vt.endY);
                vt.swipeFinalReached = true;
                anyEmitted = true;

                if (activeExecutionMode_ == ExecutionMode::SEQUENTIAL &&
                    vt.config.holdOutId > 0 &&
                    !stages_.empty() &&
                    activeStageIdx_ < stages_.size()) {
                    const auto& curSt = stages_[activeStageIdx_];
                    const bool advancingStage = (curSt.repeatCount >= 1 && (curSt.completedRepeats + 1) >= curSt.repeatCount);
                    if (curSt.durationMs > 0 && advancingStage) {
                        vt.holdLockedDown = true;
                        vt.nextDeadlineNs = std::numeric_limits<int64_t>::max();
                        return false;
                    }
                    if (curSt.durationMs == 0 && curSt.targets.size() == 1 && advancingStage && vt.muxSlot >= 0) {
                        carriedHoldSlots_[vt.config.holdOutId] = vt.muxSlot;
                        vt.muxSlot = -1;
                        vt.isPressed = false;
                        vt.swipeFinalReached = false;
                        vt.nextDeadlineNs = now;
                        return true;
                    }
                }

                vt.nextDeadlineNs = now + kSwipeStepNs;
                return false;
            }
        }

        // Release slot unless seamlessly carried out to the immediately following stage
        bool handoffOut = false;
        if (activeExecutionMode_ == ExecutionMode::SEQUENTIAL &&
            vt.config.holdOutId > 0 &&
            !stages_.empty() &&
            activeStageIdx_ < stages_.size()) {
            const auto& curSt = stages_[activeStageIdx_];
            if (curSt.durationMs == 0 && curSt.targets.size() == 1 &&
                curSt.repeatCount >= 1 && (curSt.completedRepeats + 1) >= curSt.repeatCount &&
                vt.muxSlot >= 0) {
                carriedHoldSlots_[vt.config.holdOutId] = vt.muxSlot;
                handoffOut = true;
            }
        }
        if (!handoffOut && vt.muxSlot >= 0) {
            emitSlotUp(vt.muxSlot);
            anyEmitted = true;
        }
        vt.muxSlot = -1;
        vt.isPressed = false;
        vt.swipeFinalReached = false;

        int64_t intervalMs = handoffOut ? 0 : sampleDurationMs(vt.config.intervalMs, useTapJitter, 5, 60000);
        vt.nextDeadlineNs = now + intervalMs * 1000000LL;
        return true;
    };

    auto resetStageTargets = [](StageRuntime& st, int64_t readyAtNs, bool preserveHeld = false) {
        st.stageDeadlineNs = 0;
        st.startDelayUntilNs = readyAtNs;
        st.inGap = false;
        for (size_t i = 0; i < st.targets.size(); ++i) {
            auto& vt = st.targets[i];
            if (preserveHeld && vt.isPressed && vt.holdLockedDown && vt.muxSlot >= 0) {
                vt.completedCycles = 0;
                vt.finishedRounds = false;
                continue;
            }
            vt.isPressed = false;
            vt.holdLockedDown = false;
            vt.swipeFinalReached = false;
            vt.muxSlot = -1;
            vt.completedCycles = 0;
            vt.finishedRounds = false;
            vt.nextDeadlineNs = (vt.config.holdInId > 0)
                ? readyAtNs
                : (readyAtNs + static_cast<int64_t>(i) * 500000LL);
        }
    };

    if (activeExecutionMode_ == ExecutionMode::SEQUENTIAL) {
        for (int pass = 0; pass < 2; ++pass) {
            if (activeStageIdx_ >= stages_.size()) {
                activeStageIdx_ = 0;
            }
            auto& st = stages_[activeStageIdx_];
            if (st.targets.empty()) {
                break;
            }
            if (st.inGap) {
                if (now < st.gapDeadlineNs) {
                    break;
                }
                st.inGap = false;
                const size_t nextStageIdx = (activeStageIdx_ + 1) % stages_.size();
                if (nextStageIdx == 0) {
                    seqCompletedLoops_++;
                    if (activePresetLoopCount_ >= 1 && seqCompletedLoops_ >= activePresetLoopCount_) {
                        if (anyEmitted) emitSyncAndFlush();
                        finishClickingRun();
                        return;
                    }
                }
                activeStageIdx_ = nextStageIdx;
                resetStageTargets(stages_[activeStageIdx_], now, false);
                continue;
            }
            if (st.startDelayUntilNs > now) {
                break;
            }

            if (st.durationMs > 0 || st.targets.size() > 1) {
                // Timed concurrent sub-preset stage
                if (st.stageDeadlineNs == 0) {
                    resetStageTargets(st, now, true);
                    st.stageDeadlineNs = (st.repeatCount == 0)
                                             ? std::numeric_limits<int64_t>::max()
                                             : (now + st.durationMs * 1000000LL);
                }
                if (now >= st.stageDeadlineNs) {
                    const bool advancingToNextStage = (st.completedRepeats + 1 >= st.repeatCount);
                    int64_t gapMs = sampleDurationMs(st.gapMs, false, 0, 60000);
                    for (auto& vt : st.targets) {
                        const bool isHoldInf = (static_cast<ActionType>(vt.config.actionType) == ActionType::HOLD &&
                                                vt.config.repeatCount == 0);
                        if (advancingToNextStage && vt.isPressed && vt.muxSlot >= 0 && vt.config.holdOutId > 0) {
                            if (static_cast<ActionType>(vt.config.actionType) == ActionType::SWIPE && !vt.swipeFinalReached) {
                                int32_t rx = 0, ry = 0;
                                logicalToRaw(vt.endX, vt.endY, rx, ry);
                                emitSlotMove(vt.muxSlot, rx, ry, vt.sampledMajor, vt.sampledPressure, true, vt.endX, vt.endY);
                                anyEmitted = true;
                            }
                            carriedHoldSlots_[vt.config.holdOutId] = vt.muxSlot;
                            vt.muxSlot = -1;
                            vt.isPressed = false;
                            vt.holdLockedDown = false;
                            vt.swipeFinalReached = false;
                        } else if (!advancingToNextStage && gapMs <= 80 && vt.isPressed && vt.muxSlot >= 0 && isHoldInf) {
                            // Keep finger down seamlessly across intra-stage repeat
                            vt.completedCycles = 0;
                            vt.finishedRounds = false;
                        } else {
                            if (vt.isPressed && vt.muxSlot >= 0) {
                                emitSlotUp(vt.muxSlot);
                                anyEmitted = true;
                            }
                            vt.muxSlot = -1;
                            vt.isPressed = false;
                            vt.holdLockedDown = false;
                            vt.swipeFinalReached = false;
                        }
                    }
                    st.stageDeadlineNs = 0;
                    st.completedRepeats++;
                    if (!advancingToNextStage) {
                        resetStageTargets(st, now + gapMs * 1000000LL, true);
                        if (gapMs == 0) {
                            continue;
                        }
                    } else {
                        st.completedRepeats = 0;
                        const size_t nextStageIdx = (activeStageIdx_ + 1) % stages_.size();
                        if (nextStageIdx == 0 && activePresetLoopCount_ >= 1 &&
                            (seqCompletedLoops_ + 1) >= activePresetLoopCount_) {
                            seqCompletedLoops_++;
                            if (anyEmitted) emitSyncAndFlush();
                            finishClickingRun();
                            return;
                        }
                        if (gapMs == 0) {
                            activeStageIdx_ = nextStageIdx;
                            resetStageTargets(stages_[activeStageIdx_], now, false);
                            if (stages_.size() > 1) {
                                continue;
                            }
                        } else {
                            st.inGap = true;
                            st.gapDeadlineNs = now + gapMs * 1000000LL;
                        }
                    }
                    break;
                }
                for (auto& vt : st.targets) {
                    if (!vt.config.enabled || vt.finishedRounds) continue;
                    bool completed = handleSingleTargetTick(vt);
                    if (completed && vt.config.repeatCount >= 1) {
                        vt.completedCycles++;
                        if (vt.completedCycles >= vt.config.repeatCount) {
                            vt.finishedRounds = true;
                            vt.nextDeadlineNs = std::numeric_limits<int64_t>::max();
                        }
                    }
                }
                break;
            } else {
                // Single-gesture sequential stage (TAP / SWIPE / HOLD)
                auto& vt = st.targets[0];
                bool completed = handleSingleTargetTick(vt);
                if (completed && st.repeatCount >= 1) {
                    st.completedRepeats++;
                    const bool isTapStage = (static_cast<ActionType>(vt.config.actionType) == ActionType::TAP);
                    if (st.completedRepeats < st.repeatCount) {
                        int64_t waitMs = sampleDurationMs(
                            vt.config.intervalMs,
                            isTapStage && vt.config.microJitter,
                            5,
                            60000
                        );
                        vt.nextDeadlineNs = now + waitMs * 1000000LL;
                    } else {
                        st.completedRepeats = 0;
                        const size_t nextStageIdx = (activeStageIdx_ + 1) % stages_.size();
                        if (nextStageIdx == 0) {
                            seqCompletedLoops_++;
                            if (activePresetLoopCount_ >= 1 && seqCompletedLoops_ >= activePresetLoopCount_) {
                                if (anyEmitted) emitSyncAndFlush();
                                finishClickingRun();
                                return;
                            }
                        }
                        int64_t waitMs = sampleDurationMs(
                            st.gapMs,
                            isTapStage && vt.config.microJitter && st.gapMs >= 5,
                            0,
                            60000
                        );
                        activeStageIdx_ = nextStageIdx;
                        resetStageTargets(stages_[activeStageIdx_], now + waitMs * 1000000LL, false);
                        if (waitMs == 0 && stages_.size() > 1) {
                            continue;
                        }
                    }
                }
                break;
            }
        }
    } else {
        // Pure CONCURRENT execution: each target honors its own repeatCount (0 = infinite, >=1 = N cycles)
        bool allHaveFiniteCount = true;
        bool allFinished = true;
        for (auto& vt : virtualTargets_) {
            if (!vt.config.enabled) continue;
            if (vt.config.repeatCount <= 0) {
                allHaveFiniteCount = false;
            }
            bool completed = handleSingleTargetTick(vt);
            if (completed && vt.config.repeatCount >= 1) {
                vt.completedCycles++;
                if (vt.completedCycles >= vt.config.repeatCount) {
                    vt.finishedRounds = true;
                    vt.nextDeadlineNs = std::numeric_limits<int64_t>::max();
                }
            }
            if (vt.config.repeatCount >= 1 && !vt.finishedRounds) {
                allFinished = false;
            }
        }
        if (allHaveFiniteCount && allFinished) {
            if (anyEmitted) {
                emitSyncAndFlush();
            }
            finishClickingRun();
            return;
        }
    }

    if (anyEmitted) {
        emitSyncAndFlush();
    }
}

void TouchMuxEngine::stepRawMacro(int64_t now) {
    if (!activeRawMacroRunning_ || activeRawMacroEvents_.empty()) {
        return;
    }

    constexpr int64_t kRawMacroLoopGapNs = 120000000LL;  // 120ms tail pause between loops
    const float maxDispX = static_cast<float>(std::max(1, activeDisplayW_ - 1));
    const float maxDispY = static_cast<float>(std::max(1, activeDisplayH_ - 1));
    bool anyEmitted = false;
    int64_t lastBatchTimeMs = -1;

    while (activeRawMacroRunning_) {
        if (rawMacroCursor_ >= activeRawMacroEvents_.size()) {
            const int64_t loopEndNs = rawMacroLoopStartNs_ +
                                      activeRawMacroTotalDurationMs_ * 1000000LL;
            const bool hasMoreLoops = (activeRawMacroLoopCount_ == 0) ||
                                      ((rawMacroCompletedLoops_ + 1) < activeRawMacroLoopCount_);
            const int64_t nextBoundaryNs = hasMoreLoops ? (loopEndNs + kRawMacroLoopGapNs) : loopEndNs;
            if (now < nextBoundaryNs) {
                rawMacroNextDeadlineNs_ = nextBoundaryNs;
                break;
            }
            if (!rawMacroFingerSlots_.empty()) {
                for (const auto& kv : rawMacroFingerSlots_) {
                    if (kv.second.muxSlot >= 0) {
                        emitSlotUp(kv.second.muxSlot);
                        anyEmitted = true;
                    }
                }
                rawMacroFingerSlots_.clear();
            }
            if (anyEmitted) {
                emitSyncAndFlush();
                anyEmitted = false;
                lastBatchTimeMs = -1;
            }
            rawMacroCompletedLoops_++;
            if (activeRawMacroLoopCount_ >= 1 && rawMacroCompletedLoops_ >= activeRawMacroLoopCount_) {
                finishClickingRun();
                return;
            }
            rawMacroCursor_ = 0;
            rawMacroLoopStartNs_ = now;
            continue;
        }

        const auto& ev = activeRawMacroEvents_[rawMacroCursor_];
        const int64_t dueNs = rawMacroLoopStartNs_ + ev.timeMs * 1000000LL;
        if (dueNs > now) {
            rawMacroNextDeadlineNs_ = dueNs;
            break;
        }

        // Flush previous timestamp group separately so rapid DOWN -> UP taps are never squashed
        if (anyEmitted && lastBatchTimeMs >= 0 && ev.timeMs != lastBatchTimeMs) {
            emitSyncAndFlush();
            anyEmitted = false;
        }
        lastBatchTimeMs = ev.timeMs;

        const float lx = std::clamp(ev.normX, 0.0f, 1.0f) * maxDispX;
        const float ly = std::clamp(ev.normY, 0.0f, 1.0f) * maxDispY;
        int32_t rx = 0;
        int32_t ry = 0;
        logicalToRaw(lx, ly, rx, ry);

        if (ev.kind == 0) {  // DOWN
            auto it = rawMacroFingerSlots_.find(ev.fingerId);
            if (it != rawMacroFingerSlots_.end()) {
                emitSlotMove(it->second.muxSlot, rx, ry, it->second.major, it->second.pressure, true, lx, ly);
                anyEmitted = true;
            } else {
                int slot = allocateVirtualSlot(500000 + ev.fingerId);
                if (slot >= 0) {
                    RawMacroFingerSlot fs;
                    fs.muxSlot = slot;
                    fs.major = sampleTouchMajor();
                    fs.pressure = samplePressure();
                    rawMacroFingerSlots_[ev.fingerId] = fs;
                    emitSlotDown(slot, rx, ry, fs.major, fs.pressure, true, lx, ly);
                    anyEmitted = true;
                }
            }
        } else if (ev.kind == 1) {  // MOVE
            auto it = rawMacroFingerSlots_.find(ev.fingerId);
            if (it != rawMacroFingerSlots_.end()) {
                emitSlotMove(it->second.muxSlot, rx, ry, it->second.major, it->second.pressure, true, lx, ly);
                anyEmitted = true;
            }
        } else if (ev.kind == 2) {  // UP
            auto it = rawMacroFingerSlots_.find(ev.fingerId);
            if (it != rawMacroFingerSlots_.end()) {
                if (it->second.muxSlot >= 0) {
                    emitSlotUp(it->second.muxSlot);
                    anyEmitted = true;
                }
                rawMacroFingerSlots_.erase(it);
            }
        }
        ++rawMacroCursor_;
    }

    if (anyEmitted) {
        emitSyncAndFlush();
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
        activeRawMacroRunning_ = false;
        threadRunning_.store(false);
        engineState_.store(static_cast<int32_t>(EngineState::IDLE));
        return;
    }

    stepRpcGestures(now);
    stepVirtualTargets(now);
    stepRawMacro(now);
    rearmTimerFd();
}

void TouchMuxEngine::rearmTimerFd() {
    if (timerFd_ < 0) return;

    int64_t earliestNs = 0;
    auto considerDeadline = [&earliestNs](int64_t candidate) {
        if (candidate <= 0 || candidate == std::numeric_limits<int64_t>::max()) return;
        if (earliestNs == 0 || candidate < earliestNs) {
            earliestNs = candidate;
        }
    };

    if (selfCheckPending_.load() && selfCheckDeadlineNs_ > 0) {
        considerDeadline(selfCheckDeadlineNs_);
    }
    for (const auto& rg : rpcGestures_) {
        if (!rg.isHold) {
            considerDeadline(rg.nextDeadlineNs);
        }
    }
    if (activeRawMacroRunning_ && rawMacroNextDeadlineNs_ > 0) {
        considerDeadline(rawMacroNextDeadlineNs_);
    }
    if (activeClickingRunning_) {
        if (activeExecutionMode_ == ExecutionMode::SEQUENTIAL) {
            if (!stages_.empty()) {
                const auto& st = stages_[(activeStageIdx_ < stages_.size()) ? activeStageIdx_ : 0];
                if (st.inGap) {
                    considerDeadline(st.gapDeadlineNs);
                } else if (st.startDelayUntilNs > 0 && (st.stageDeadlineNs == 0 || st.targets.empty())) {
                    considerDeadline(st.startDelayUntilNs);
                } else {
                    if (st.startDelayUntilNs > 0) {
                        considerDeadline(st.startDelayUntilNs);
                    }
                    if (st.stageDeadlineNs > 0) {
                        considerDeadline(st.stageDeadlineNs);
                    }
                    for (const auto& vt : st.targets) {
                        if (!vt.config.enabled || vt.holdLockedDown || vt.finishedRounds) continue;
                        considerDeadline(vt.nextDeadlineNs);
                    }
                }
            }
        } else {
            for (const auto& vt : virtualTargets_) {
                if (!vt.config.enabled || vt.holdLockedDown || vt.finishedRounds) continue;
                considerDeadline(vt.nextDeadlineNs);
            }
        }
    }

    struct itimerspec its {};
    if (earliestNs > 0) {
        const int64_t now = nowMonotonicNs();
        int64_t deltaNs = earliestNs - now;
        if (deltaNs < 100000LL) {
            deltaNs = 100000LL;
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
            } else if (uhidFd_ >= 0 && fd == uhidFd_) {
                handleUhidEvents();
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
    if (uhidFd_ >= 0) {
        struct uhid_event d {};
        d.type = UHID_DESTROY;
        ssize_t ignored = write(uhidFd_, &d, sizeof(d));
        (void)ignored;
    }
    closeAllFds();
    LOGI("TouchMux engineLoop exited cleanly and released EVIOCGRAB.");
}

bool TouchMuxEngine::startRecording(int displayW,
                                    int displayH,
                                    int rotation,
                                    int excludeLeft,
                                    int excludeTop,
                                    int excludeRight,
                                    int excludeBottom) {
    stopRecording(true);
    if (threadRunning_.load()) {
        LOGW("startRecording called while active TouchMux session running; shutting down session first.");
        shutdown();
    }
    stopTouchTriggerWatchThread();

    // Reclaim any stale :touchmux daemon (e.g. after app update or Shizuku rebind) that could still
    // hold EVIOCGRAB on the physical touchscreen and starve non-grabbed O_RDONLY listeners.
    cleanupOrphanProcesses();

    DeviceDesc desc;
    int err = 0;
    if (!findBestTouchscreen(desc, err)) {
        lastError_ = formatErrno("Recording probe failed", err);
        LOGE("%s", lastError_.c_str());
        return false;
    }

    int fd = open(desc.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        int openErr = errno;
        lastError_ = formatErrno("open(touchscreen) for recording failed", openErr);
        LOGE("%s", lastError_.c_str());
        return false;
    }

    // Verify no process currently holds an exclusive EVIOCGRAB on this touchscreen node; otherwise
    // kernel evdev_events() routes all touches solely to the grab holder and fd reads nothing.
    waitForPhysicalTouchIdle(fd, desc, 500);
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        int grabErr = errno;
        if (grabErr == EBUSY) {
            LOGW("startRecording: %s returned EBUSY on grab probe; retrying orphan cleanup...",
                 desc.path.c_str());
            cleanupOrphanProcesses();
            usleep(150000);
            if (ioctl(fd, EVIOCGRAB, 1) == 0) {
                ioctl(fd, EVIOCGRAB, 0);
                grabErr = 0;
            } else {
                grabErr = errno;
            }
        }
        if (grabErr == EBUSY) {
            close(fd);
            lastError_ = formatErrno("Touchscreen exclusively grabbed by another process (EBUSY)", grabErr);
            LOGE("%s on %s", lastError_.c_str(), desc.path.c_str());
            return false;
        }
    } else {
        ioctl(fd, EVIOCGRAB, 0);
    }

    // Drain any buffered events right after opening (such as tap-release from "Ready" button)
    struct input_event drainBuf[64];
    while (read(fd, drainBuf, sizeof(drainBuf)) > 0) {
    }

    int wakeFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (wakeFd < 0) {
        int wErr = errno;
        close(fd);
        lastError_ = formatErrno("eventfd for recording failed", wErr);
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(recMutex_);
        recDesc_ = std::move(desc);
        recPhysFd_ = fd;
        recWakeFd_ = wakeFd;
        recDisplayW_ = std::max(1, displayW);
        recDisplayH_ = std::max(1, displayH);
        recRotation_ = rotation;
        recExL_ = excludeLeft;
        recExT_ = excludeTop;
        recExR_ = excludeRight;
        recExB_ = excludeBottom;
        recFirstTouchNs_ = 0;
        recActiveFingers_ = 0;
        recStrokeSeq_ = 0;
        recStrokes_.clear();
    }
    recStarted_.store(false);
    recRunning_.store(true);
    recThread_ = std::thread(&TouchMuxEngine::recordLoop, this);
    LOGI("Touch recording started (waiting for first touch outside [%d,%d,%d,%d]) on %s",
         excludeLeft, excludeTop, excludeRight, excludeBottom, recDesc_.path.c_str());
    return true;
}

void TouchMuxEngine::updateRecordingExcludeRect(int excludeLeft,
                                                int excludeTop,
                                                int excludeRight,
                                                int excludeBottom) {
    std::lock_guard<std::mutex> lock(recMutex_);
    recExL_ = excludeLeft;
    recExT_ = excludeTop;
    recExR_ = excludeRight;
    recExB_ = excludeBottom;
}

void TouchMuxEngine::getRecordingStats(int64_t outStats[4]) const {
    if (!recRunning_.load()) {
        outStats[0] = 0;
        outStats[1] = 0;
        outStats[2] = 0;
        outStats[3] = 0;
        return;
    }
    std::lock_guard<std::mutex> lock(recMutex_);
    const bool started = recStarted_.load();
    outStats[0] = started ? 2 : 1;
    outStats[1] = started && recFirstTouchNs_ > 0
                      ? std::max<int64_t>(0, (nowMonotonicNs() - recFirstTouchNs_) / 1000000LL)
                      : 0;
    outStats[2] = static_cast<int64_t>(recStrokes_.size());
    outStats[3] = static_cast<int64_t>(recActiveFingers_);
}

std::string TouchMuxEngine::stopRecording(bool cancel) {
    if (recRunning_.exchange(false)) {
        if (recWakeFd_ >= 0) {
            uint64_t v = 1;
            ssize_t ignored = write(recWakeFd_, &v, sizeof(v));
            (void)ignored;
        }
    }
    if (recThread_.joinable()) {
        recThread_.join();
    }

    std::vector<RecordedStroke> snapshot;
    int dispW = 1080;
    int dispH = 2400;
    bool hadStart = recStarted_.exchange(false);
    {
        std::lock_guard<std::mutex> lock(recMutex_);
        if (recPhysFd_ >= 0) {
            close(recPhysFd_);
            recPhysFd_ = -1;
        }
        if (recWakeFd_ >= 0) {
            close(recWakeFd_);
            recWakeFd_ = -1;
        }
        dispW = recDisplayW_;
        dispH = recDisplayH_;
        if (!cancel && hadStart) {
            snapshot = std::move(recStrokes_);
        }
        recStrokes_.clear();
        recActiveFingers_ = 0;
        recFirstTouchNs_ = 0;
    }

    if (cancel || !hadStart || snapshot.empty()) {
        syncTouchTriggerWatcherState();
        return "";
    }

    std::sort(snapshot.begin(), snapshot.end(), [](const RecordedStroke& a, const RecordedStroke& b) {
        if (a.downMs != b.downMs) return a.downMs < b.downMs;
        return a.id < b.id;
    });

    std::ostringstream oss;
    oss << std::fixed << std::setprecision(1);
    oss << "{\"screenW\":" << dispW << ",\"screenH\":" << dispH << ",\"strokes\":[";
    for (size_t i = 0; i < snapshot.size(); ++i) {
        const auto& s = snapshot[i];
        if (i > 0) oss << ",";
        oss << "{\"id\":" << s.id
            << ",\"downMs\":" << s.downMs
            << ",\"upMs\":" << std::max(s.downMs + 8, s.upMs)
            << ",\"points\":[";
        for (size_t j = 0; j < s.points.size(); ++j) {
            if (j > 0) oss << ",";
            oss << "{\"t\":" << s.points[j].tMs
                << ",\"x\":" << s.points[j].x
                << ",\"y\":" << s.points[j].y << "}";
        }
        oss << "]}";
    }
    oss << "]}";
    syncTouchTriggerWatcherState();
    return oss.str();
}

void TouchMuxEngine::recordLoop() {
    struct RecSlotState {
        bool down = false;
        bool pendingDown = false;
        bool pendingUp = false;
        bool excluded = false;
        bool hasX = false;
        bool hasY = false;
        int32_t rawX = 0;
        int32_t rawY = 0;
        float lastX = 0.0f;
        float lastY = 0.0f;
        int64_t lastSampleNs = 0;
        RecordedStroke stroke{};
    };

    RecSlotState slots[kMaxPhysSlots]{};
    int curSlot = 0;
    DeviceDesc descCopy;
    int dispW = 1080;
    int dispH = 2400;
    int rot = 0;
    int pFd = -1;
    int wFd = -1;
    {
        std::lock_guard<std::mutex> lock(recMutex_);
        descCopy = recDesc_;
        dispW = recDisplayW_;
        dispH = recDisplayH_;
        rot = recRotation_;
        pFd = recPhysFd_;
        wFd = recWakeFd_;
    }

    if (pFd < 0 || wFd < 0) {
        recRunning_.store(false);
        return;
    }

    if (descCopy.isTypeB) {
        struct input_absinfo absSlotNow {};
        if (ioctl(pFd, EVIOCGABS(ABS_MT_SLOT), &absSlotNow) == 0) {
            curSlot = std::clamp(absSlotNow.value, 0, kMaxPhysSlots - 1);
        }
    }
    const int32_t midX = (descCopy.absX.minimum + descCopy.absX.maximum) / 2;
    const int32_t midY = (descCopy.absY.minimum + descCopy.absY.maximum) / 2;
    for (auto& s : slots) {
        s.rawX = midX;
        s.rawY = midY;
    }

    auto commitSynFrame = [&](int64_t frameNs) {
        int exL = 0, exT = 0, exR = 0, exB = 0;
        {
            std::lock_guard<std::mutex> lock(recMutex_);
            exL = recExL_;
            exT = recExT_;
            exR = recExR_;
            exB = recExB_;
        }
        auto insideExclude = [&](float x, float y) -> bool {
            if (exR <= exL || exB <= exT) return false;
            return x >= static_cast<float>(exL - 8) &&
                   x <= static_cast<float>(exR + 8) &&
                   y >= static_cast<float>(exT - 8) &&
                   y <= static_cast<float>(exB + 8);
        };

        int activeCnt = 0;
        for (int s = 0; s < kMaxPhysSlots; ++s) {
            auto& st = slots[s];
            float lx = 0.0f;
            float ly = 0.0f;
            rawToLogicalDesc(descCopy, dispW, dispH, rot, st.rawX, st.rawY, lx, ly);

            if (st.pendingDown) {
                st.pendingDown = false;
                if (st.down && !st.excluded) {
                    // Close previous un-ended stroke defensively
                    int64_t upMs = std::max<int64_t>(st.stroke.downMs + 8, (frameNs - recFirstTouchNs_) / 1000000LL);
                    st.stroke.upMs = upMs;
                    std::lock_guard<std::mutex> lock(recMutex_);
                    recStrokes_.push_back(std::move(st.stroke));
                }
                st.down = true;
                st.excluded = insideExclude(lx, ly);
                if (!st.excluded) {
                    std::lock_guard<std::mutex> lock(recMutex_);
                    if (!recStarted_.load()) {
                        recFirstTouchNs_ = frameNs;
                        recStarted_.store(true);
                        LOGI("Touch recording first touch captured at (%.1f, %.1f), excl=[%d,%d,%d,%d]",
                             lx, ly, exL, exT, exR, exB);
                    }
                    int64_t tMs = std::max<int64_t>(0, (frameNs - recFirstTouchNs_) / 1000000LL);
                    st.stroke = RecordedStroke{};
                    st.stroke.id = ++recStrokeSeq_;
                    st.stroke.downMs = tMs;
                    st.stroke.upMs = tMs;
                    st.stroke.points.push_back(RecordedPoint{tMs, lx, ly});
                    st.lastX = lx;
                    st.lastY = ly;
                    st.lastSampleNs = frameNs;
                }
            } else if (st.down && !st.excluded && !st.pendingUp && (st.hasX || st.hasY)) {
                float dx = lx - st.lastX;
                float dy = ly - st.lastY;
                float dist = std::hypot(dx, dy);
                if (dist >= 0.75f || (frameNs - st.lastSampleNs) >= 4000000LL) {
                    int64_t tMs = std::max<int64_t>(st.stroke.downMs, (frameNs - recFirstTouchNs_) / 1000000LL);
                    st.stroke.points.push_back(RecordedPoint{tMs, lx, ly});
                    st.lastX = lx;
                    st.lastY = ly;
                    st.lastSampleNs = frameNs;
                }
            }

            if (st.pendingUp) {
                st.pendingUp = false;
                if (st.down && !st.excluded) {
                    int64_t tMs = std::max<int64_t>(st.stroke.downMs, (frameNs - recFirstTouchNs_) / 1000000LL);
                    if (st.stroke.points.empty() ||
                        std::hypot(lx - st.lastX, ly - st.lastY) >= 0.5f ||
                        st.stroke.points.back().tMs != tMs) {
                        st.stroke.points.push_back(RecordedPoint{tMs, lx, ly});
                    }
                    st.stroke.upMs = std::max<int64_t>(st.stroke.downMs + 8, tMs);
                    std::lock_guard<std::mutex> lock(recMutex_);
                    recStrokes_.push_back(std::move(st.stroke));
                }
                st.down = false;
                st.excluded = false;
                st.stroke = RecordedStroke{};
            }

            st.hasX = false;
            st.hasY = false;
            if (st.down && !st.excluded) {
                ++activeCnt;
            }
        }
        std::lock_guard<std::mutex> lock(recMutex_);
        recActiveFingers_ = activeCnt;
    };

    // Type-A frame accumulation helpers
    TypeAContact recTypeACur{};
    std::vector<TypeAContact> recTypeAContacts;
    int recTypeASlots[kMaxPhysSlots];
    std::fill(std::begin(recTypeASlots), std::end(recTypeASlots), -1);

    auto commitTypeAFrame = [&](int64_t frameNs) {
        bool usedSlot[kMaxPhysSlots]{};
        int assignedSlots[16]{};
        const size_t contactCnt = std::min<size_t>(recTypeAContacts.size(), 16);

        for (size_t c = 0; c < contactCnt; ++c) {
            assignedSlots[c] = -1;
            const auto& ct = recTypeAContacts[c];
            int bestS = -1;
            int64_t bestDistSq = std::numeric_limits<int64_t>::max();
            for (int s = 0; s < kMaxPhysSlots; ++s) {
                if (usedSlot[s] || (!slots[s].down)) continue;
                int64_t dx = static_cast<int64_t>(ct.x) - slots[s].rawX;
                int64_t dy = static_cast<int64_t>(ct.y) - slots[s].rawY;
                int64_t d2 = dx * dx + dy * dy;
                if (d2 < bestDistSq) {
                    bestDistSq = d2;
                    bestS = s;
                }
            }
            if (bestS >= 0) {
                usedSlot[bestS] = true;
                assignedSlots[c] = bestS;
            }
        }
        for (int s = 0; s < kMaxPhysSlots; ++s) {
            if (slots[s].down && !usedSlot[s]) {
                slots[s].pendingUp = true;
            }
        }
        for (size_t c = 0; c < contactCnt; ++c) {
            const auto& ct = recTypeAContacts[c];
            int s = assignedSlots[c];
            if (s < 0) {
                for (int k = 0; k < kMaxPhysSlots; ++k) {
                    if (!slots[k].down && !usedSlot[k]) {
                        s = k;
                        usedSlot[k] = true;
                        slots[s].pendingDown = true;
                        break;
                    }
                }
            }
            if (s >= 0) {
                slots[s].rawX = ct.x;
                slots[s].rawY = ct.y;
                slots[s].hasX = true;
                slots[s].hasY = true;
            }
        }
        recTypeAContacts.clear();
        recTypeACur = TypeAContact{};
        commitSynFrame(frameNs);
    };

    struct pollfd pfds[2] = {
        {wFd, POLLIN, 0},
        {pFd, POLLIN, 0}
    };
    struct input_event evBuf[64];

    while (recRunning_.load()) {
        int rc = poll(pfds, 2, 200);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pfds[0].revents & POLLIN) {
            uint64_t drained = 0;
            while (read(wFd, &drained, sizeof(drained)) > 0) {}
            if (!recRunning_.load()) break;
        }
        if (!(pfds[1].revents & POLLIN)) {
            continue;
        }
        while (recRunning_.load()) {
            ssize_t n = read(pFd, evBuf, sizeof(evBuf));
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                if (errno == EINTR) continue;
                recRunning_.store(false);
                break;
            }
            if (n == 0) break;

            const size_t count = static_cast<size_t>(n) / sizeof(struct input_event);
            for (size_t i = 0; i < count; ++i) {
                const struct input_event& ev = evBuf[i];
                if (!descCopy.isTypeB) {
                    if (ev.type == EV_ABS) {
                        if (ev.code == ABS_MT_POSITION_X) {
                            recTypeACur.hasX = true;
                            recTypeACur.x = ev.value;
                        } else if (ev.code == ABS_MT_POSITION_Y) {
                            recTypeACur.hasY = true;
                            recTypeACur.y = ev.value;
                        }
                    } else if (ev.type == EV_SYN && ev.code == SYN_MT_REPORT) {
                        if (recTypeACur.hasX || recTypeACur.hasY) {
                            recTypeAContacts.push_back(recTypeACur);
                        }
                        recTypeACur = TypeAContact{};
                    } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                        commitTypeAFrame(nowMonotonicNs());
                    } else if (ev.type == EV_SYN && ev.code == SYN_DROPPED) {
                        recTypeAContacts.clear();
                        recTypeACur = TypeAContact{};
                        commitTypeAFrame(nowMonotonicNs());
                    }
                    continue;
                }

                // Type-B event parsing
                if (ev.type == EV_SYN) {
                    if (ev.code == SYN_REPORT) {
                        commitSynFrame(nowMonotonicNs());
                    } else if (ev.code == SYN_DROPPED) {
                        for (int s = 0; s < kMaxPhysSlots; ++s) {
                            if (slots[s].down) slots[s].pendingUp = true;
                        }
                        commitSynFrame(nowMonotonicNs());
                    }
                } else if (ev.type == EV_ABS) {
                    if (ev.code == ABS_MT_SLOT) {
                        curSlot = std::clamp(ev.value, 0, kMaxPhysSlots - 1);
                    } else if (ev.code == ABS_MT_TRACKING_ID) {
                        if (ev.value >= 0) {
                            slots[curSlot].pendingDown = true;
                            slots[curSlot].pendingUp = false;
                        } else if (slots[curSlot].down || slots[curSlot].pendingDown) {
                            slots[curSlot].pendingUp = true;
                        }
                    } else if (ev.code == ABS_MT_POSITION_X) {
                        slots[curSlot].rawX = ev.value;
                        slots[curSlot].hasX = true;
                        if (!slots[curSlot].down && !slots[curSlot].pendingDown) {
                            slots[curSlot].pendingDown = true;
                        }
                    } else if (ev.code == ABS_MT_POSITION_Y) {
                        slots[curSlot].rawY = ev.value;
                        slots[curSlot].hasY = true;
                        if (!slots[curSlot].down && !slots[curSlot].pendingDown) {
                            slots[curSlot].pendingDown = true;
                        }
                    }
                }
            }
        }
    }

    // Finalize any still-pressed non-excluded strokes cleanly before returning
    const int64_t endNs = nowMonotonicNs();
    if (recStarted_.load()) {
        std::lock_guard<std::mutex> lock(recMutex_);
        for (int s = 0; s < kMaxPhysSlots; ++s) {
            if (slots[s].down && !slots[s].excluded) {
                int64_t tMs = std::max<int64_t>(slots[s].stroke.downMs + 8, (endNs - recFirstTouchNs_) / 1000000LL);
                slots[s].stroke.upMs = tMs;
                if (slots[s].stroke.points.empty() || slots[s].stroke.points.back().tMs != tMs) {
                    slots[s].stroke.points.push_back(RecordedPoint{tMs, slots[s].lastX, slots[s].lastY});
                }
                recStrokes_.push_back(std::move(slots[s].stroke));
            }
        }
        recActiveFingers_ = 0;
    }
}

}  // namespace touchmux
