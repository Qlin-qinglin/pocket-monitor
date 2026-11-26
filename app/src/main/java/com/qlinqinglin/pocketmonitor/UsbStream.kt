package com.qlinqinglin.pocketmonitor

internal object UsbStream {
    init {
        System.loadLibrary("pocket_usb")
    }

    external fun connectionSpeed(fd: Int): Int
}
