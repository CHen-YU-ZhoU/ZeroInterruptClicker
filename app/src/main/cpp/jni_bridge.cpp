#include <jni.h>
#include <pthread.h>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "touch_mux.h"

namespace {

JavaVM* gJvm = nullptr;
pthread_key_t gThreadDetachKey;
pthread_once_t gThreadKeyOnce = PTHREAD_ONCE_INIT;

void detachCurrentThreadOnExit(void* ptr) {
    if (ptr != nullptr && gJvm != nullptr) {
        gJvm->DetachCurrentThread();
    }
}

void createThreadKey() {
    pthread_key_create(&gThreadDetachKey, detachCurrentThreadOnExit);
}

JNIEnv* getAttachedJniEnv() {
    if (gJvm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    jint status = gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status == JNI_EDETACHED) {
        pthread_once(&gThreadKeyOnce, createThreadKey);
        if (gJvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            pthread_setspecific(gThreadDetachKey, reinterpret_cast<void*>(1));
            return env;
        }
    }
    return nullptr;
}

std::unique_ptr<touchmux::TouchMuxEngine> gEngine;
std::mutex gEngineMutex;

struct JniCallbackRefs {
    std::mutex mutex;
    jobject serviceObj = nullptr;
    jintArray hybIds = nullptr;
    jfloatArray hybXs = nullptr;
    jfloatArray hybYs = nullptr;
    jfloatArray hybPressures = nullptr;
    jfloatArray hybSizes = nullptr;
    jintArray slotBuf = nullptr;
    jmethodID midHybridFrame = nullptr;
    jmethodID midVolDoubleTap = nullptr;
    jmethodID midEmergencyUngrab = nullptr;
    jmethodID midSlotChanged = nullptr;
    jmethodID midClickingCompleted = nullptr;
} gCbRefs;

void releaseGlobalRefsLocked(JNIEnv* env) {
    if (env == nullptr) return;
    if (gCbRefs.serviceObj) {
        env->DeleteGlobalRef(gCbRefs.serviceObj);
        gCbRefs.serviceObj = nullptr;
    }
    if (gCbRefs.hybIds) {
        env->DeleteGlobalRef(gCbRefs.hybIds);
        gCbRefs.hybIds = nullptr;
    }
    if (gCbRefs.hybXs) {
        env->DeleteGlobalRef(gCbRefs.hybXs);
        gCbRefs.hybXs = nullptr;
    }
    if (gCbRefs.hybYs) {
        env->DeleteGlobalRef(gCbRefs.hybYs);
        gCbRefs.hybYs = nullptr;
    }
    if (gCbRefs.hybPressures) {
        env->DeleteGlobalRef(gCbRefs.hybPressures);
        gCbRefs.hybPressures = nullptr;
    }
    if (gCbRefs.hybSizes) {
        env->DeleteGlobalRef(gCbRefs.hybSizes);
        gCbRefs.hybSizes = nullptr;
    }
    if (gCbRefs.slotBuf) {
        env->DeleteGlobalRef(gCbRefs.slotBuf);
        gCbRefs.slotBuf = nullptr;
    }
    gCbRefs.midHybridFrame = nullptr;
    gCbRefs.midVolDoubleTap = nullptr;
    gCbRefs.midEmergencyUngrab = nullptr;
    gCbRefs.midSlotChanged = nullptr;
    gCbRefs.midClickingCompleted = nullptr;
}

touchmux::TouchMuxEngine& getOrCreateEngine() {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        gEngine = std::make_unique<touchmux::TouchMuxEngine>();
    }
    return *gEngine;
}

std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        if (c == '"' || c == '\\') {
            out.push_back('\\');
            out.push_back(c);
        } else if (c == '\n') {
            out += "\\n";
        } else {
            out.push_back(c);
        }
    }
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeProbeCapabilities(
    JNIEnv* env,
    jobject /* thiz */) {
    auto probe = touchmux::TouchMuxEngine::probeDevices();
    std::ostringstream oss;
    oss << "{"
        << "\"uinputWritable\":" << (probe.uinputWritable ? "true" : "false") << ","
        << "\"uinputErrno\":" << probe.uinputErrno << ","
        << "\"uhidWritable\":" << (probe.uhidWritable ? "true" : "false") << ","
        << "\"uhidErrno\":" << probe.uhidErrno << ","
        << "\"touchDeviceReadable\":" << (probe.touchDeviceReadable ? "true" : "false") << ","
        << "\"touchDeviceErrno\":" << probe.touchDeviceErrno << ","
        << "\"touchDevicePath\":\"" << jsonEscape(probe.touchDevicePath) << "\","
        << "\"touchDeviceName\":\"" << jsonEscape(probe.touchDeviceName) << "\","
        << "\"physSlotCount\":" << probe.physSlotCount << ","
        << "\"isTypeB\":" << (probe.isTypeB ? "true" : "false") << ","
        << "\"absMinX\":" << probe.absMinX << ","
        << "\"absMaxX\":" << probe.absMaxX << ","
        << "\"absMinY\":" << probe.absMinY << ","
        << "\"absMaxY\":" << probe.absMaxY
        << "}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jint JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeCleanupOrphans(
    JNIEnv* env,
    jobject /* thiz */,
    jstring processName) {
    std::string target;
    if (processName != nullptr) {
        const char* chars = env->GetStringUTFChars(processName, nullptr);
        if (chars != nullptr) {
            target = chars;
            env->ReleaseStringUTFChars(processName, chars);
        }
    }
    return static_cast<jint>(touchmux::TouchMuxEngine::cleanupOrphanProcesses(target));
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeRegisterCallbacks(
    JNIEnv* env,
    jobject thiz,
    jintArray hybIds,
    jfloatArray hybXs,
    jfloatArray hybYs,
    jfloatArray hybPressures,
    jfloatArray hybSizes,
    jintArray slotBuf) {
    auto& engine = getOrCreateEngine();
    {
        std::lock_guard<std::mutex> lock(gCbRefs.mutex);
        releaseGlobalRefsLocked(env);

        jclass cls = env->GetObjectClass(thiz);
        gCbRefs.serviceObj = env->NewGlobalRef(thiz);
        gCbRefs.hybIds = reinterpret_cast<jintArray>(env->NewGlobalRef(hybIds));
        gCbRefs.hybXs = reinterpret_cast<jfloatArray>(env->NewGlobalRef(hybXs));
        gCbRefs.hybYs = reinterpret_cast<jfloatArray>(env->NewGlobalRef(hybYs));
        gCbRefs.hybPressures = reinterpret_cast<jfloatArray>(env->NewGlobalRef(hybPressures));
        gCbRefs.hybSizes = reinterpret_cast<jfloatArray>(env->NewGlobalRef(hybSizes));
        gCbRefs.slotBuf = reinterpret_cast<jintArray>(env->NewGlobalRef(slotBuf));

        gCbRefs.midHybridFrame = env->GetMethodID(cls, "onNativeHybridMotionFrame", "(JJII)V");
        gCbRefs.midVolDoubleTap = env->GetMethodID(cls, "onNativeVolumeKeyDoubleTap", "()V");
        gCbRefs.midEmergencyUngrab = env->GetMethodID(cls, "onNativeEmergencyUngrab", "()V");
        gCbRefs.midSlotChanged = env->GetMethodID(cls, "onNativeSlotStatesChanged", "()V");
        gCbRefs.midClickingCompleted = env->GetMethodID(cls, "onNativeClickingCompleted", "()V");
        env->DeleteLocalRef(cls);
    }

    touchmux::EngineCallbacks cbs;
    cbs.onHybridMotionFrame = [](int64_t downTimeMs,
                                 int64_t eventTimeMs,
                                 int32_t action,
                                 int32_t pointerCount,
                                 const int32_t* pointerIds,
                                 const float* xs,
                                 const float* ys,
                                 const float* pressures,
                                 const float* sizes) {
        JNIEnv* cbEnv = getAttachedJniEnv();
        if (cbEnv == nullptr || pointerCount <= 0) return;

        std::lock_guard<std::mutex> lock(gCbRefs.mutex);
        if (gCbRefs.serviceObj == nullptr || gCbRefs.midHybridFrame == nullptr) return;

        cbEnv->SetIntArrayRegion(gCbRefs.hybIds, 0, pointerCount, pointerIds);
        cbEnv->SetFloatArrayRegion(gCbRefs.hybXs, 0, pointerCount, xs);
        cbEnv->SetFloatArrayRegion(gCbRefs.hybYs, 0, pointerCount, ys);
        cbEnv->SetFloatArrayRegion(gCbRefs.hybPressures, 0, pointerCount, pressures);
        cbEnv->SetFloatArrayRegion(gCbRefs.hybSizes, 0, pointerCount, sizes);

        cbEnv->CallVoidMethod(
            gCbRefs.serviceObj,
            gCbRefs.midHybridFrame,
            static_cast<jlong>(downTimeMs),
            static_cast<jlong>(eventTimeMs),
            static_cast<jint>(action),
            static_cast<jint>(pointerCount)
        );
        if (cbEnv->ExceptionCheck()) {
            cbEnv->ExceptionClear();
        }
    };

    cbs.onVolumeKeyDoubleTap = []() {
        JNIEnv* cbEnv = getAttachedJniEnv();
        if (cbEnv == nullptr) return;
        std::lock_guard<std::mutex> lock(gCbRefs.mutex);
        if (gCbRefs.serviceObj == nullptr || gCbRefs.midVolDoubleTap == nullptr) return;
        cbEnv->CallVoidMethod(gCbRefs.serviceObj, gCbRefs.midVolDoubleTap);
        if (cbEnv->ExceptionCheck()) {
            cbEnv->ExceptionClear();
        }
    };

    cbs.onEmergencyUngrab = []() {
        JNIEnv* cbEnv = getAttachedJniEnv();
        if (cbEnv == nullptr) return;
        std::lock_guard<std::mutex> lock(gCbRefs.mutex);
        if (gCbRefs.serviceObj == nullptr || gCbRefs.midEmergencyUngrab == nullptr) return;
        cbEnv->CallVoidMethod(gCbRefs.serviceObj, gCbRefs.midEmergencyUngrab);
        if (cbEnv->ExceptionCheck()) {
            cbEnv->ExceptionClear();
        }
    };

    cbs.onSlotStatesChanged = [](const int32_t slots[touchmux::kMaxUinputSlots]) {
        JNIEnv* cbEnv = getAttachedJniEnv();
        if (cbEnv == nullptr) return;
        std::lock_guard<std::mutex> lock(gCbRefs.mutex);
        if (gCbRefs.serviceObj == nullptr || gCbRefs.midSlotChanged == nullptr || gCbRefs.slotBuf == nullptr) return;
        cbEnv->SetIntArrayRegion(gCbRefs.slotBuf, 0, touchmux::kMaxUinputSlots, slots);
        cbEnv->CallVoidMethod(gCbRefs.serviceObj, gCbRefs.midSlotChanged);
        if (cbEnv->ExceptionCheck()) {
            cbEnv->ExceptionClear();
        }
    };

    cbs.onClickingCompleted = []() {
        JNIEnv* cbEnv = getAttachedJniEnv();
        if (cbEnv == nullptr) return;
        std::lock_guard<std::mutex> lock(gCbRefs.mutex);
        if (gCbRefs.serviceObj == nullptr || gCbRefs.midClickingCompleted == nullptr) return;
        cbEnv->CallVoidMethod(gCbRefs.serviceObj, gCbRefs.midClickingCompleted);
        if (cbEnv->ExceptionCheck()) {
            cbEnv->ExceptionClear();
        }
    };

    engine.setCallbacks(std::move(cbs));
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeSetVolumeKeyEnabled(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jboolean enabled) {
    getOrCreateEngine().setVolumeKeyControlEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeInitAndGrab(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint displayW,
    jint displayH,
    jint rotation,
    jint selfCheckTimeoutMs,
    jint preferredBackend) {
    return getOrCreateEngine().initAndGrab(
        displayW, displayH, rotation, selfCheckTimeoutMs, preferredBackend);
}

JNIEXPORT jboolean JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeConfirmSelfCheck(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return getOrCreateEngine().confirmSelfCheck() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeSetTargetsAndRunning(
    JNIEnv* env,
    jobject /* thiz */,
    jintArray ids,
    jfloatArray xs,
    jfloatArray ys,
    jlongArray intervalsMs,
    jlongArray pressDurationsMs,
    jbooleanArray jitters,
    jbooleanArray enabledFlags,
    jintArray actionTypes,
    jfloatArray endXs,
    jfloatArray endYs,
    jlongArray swipeDurationsMs,
    jintArray repeatCounts,
    jintArray stageIndices,
    jlongArray stageDurationsMs,
    jlongArray stageGapsMs,
    jintArray stageRepeatCounts,
    jintArray holdInIds,
    jintArray holdOutIds,
    jboolean running,
    jint displayW,
    jint displayH,
    jint rotation,
    jfloat jitterRadiusPx,
    jint executionMode,
    jint presetLoopCount,
    jboolean forceReset) {
    const jsize count = ids ? env->GetArrayLength(ids) : 0;
    std::vector<touchmux::NativeClickTarget> targets;
    if (count > 0) {
        jint* idPtr = env->GetIntArrayElements(ids, nullptr);
        jfloat* xPtr = env->GetFloatArrayElements(xs, nullptr);
        jfloat* yPtr = env->GetFloatArrayElements(ys, nullptr);
        jlong* intPtr = env->GetLongArrayElements(intervalsMs, nullptr);
        jlong* pressPtr = env->GetLongArrayElements(pressDurationsMs, nullptr);
        jboolean* jitPtr = env->GetBooleanArrayElements(jitters, nullptr);
        jboolean* enPtr = env->GetBooleanArrayElements(enabledFlags, nullptr);
        jint* actPtr = actionTypes ? env->GetIntArrayElements(actionTypes, nullptr) : nullptr;
        jfloat* exPtr = endXs ? env->GetFloatArrayElements(endXs, nullptr) : nullptr;
        jfloat* eyPtr = endYs ? env->GetFloatArrayElements(endYs, nullptr) : nullptr;
        jlong* sdPtr = swipeDurationsMs ? env->GetLongArrayElements(swipeDurationsMs, nullptr) : nullptr;
        jint* repPtr = repeatCounts ? env->GetIntArrayElements(repeatCounts, nullptr) : nullptr;
        jint* stIdxPtr = stageIndices ? env->GetIntArrayElements(stageIndices, nullptr) : nullptr;
        jlong* stDurPtr = stageDurationsMs ? env->GetLongArrayElements(stageDurationsMs, nullptr) : nullptr;
        jlong* stGapPtr = stageGapsMs ? env->GetLongArrayElements(stageGapsMs, nullptr) : nullptr;
        jint* stRepPtr = stageRepeatCounts ? env->GetIntArrayElements(stageRepeatCounts, nullptr) : nullptr;
        jint* holdInPtr = holdInIds ? env->GetIntArrayElements(holdInIds, nullptr) : nullptr;
        jint* holdOutPtr = holdOutIds ? env->GetIntArrayElements(holdOutIds, nullptr) : nullptr;

        targets.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            touchmux::NativeClickTarget t;
            t.id = idPtr[i];
            t.x = xPtr[i];
            t.y = yPtr[i];
            t.intervalMs = intPtr[i];
            t.pressDurationMs = pressPtr[i];
            t.microJitter = jitPtr[i] == JNI_TRUE;
            t.enabled = enPtr[i] == JNI_TRUE;
            t.actionType = actPtr ? actPtr[i] : 0;
            t.endX = exPtr ? exPtr[i] : t.x;
            t.endY = eyPtr ? eyPtr[i] : t.y;
            t.swipeDurationMs = sdPtr ? sdPtr[i] : 300;
            t.repeatCount = repPtr ? repPtr[i] : 0;
            t.stageIndex = stIdxPtr ? stIdxPtr[i] : 0;
            t.stageDurationMs = stDurPtr ? stDurPtr[i] : 0;
            t.stageGapMs = stGapPtr ? stGapPtr[i] : 50;
            t.stageRepeatCount = stRepPtr ? stRepPtr[i] : 1;
            t.holdInId = holdInPtr ? holdInPtr[i] : 0;
            t.holdOutId = holdOutPtr ? holdOutPtr[i] : 0;
            targets.push_back(t);
        }

        env->ReleaseIntArrayElements(ids, idPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(xs, xPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(ys, yPtr, JNI_ABORT);
        env->ReleaseLongArrayElements(intervalsMs, intPtr, JNI_ABORT);
        env->ReleaseLongArrayElements(pressDurationsMs, pressPtr, JNI_ABORT);
        env->ReleaseBooleanArrayElements(jitters, jitPtr, JNI_ABORT);
        env->ReleaseBooleanArrayElements(enabledFlags, enPtr, JNI_ABORT);
        if (actPtr) env->ReleaseIntArrayElements(actionTypes, actPtr, JNI_ABORT);
        if (exPtr) env->ReleaseFloatArrayElements(endXs, exPtr, JNI_ABORT);
        if (eyPtr) env->ReleaseFloatArrayElements(endYs, eyPtr, JNI_ABORT);
        if (sdPtr) env->ReleaseLongArrayElements(swipeDurationsMs, sdPtr, JNI_ABORT);
        if (repPtr) env->ReleaseIntArrayElements(repeatCounts, repPtr, JNI_ABORT);
        if (stIdxPtr) env->ReleaseIntArrayElements(stageIndices, stIdxPtr, JNI_ABORT);
        if (stDurPtr) env->ReleaseLongArrayElements(stageDurationsMs, stDurPtr, JNI_ABORT);
        if (stGapPtr) env->ReleaseLongArrayElements(stageGapsMs, stGapPtr, JNI_ABORT);
        if (stRepPtr) env->ReleaseIntArrayElements(stageRepeatCounts, stRepPtr, JNI_ABORT);
        if (holdInPtr) env->ReleaseIntArrayElements(holdInIds, holdInPtr, JNI_ABORT);
        if (holdOutPtr) env->ReleaseIntArrayElements(holdOutIds, holdOutPtr, JNI_ABORT);
    }

    getOrCreateEngine().setTargetsAndRunning(
        targets,
        running == JNI_TRUE,
        displayW,
        displayH,
        rotation,
        jitterRadiusPx,
        executionMode,
        presetLoopCount,
        forceReset == JNI_TRUE
    );
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeUpdateDisplayMetrics(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint displayW,
    jint displayH,
    jint rotation) {
    getOrCreateEngine().setDisplayMetrics(displayW, displayH, rotation);
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeStopClicking(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    getOrCreateEngine().stopClicking();
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeShutdown(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) {
        gEngine->shutdown();
    }
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeDestroyEngine(
    JNIEnv* env,
    jobject /* thiz */) {
    {
        std::lock_guard<std::mutex> lock(gEngineMutex);
        gEngine.reset();
    }
    std::lock_guard<std::mutex> cbLock(gCbRefs.mutex);
    releaseGlobalRefsLocked(env);
}

JNIEXPORT jint JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeGetState(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return static_cast<jint>(getOrCreateEngine().getState());
}

JNIEXPORT jint JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeGetBackendMode(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return static_cast<jint>(getOrCreateEngine().getBackendMode());
}

JNIEXPORT jboolean JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeDidRollback(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return getOrCreateEngine().didSelfCheckRollback() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeGetLastError(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(getOrCreateEngine().getLastError().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeGetTouchDeviceInfo(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(getOrCreateEngine().getTouchDeviceInfo().c_str());
}

JNIEXPORT jintArray JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeGetSlotStates(
    JNIEnv* env,
    jobject /* thiz */) {
    int32_t slots[touchmux::kMaxUinputSlots]{};
    getOrCreateEngine().getSlotStates(slots);
    jintArray out = env->NewIntArray(touchmux::kMaxUinputSlots);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, touchmux::kMaxUinputSlots, slots);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeInjectRpcTap(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat x,
    jfloat y,
    jlong durationMs) {
    getOrCreateEngine().enqueueRpcTap(x, y, durationMs);
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeInjectRpcSwipe(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat x1,
    jfloat y1,
    jfloat x2,
    jfloat y2,
    jlong durationMs) {
    getOrCreateEngine().enqueueRpcSwipe(x1, y1, x2, y2, durationMs);
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeSetRpcHold(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint holdId,
    jboolean down,
    jfloat x,
    jfloat y) {
    getOrCreateEngine().setRpcHold(holdId, down == JNI_TRUE, x, y);
}

JNIEXPORT jboolean JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeStartRecording(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint displayW,
    jint displayH,
    jint rotation,
    jint excludeLeft,
    jint excludeTop,
    jint excludeRight,
    jint excludeBottom) {
    bool ok = getOrCreateEngine().startRecording(
        displayW, displayH, rotation,
        excludeLeft, excludeTop, excludeRight, excludeBottom
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeUpdateRecordingExcludeRect(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint excludeLeft,
    jint excludeTop,
    jint excludeRight,
    jint excludeBottom) {
    getOrCreateEngine().updateRecordingExcludeRect(
        excludeLeft, excludeTop, excludeRight, excludeBottom
    );
}

JNIEXPORT jlongArray JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeGetRecordingStats(
    JNIEnv* env,
    jobject /* thiz */) {
    int64_t stats[4]{};
    getOrCreateEngine().getRecordingStats(stats);
    jlongArray out = env->NewLongArray(4);
    if (out != nullptr) {
        jlong jStats[4] = {stats[0], stats[1], stats[2], stats[3]};
        env->SetLongArrayRegion(out, 0, 4, jStats);
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeStopRecording(
    JNIEnv* env,
    jobject /* thiz */) {
    std::string json = getOrCreateEngine().stopRecording(false);
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeCancelRecording(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    getOrCreateEngine().stopRecording(true);
}

}  // extern "C"
