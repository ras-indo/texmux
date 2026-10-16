/* termux-api-jni.c — JNI bridge for libtermux-api.so
 *
 * Provides Java with direct access to C utility functions used by
 * the Termux API protocol: UUID generation, JSON escaping, Base64
 * encoding, and JSON command building.
 *
 * This eliminates the need for a socket round-trip when Java code
 * initiates API calls internally (plugins, widgets, automation).
 * For shell-initiated calls the socket is still used, but the C
 * side of the protocol is now directly accessible from Java.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "termux-api.h"

/* ─── Helper: jstring → char* ─────────────────────────────────── */

static char* jstring_to_cstr(JNIEnv* env, jstring js) {
    if (!js) return NULL;
    const char* utf = (*env)->GetStringUTFChars(env, js, NULL);
    if (!utf) return NULL;
    char* copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, js, utf);
    return copy;
}

/* ─── NativeBridge.generateUuid() → String ────────────────────── */

JNIEXPORT jstring JNICALL
Java_com_termux_api_NativeBridge_generateUuid(JNIEnv* env, jclass clazz) {
    (void)clazz;
    char buf[64];
    generate_uuid(buf);
    return (*env)->NewStringUTF(env, buf);
}

/* ─── NativeBridge.jsonEscape(String) → String ────────────────── */

JNIEXPORT jstring JNICALL
Java_com_termux_api_NativeBridge_jsonEscape(JNIEnv* env, jclass clazz, jstring input) {
    (void)clazz;
    char* cstr = jstring_to_cstr(env, input);
    if (!cstr) return (*env)->NewStringUTF(env, "null");
    char* escaped = json_escape(cstr);
    free(cstr);
    if (!escaped) return (*env)->NewStringUTF(env, "null");
    jstring result = (*env)->NewStringUTF(env, escaped);
    free(escaped);
    return result;
}

/* ─── NativeBridge.b64Encode(byte[]) → String ─────────────────── */

JNIEXPORT jstring JNICALL
Java_com_termux_api_NativeBridge_b64Encode(JNIEnv* env, jclass clazz, jbyteArray data) {
    (void)clazz;
    if (!data) return (*env)->NewStringUTF(env, "");
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return (*env)->NewStringUTF(env, "");

    size_t out_len;
    char* encoded = b64_encode((const uint8_t*)bytes, (size_t)len, &out_len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    if (!encoded) return (*env)->NewStringUTF(env, "");
    jstring result = (*env)->NewStringUTF(env, encoded);
    free(encoded);
    return result;
}

/* ─── NativeBridge.buildJsonCommand(String[]) → String ──────────
 *
 * Converts Java String[] argv to C argc/argv and calls
 * build_json_command().  Returns the JSON command string.
 * Returns empty string on failure.
 */

JNIEXPORT jstring JNICALL
Java_com_termux_api_NativeBridge_buildJsonCommand(JNIEnv* env, jclass clazz,
                                                   jobjectArray javaArgs) {
    (void)clazz;
    if (!javaArgs) return (*env)->NewStringUTF(env, "");

    jsize argc = (*env)->GetArrayLength(env, javaArgs);
    if (argc < 1) return (*env)->NewStringUTF(env, "");

    /* Convert String[] to char* argv (including argv[0] = program name) */
    char** argv = calloc((size_t)argc + 1, sizeof(char*));
    if (!argv) return (*env)->NewStringUTF(env, "");

    for (jsize i = 0; i < argc; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, javaArgs, i);
        argv[i] = jstring_to_cstr(env, js);
        (*env)->DeleteLocalRef(env, js);
    }
    argv[argc] = NULL;

    /* Build JSON */
    size_t cmd_len;
    char* json = build_json_command((int)argc, argv, &cmd_len);

    /* Cleanup */
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    if (!json) return (*env)->NewStringUTF(env, "");
    jstring result = (*env)->NewStringUTF(env, json);
    free(json);
    return result;
}

/* ─── NativeBridge.nativeCreateFileServerSocket(String) → FileDescriptor ──
 *
 * Creates a real filesystem Unix domain socket (AF_UNIX, SOCK_STREAM),
 * binds+listens at the given path, and returns the fd as a Java
 * FileDescriptor.  The caller wraps this with LocalServerSocket(fd).
 *
 * Unlike LocalServerSocket(String) (abstract namespace only), this
 * creates a proper filesystem socket accessible from proot.
 */

JNIEXPORT jobject JNICALL
Java_com_termux_api_NativeBridge_nativeCreateFileServerSocket(
    JNIEnv* env, jclass clazz, jstring path)
{
    (void)clazz;
    if (!path) return NULL;

    const char* cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) return NULL;

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd == -1) { (*env)->ReleaseStringUTFChars(env, path, cpath); return NULL; }

    unlink(cpath);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t plen = strlen(cpath);
    if (plen >= sizeof(addr.sun_path)) { close(fd); (*env)->ReleaseStringUTFChars(env, path, cpath); return NULL; }
    memcpy(addr.sun_path, cpath, plen);

    socklen_t alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + plen);
    if (bind(fd, (struct sockaddr*)&addr, alen) == -1 || listen(fd, 5) == -1) {
        int saved = errno;
        close(fd); unlink(cpath);
        (*env)->ReleaseStringUTFChars(env, path, cpath);
        return NULL;
    }

    (*env)->ReleaseStringUTFChars(env, path, cpath);

    jclass fdClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    if (!fdClass) { close(fd); return NULL; }
    jmethodID ctor = (*env)->GetMethodID(env, fdClass, "<init>", "()V");
    if (!ctor) { close(fd); return NULL; }
    jobject fdObj = (*env)->NewObject(env, fdClass, ctor);
    if (!fdObj) { close(fd); return NULL; }

    jfieldID fdField = (*env)->GetFieldID(env, fdClass, "descriptor", "I");
    if (!fdField) fdField = (*env)->GetFieldID(env, fdClass, "fd", "I");
    if (!fdField) { close(fd); return NULL; }
    (*env)->SetIntField(env, fdObj, fdField, fd);

    return fdObj;
}
