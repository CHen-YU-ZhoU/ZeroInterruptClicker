package com.zerointerrupt.clicker;

import com.zerointerrupt.clicker.ClickTargetParceled;

interface ITouchMuxService {
    String probeCapabilities(String nativeLibDir);
    int initNativeEngine(String nativeLibDir, int displayWidth, int displayHeight, int rotation);
    int startSelfCheckGrab(String nativeLibDir, int displayWidth, int displayHeight, int rotation, int timeoutMs);
    boolean confirmSelfCheck();
    void cancelSelfCheckAndUngrab();
    int startClicking(in List<ClickTargetParceled> targets, int displayWidth, int displayHeight, int rotation, boolean allowFallback);
    void updateTargets(in List<ClickTargetParceled> targets, int displayWidth, int displayHeight, int rotation);
    void updateDisplayMetrics(int displayWidth, int displayHeight, int rotation);
    void stopClicking();
    void shutdownEngine();
    int getEngineMode();
    String getStatusDetail();
    void destroy();
}
