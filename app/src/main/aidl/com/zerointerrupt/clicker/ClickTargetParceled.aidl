package com.zerointerrupt.clicker;

parcelable ClickTargetParceled {
    int id;
    float x;
    float y;
    long intervalMs;
    long pressDurationMs;
    boolean microJitter;
    boolean enabled;
}
