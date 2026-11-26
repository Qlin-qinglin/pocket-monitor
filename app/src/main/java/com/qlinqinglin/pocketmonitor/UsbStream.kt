package com.qlinqinglin.pocketmonitor

internal object UsbStream {
    init {
        System.loadLibrary("pocket_usb")
    }

    external fun clearHalt(fd: Int, endpointAddress: Int): Int
}
