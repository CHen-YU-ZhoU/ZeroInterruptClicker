package com.zerointerrupt.clicker;

interface ITouchMuxCallback {
    oneway void onVolumeKeyToggleRequested();
    oneway void onEmergencyUngrabTriggered();
    oneway void onSlotStatesChanged(in int[] slots);
    oneway void onClickingCompleted();
}
