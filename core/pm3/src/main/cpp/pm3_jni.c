// JNI bridge to the Proxmark3 client (RfidResearchGroup/proxmark3, iceman fork).
//
// The client is compiled into this same shared library, so there is no separate
// process, no PTY and no Termux involved: commands are dispatched directly
// through pm3_console() and their output is captured with the client's own
// "grabber" (see ui.c).
//
// Where pm3_open() (the upstream entry point) calls exit() when no device is
// found, this open() sequence reports the failure instead: killing the app
// process is not an acceptable error mode.
//
// Note on pm3_grabbed_output_get(): the library keeps ownership of the returned
// buffer (it is reused by the next grab), so it must not be freed here.
//
// SPDX-License-Identifier: GPL-3.0-or-later

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "comms.h"
#include "pm3.h"
#include "preferences.h"
#include "proxmark3.h"
#include "usart_defs.h"
#include "ui.h"

// The client is not thread-safe for command dispatch; serialise everything.
static pthread_mutex_t g_api_lock = PTHREAD_MUTEX_INITIALIZER;
// Guards pm3_present transitions against concurrent streaming grabs / closes.
static pthread_mutex_t g_grab_state_lock = PTHREAD_MUTEX_INITIALIZER;

// Route client output of the call in progress into the grabber, so it can be
// returned to Java instead of being printed to stdout (which goes nowhere).
static void grab_arm(uint8_t *previous) {
    *previous = g_printAndLog;
    g_printAndLog |= PRINTANDLOG_GRAB;
    g_printAndLog &= (uint8_t) ~PRINTANDLOG_PRINT;
}

// Sanitises arbitrary byte output into valid Modified UTF-8 (BMP only, no raw
// NULs or invalid byte sequences) so ART's NewStringUTF() can never abort the
// process on binary/4-byte sequences printed by scripts or dumps.
static char *sanitize_for_jni_utf8(const char *src) {
    if (src == NULL) {
        return NULL;
    }
    size_t len = strlen(src);
    char *out = (char *) malloc(len + 1);
    if (out == NULL) {
        return NULL;
    }
    size_t i = 0;
    size_t o = 0;
    while (i < len) {
        unsigned char c = (unsigned char) src[i];
        if (c >= 0x01 && c <= 0x7F) {
            out[o++] = (char) c;
            i++;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len) {
            unsigned char c1 = (unsigned char) src[i + 1];
            unsigned int cp = ((unsigned int) (c & 0x1F) << 6) | (c1 & 0x3F);
            if ((c1 & 0xC0) == 0x80 && cp >= 0x80) {
                out[o++] = (char) c;
                out[o++] = (char) c1;
                i += 2;
            } else {
                out[o++] = '?';
                i++;
            }
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
            unsigned char c1 = (unsigned char) src[i + 1];
            unsigned char c2 = (unsigned char) src[i + 2];
            unsigned int cp = ((unsigned int) (c & 0x0F) << 12) |
                              ((unsigned int) (c1 & 0x3F) << 6) |
                              (c2 & 0x3F);
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80 &&
                cp >= 0x800 && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                out[o++] = (char) c;
                out[o++] = (char) c1;
                out[o++] = (char) c2;
                i += 3;
            } else {
                out[o++] = '?';
                i++;
            }
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len) {
            // 4-byte UTF-8 is rejected by JNI NewStringUTF (expects CESU-8);
            // replace the whole sequence with '?' to avoid ART fatal aborts.
            unsigned char c1 = (unsigned char) src[i + 1];
            unsigned char c2 = (unsigned char) src[i + 2];
            unsigned char c3 = (unsigned char) src[i + 3];
            out[o++] = '?';
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80) {
                i += 4;
            } else {
                i++;
            }
        } else {
            out[o++] = '?';
            i++;
        }
    }
    out[o] = '\0';
    return out;
}

// Takes ownership of a grabber copy and turns it into a Java string.
static jstring grab_to_string(JNIEnv *env, char *text) {
    char *safe = sanitize_for_jni_utf8(text);
    free(text);
    jstring result = (*env)->NewStringUTF(env, safe != NULL ? safe : "");
    free(safe);
    if (result == NULL) {
        (*env)->ExceptionClear(env);
        result = (*env)->NewStringUTF(env, "");
    }
    return result;
}

// Called once, before anything that touches $HOME (preferences, resources,
// dumps): pm3 derives the user directory from $HOME, which is unset on Android.
static jboolean native_init(JNIEnv *env, __attribute__((unused)) jclass clazz, jstring home) {
    const char *home_chars = (*env)->GetStringUTFChars(env, home, NULL);
    if (home_chars == NULL) {
        return JNI_FALSE;
    }
    setenv("HOME", home_chars, 1);
    (*env)->ReleaseStringUTFChars(env, home, home_chars);
    return JNI_TRUE;
}

// Mirrors pm3_open() (client/src/pm3.c) but reports failure as a status.

static jstring native_open(JNIEnv *env, __attribute__((unused)) jclass clazz, jstring port) {
    const char *port_name = (*env)->GetStringUTFChars(env, port, NULL);
    if (port_name == NULL) {
        return NULL;
    }

    pthread_mutex_lock(&g_api_lock);

    uint8_t previous;
    grab_arm(&previous);
    pm3_init();
    preferences_load();
    // Enable ANSI colour emission so the Android console receives the same
    // token-level colours as the desktop Proxmark3 terminal.
    g_session.supports_colors = true;
    OpenProxmark(&g_session.current_device, port_name, false, 20, false, USART_BAUD_RATE);
    if (g_session.pm3_present && TestProxmark(g_session.current_device) != PM3_SUCCESS) {
        CloseProxmark(g_session.current_device);
    }
    g_printAndLog = previous;
    char *captured = grabber_take_all();

    pthread_mutex_unlock(&g_api_lock);

    (*env)->ReleaseStringUTFChars(env, port, port_name);
    return grab_to_string(env, captured);
}

static void native_close(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    (void) env;
    pthread_mutex_lock(&g_api_lock);
    pthread_mutex_lock(&g_grab_state_lock);
    if (g_session.pm3_present) {
        pm3_close(g_session.current_device);
    }
    free_grabber();
    pthread_mutex_unlock(&g_grab_state_lock);
    pthread_mutex_unlock(&g_api_lock);
}

static jboolean native_is_open(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    (void) env;
    pthread_mutex_lock(&g_grab_state_lock);
    jboolean open = g_session.pm3_present ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock(&g_grab_state_lock);
    return open;
}

// Run one client command and return everything it printed. Blocks for as long
// as the command runs, so callers must invoke it off the main thread.
static jstring native_exec(JNIEnv *env, __attribute__((unused)) jclass clazz, jstring cmd) {
    const char *cmd_chars = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (cmd_chars == NULL) {
        return NULL;
    }
    char *cmd_copy = strdup(cmd_chars);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_chars);
    if (cmd_copy == NULL) {
        return NULL;
    }

    pthread_mutex_lock(&g_api_lock);
    if (g_session.pm3_present == false) {
        pthread_mutex_unlock(&g_api_lock);
        free(cmd_copy);
        return (*env)->NewStringUTF(env, "");
    }
    // A stop request only applies to the command that was running when it was
    // pressed, so the flag starts clean for every new command.
    __atomic_store_n(&g_user_interrupt, false, __ATOMIC_RELEASE);
    pm3_input_cancel();
    g_session.supports_colors = true;
    pm3_console(g_session.current_device, cmd_copy, true, true);
    char *captured = grabber_take_all();
    pthread_mutex_unlock(&g_api_lock);

    free(cmd_copy);
    return grab_to_string(env, captured);
}

// Drops the output accumulated so far, so the UI can stream it while the
// command is still running. Safe to call from another thread.
static jstring native_grab(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    pthread_mutex_lock(&g_grab_state_lock);
    char *chunk = g_session.pm3_present ? grabber_take_copy() : NULL;
    pthread_mutex_unlock(&g_grab_state_lock);
    if (chunk == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    return grab_to_string(env, chunk);
}

// True while a running Lua script is blocked inside io.read() awaiting input.
static jboolean native_is_waiting_input(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    (void) env;
    return pm3_input_is_waiting() ? JNI_TRUE : JNI_FALSE;
}

// Delivers a line typed by the user in the console to a waiting Lua script.
static void native_send_input(JNIEnv *env, __attribute__((unused)) jclass clazz, jstring input) {
    const char *chars = input != NULL ? (*env)->GetStringUTFChars(env, input, NULL) : NULL;
    pm3_input_provide(chars != NULL ? chars : "");
    if (chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, input, chars);
    }
}

// Asks the command in progress to stop: long loops poll g_user_interrupt,
// Lua scripts hit the debug hook and any blocked io.read() wakes immediately.
static void native_abort(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    (void) env;
    __atomic_store_n(&g_user_interrupt, true, __ATOMIC_RELEASE);
    pm3_input_cancel();
}

static JNINativeMethod methods[] = {
    {"nativeInit",           "(Ljava/lang/String;)Z",                  (void *) native_init},
    {"nativeOpen",           "(Ljava/lang/String;)Ljava/lang/String;", (void *) native_open},
    {"nativeClose",          "()V",                                    (void *) native_close},
    {"nativeIsOpen",         "()Z",                                    (void *) native_is_open},
    {"nativeExec",           "(Ljava/lang/String;)Ljava/lang/String;", (void *) native_exec},
    {"nativeGrab",           "()Ljava/lang/String;",                   (void *) native_grab},
    {"nativeIsWaitingInput", "()Z",                                    (void *) native_is_waiting_input},
    {"nativeSendInput",      "(Ljava/lang/String;)V",                  (void *) native_send_input},
    {"nativeAbort",          "()V",                                    (void *) native_abort},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = (*env)->FindClass(env, "com/farmacia/pm3android/pm3/Pm3Native");
    if (clazz == NULL) {
        return JNI_ERR;
    }
    if ((*env)->RegisterNatives(env, clazz, methods,
                                sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        (*env)->DeleteLocalRef(env, clazz);
        return JNI_ERR;
    }
    (*env)->DeleteLocalRef(env, clazz);
    return JNI_VERSION_1_6;
}
