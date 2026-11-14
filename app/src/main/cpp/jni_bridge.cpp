#include <jni.h>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "touch_mux.h"

namespace {

std::unique_ptr<touchmux::TouchMuxEngine> gEngine;
std::mutex gEngineMutex;

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

JNIEXPORT jstring JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeProbeCapabilities(
    JNIEnv* env,
    jobject /* thiz */) {
    auto probe = touchmux::TouchMuxEngine::probeDevices();
    std::ostringstream oss;
    oss << "{"
        << "\"uinputWritable\":" << (probe.uinputWritable ? "true" : "false") << ","
        << "\"uinputErrno\":" << probe.uinputErrno << ","
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
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeInitAndGrab(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint displayW,
    jint displayH,
    jint rotation,
    jint selfCheckTimeoutMs) {
    return getOrCreateEngine().initAndGrab(displayW, displayH, rotation, selfCheckTimeoutMs);
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
    jboolean running,
    jint displayW,
    jint displayH,
    jint rotation,
    jfloat jitterRadiusPx) {
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
            targets.push_back(t);
        }

        env->ReleaseIntArrayElements(ids, idPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(xs, xPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(ys, yPtr, JNI_ABORT);
        env->ReleaseLongArrayElements(intervalsMs, intPtr, JNI_ABORT);
        env->ReleaseLongArrayElements(pressDurationsMs, pressPtr, JNI_ABORT);
        env->ReleaseBooleanArrayElements(jitters, jitPtr, JNI_ABORT);
        env->ReleaseBooleanArrayElements(enabledFlags, enPtr, JNI_ABORT);
    }

    getOrCreateEngine().setTargetsAndRunning(
        targets,
        running == JNI_TRUE,
        displayW,
        displayH,
        rotation,
        jitterRadiusPx
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

JNIEXPORT jint JNICALL
Java_com_zerointerrupt_clicker_shizuku_TouchMuxUserService_nativeGetState(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return static_cast<jint>(getOrCreateEngine().getState());
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

}  // extern "C"
