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

// Route client output of the call in progress into the grabber, so it can be
// returned to Java instead of being printed to stdout (which goes nowhere).
static void grab_arm(uint8_t *previous) {
    *previous = g_printAndLog;
    g_printAndLog |= PRINTANDLOG_GRAB;
    g_printAndLog &= (uint8_t) ~PRINTANDLOG_PRINT;
}

// Takes ownership of a grabber copy and turns it into a Java string.
static jstring grab_to_string(JNIEnv *env, char *text) {
    // NewStringUTF wants modified UTF-8; client output is plain printable text
    // (ANSI colours are off by default), so a straight conversion is fine.
    jstring result = (*env)->NewStringUTF(env, text != NULL ? text : "");
    free(text);
    if (result == NULL) {
        result = (*env)->NewStringUTF(env, "");
    }
    return result;
}

// Final drain: includes a trailing partial character, if any.
static jstring grab_take(JNIEnv *env) {
    return grab_to_string(env, grabber_take_all());
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
    OpenProxmark(&g_session.current_device, port_name, false, 20, false, USART_BAUD_RATE);
    if (g_session.pm3_present && TestProxmark(g_session.current_device) != PM3_SUCCESS) {
        CloseProxmark(g_session.current_device);
    }
    g_printAndLog = previous;

    pthread_mutex_unlock(&g_api_lock);

    (*env)->ReleaseStringUTFChars(env, port, port_name);
    return grab_take(env);
}

static void native_close(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    (void) env;
    pthread_mutex_lock(&g_api_lock);
    if (g_session.pm3_present) {
        pm3_close(g_session.current_device);
    }
    free_grabber();
    pthread_mutex_unlock(&g_api_lock);
}

static jboolean native_is_open(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    (void) env;
    return g_session.pm3_present ? JNI_TRUE : JNI_FALSE;
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
    g_user_interrupt = false;
    pm3_console(g_session.current_device, cmd_copy, true, true);
    pthread_mutex_unlock(&g_api_lock);

    free(cmd_copy);
    return grab_take(env);
}

// Drops the output accumulated so far, so the UI can stream it while the
// command is still running. Safe to call from another thread.
static jstring native_grab(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    if (g_session.pm3_present == false) {
        return (*env)->NewStringUTF(env, "");
    }
    return grab_to_string(env, grabber_take_copy());
}

// Asks the command in progress to stop: long loops poll g_user_interrupt and
// Lua scripts hit the debug hook set in cmdscript.c.
static void native_abort(JNIEnv *env, __attribute__((unused)) jclass clazz) {
    (void) env;
    g_user_interrupt = true;
}

static JNINativeMethod methods[] = {
    {"nativeInit",    "(Ljava/lang/String;)Z",                  (void *) native_init},
    {"nativeOpen",    "(Ljava/lang/String;)Ljava/lang/String;", (void *) native_open},
    {"nativeClose",   "()V",                                    (void *) native_close},
    {"nativeIsOpen",  "()Z",                                    (void *) native_is_open},
    {"nativeExec",    "(Ljava/lang/String;)Ljava/lang/String;", (void *) native_exec},
    {"nativeGrab",    "()Ljava/lang/String;",                    (void *) native_grab},
    {"nativeAbort",   "()V",                                     (void *) native_abort},
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
