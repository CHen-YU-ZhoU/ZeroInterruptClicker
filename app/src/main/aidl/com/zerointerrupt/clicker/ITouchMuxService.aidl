package com.zerointerrupt.clicker;

import com.zerointerrupt.clicker.ClickTargetParceled;
import com.zerointerrupt.clicker.ITouchMuxCallback;

interface ITouchMuxService {
    String probeCapabilities(String nativeLibDir);
    int initNativeEngine(String nativeLibDir, int displayWidth, int displayHeight, int rotation, int preferredBackend);
    int startSelfCheckGrab(String nativeLibDir, int displayWidth, int displayHeight, int rotation, int timeoutMs, int preferredBackend);
    boolean confirmSelfCheck();
    void cancelSelfCheckAndUngrab();
    int startClicking(
        in List<ClickTargetParceled> targets,
        int displayWidth,
        int displayHeight,
        int rotation,
        boolean allowFallback,
        int executionMode,
        int presetLoopCount,
        float jitterRadiusPx,
        int preferredBackend
    );
    void updateTargets(
        in List<ClickTargetParceled> targets,
        int displayWidth,
        int displayHeight,
        int rotation,
        int executionMode,
        int presetLoopCount,
        float jitterRadiusPx
    );
    void updateDisplayMetrics(int displayWidth, int displayHeight, int rotation);
    void stopClicking();
    void shutdownEngine();
    int getEngineMode();
    String getStatusDetail();
    int cleanupOrphanProcesses();
    int[] getSlotStates();
    void setPreferredBackend(int preferredBackend);
    void setVolumeKeyControlEnabled(boolean enabled);
    void registerCallback(ITouchMuxCallback callback);
    void injectRpcTap(float x, float y, long durationMs);
    void injectRpcSwipe(float x1, float y1, float x2, float y2, long durationMs);
    void setRpcHold(int holdId, boolean down, float x, float y);
    boolean startTouchRecording(int displayWidth, int displayHeight, int rotation, int excludeLeft, int excludeTop, int excludeRight, int excludeBottom);
    void updateRecordingExcludeRect(int excludeLeft, int excludeTop, int excludeRight, int excludeBottom);
    long[] getTouchRecordingStats();
    String stopTouchRecording();
    void cancelTouchRecording();
    void destroy();
}
