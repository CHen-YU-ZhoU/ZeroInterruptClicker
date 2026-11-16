package com.zerointerrupt.clicker;

parcelable ClickTargetParceled {
    int id;
    float x;
    float y;
    long intervalMs;
    long pressDurationMs;
    boolean microJitter;
    boolean enabled;
    int actionType;
    float endX;
    float endY;
    long swipeDurationMs;
    int repeatCount;
    int stageIndex;
    long stageDurationMs;
    long stageGapMs;
    int stageRepeatCount;
    int holdInId;
    int holdOutId;
}
