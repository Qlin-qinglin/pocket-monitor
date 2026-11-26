#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

JNIEXPORT jint JNICALL
Java_com_qlinqinglin_pocketmonitor_UsbStream_connectionSpeed(
        JNIEnv *env, jobject instance, jint fd) {
    (void)env;
    (void)instance;
    int result;
    do {
        result = ioctl(fd, USBDEVFS_GET_SPEED, 0);
    } while (result < 0 && errno == EINTR);
    return result < 0 ? -errno : result;
}
