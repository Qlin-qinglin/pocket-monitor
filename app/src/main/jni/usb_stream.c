#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

JNIEXPORT jint JNICALL
Java_com_qlinqinglin_pocketmonitor_UsbStream_clearHalt(
        JNIEnv *env, jobject instance, jint fd, jint endpoint_address) {
    (void)env;
    (void)instance;
    unsigned int endpoint = (unsigned int)endpoint_address;
    int result;
    do {
        result = ioctl(fd, USBDEVFS_CLEAR_HALT, &endpoint);
    } while (result < 0 && errno == EINTR);
    return result < 0 ? -errno : 0;
}
