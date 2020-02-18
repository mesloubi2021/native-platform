#ifdef _WIN32

#include "win_fsnotifier.h"

using namespace std;

//
// WatchPoint
//

WatchPoint::WatchPoint(Server* server, wstring path, HANDLE directoryHandle) {
    this->server = server;
    this->path = path;
    this->buffer = (FILE_NOTIFY_INFORMATION*) malloc(EVENT_BUFFER_SIZE);
    ZeroMemory(&this->overlapped, sizeof(OVERLAPPED));
    this->overlapped.hEvent = this;
    this->directoryHandle = directoryHandle;
    this->status = WATCH_UNINITIALIZED;
}

WatchPoint::~WatchPoint() {
    free(buffer);
}

void WatchPoint::close() {
    BOOL ret = CancelIo(directoryHandle);
    if (!ret) {
        log_severe(server->getThreadEnv(), L"Couldn't cancel I/O %p for '%ls': %d", directoryHandle, path.c_str(), GetLastError());
    }
    ret = CloseHandle(directoryHandle);
    if (!ret) {
        log_severe(server->getThreadEnv(), L"Couldn't close handle %p for '%ls': %d", directoryHandle, path.c_str(), GetLastError());
    }
}

static void CALLBACK startWatchCallback(_In_ ULONG_PTR arg) {
    WatchPoint* watchPoint = (WatchPoint*) arg;
    watchPoint->listen();
}

int WatchPoint::awaitListeningStarted(HANDLE threadHandle) {
    unique_lock<mutex> lock(listenerMutex);
    QueueUserAPC(startWatchCallback, threadHandle, (ULONG_PTR) this);
    listenerStarted.wait(lock);
    lock.unlock();
    return status;
}

void WatchPoint::listen() {
    BOOL success = ReadDirectoryChangesW(
        directoryHandle,        // handle to directory
        buffer,                 // read results buffer
        EVENT_BUFFER_SIZE,      // length of buffer
        TRUE,                   // include children
        EVENT_MASK,             // filter conditions
        NULL,                   // bytes returned
        &overlapped,            // overlapped buffer
        &handleEventCallback    // completion routine
    );

    unique_lock<mutex> lock(listenerMutex);
    if (success) {
        status = WATCH_LISTENING;
    } else {
        status = WATCH_FAILED_TO_LISTEN;
        log_warning(server->getThreadEnv(), L"Couldn't start watching %p for '%ls', error = %d", directoryHandle, path.c_str(), GetLastError());
        // TODO Error handling
    }
    listenerStarted.notify_all();
    lock.unlock();
}

static void CALLBACK handleEventCallback(DWORD errorCode, DWORD bytesTransferred, LPOVERLAPPED overlapped) {
    WatchPoint* watchPoint = (WatchPoint*) overlapped->hEvent;
    watchPoint->handleEvent(errorCode, bytesTransferred);
}

void WatchPoint::handleEvent(DWORD errorCode, DWORD bytesTransferred) {
    status = WATCH_NOT_LISTENING;

    if (errorCode == ERROR_OPERATION_ABORTED) {
        log_info(server->getThreadEnv(), L"Finished watching '%ls'", path.c_str());
        status = WATCH_FINISHED;
        server->reportFinished(this);
        return;
    }

    if (bytesTransferred == 0) {
        // don't send dirty too much, everything is changed anyway
        // TODO Understand what this does
        // if (WaitForSingleObject(stopEventHandle, 500) == WAIT_OBJECT_0)
        //    break;

        // Got a buffer overflow => current changes lost => send INVALIDATE on root
        server->reportEvent(FILE_EVENT_INVALIDATE, path);
    } else {
        FILE_NOTIFY_INFORMATION* current = buffer;
        for (;;) {
            handlePathChanged(current);
            if (current->NextEntryOffset == 0) {
                break;
            }
            current = (FILE_NOTIFY_INFORMATION*) (((BYTE*) current) + current->NextEntryOffset);
        }
    }

    listen();
    if (status != WATCH_LISTENING) {
        server->reportFinished(this);
    }
}

void WatchPoint::handlePathChanged(FILE_NOTIFY_INFORMATION* info) {
    wstring changedPath = wstring(info->FileName, 0, info->FileNameLength / sizeof(wchar_t));
    if (!changedPath.empty()) {
        changedPath.insert(0, 1, L'\\');
        changedPath.insert(0, path);
    }

    log_fine(server->getThreadEnv(), L"Change detected: 0x%x '%ls'", info->Action, changedPath.c_str());

    jint type;
    if (info->Action == FILE_ACTION_ADDED || info->Action == FILE_ACTION_RENAMED_NEW_NAME) {
        type = FILE_EVENT_CREATED;
    } else if (info->Action == FILE_ACTION_REMOVED || info->Action == FILE_ACTION_RENAMED_OLD_NAME) {
        type = FILE_EVENT_REMOVED;
    } else if (info->Action == FILE_ACTION_MODIFIED) {
        type = FILE_EVENT_MODIFIED;
    } else {
        log_warning(server->getThreadEnv(), L"Unknown event 0x%x for %ls", info->Action, changedPath.c_str());
        type = FILE_EVENT_UNKNOWN;
    }

    server->reportEvent(type, changedPath);
}

//
// Server
//

Server::Server(JavaVM* jvm, JNIEnv* env, jobject watcherCallback) {
    this->jvm = jvm;
    this->watcherCallback = env->NewGlobalRef(watcherCallback);

    unique_lock<mutex> lock(watcherThreadMutex);
    this->watcherThread = thread(&Server::run, this);
    this->watcherThreadStarted.wait(lock);
    lock.unlock();

    // TODO Error handling
    SetThreadPriority(this->watcherThread.native_handle(), THREAD_PRIORITY_ABOVE_NORMAL);
}

Server::~Server() {
}

void Server::run() {
    JNIEnv* env = attach_jni(jvm, "File watcher server", true);
    if (env == nullptr) {
        fwprintf(stderr, L"!!! Couldn't attach JNI, stopping");
        return;
    }

    log_info(env, L"Server thread %d with handle %p running", GetCurrentThreadId(), watcherThread.native_handle());

    unique_lock<mutex> lock(watcherThreadMutex);
    watcherThreadStarted.notify_all();
    lock.unlock();

    while (!terminate || watchPoints.size() > 0) {
        SleepEx(INFINITE, true);
    }

    log_info(env, L"Server thread %d finishing", GetCurrentThreadId());

    detach_jni(jvm);
}

void Server::startWatching(JNIEnv* env, wchar_t* path) {
    HANDLE directoryHandle = CreateFileW(
        path,                   // pointer to the file name
        FILE_LIST_DIRECTORY,    // access (read/write) mode
        CREATE_SHARE,           // share mode
        NULL,                   // security descriptor
        OPEN_EXISTING,          // how to create
        CREATE_FLAGS,           // file attributes
        NULL                    // file with attributes to copy
    );

    if (directoryHandle == INVALID_HANDLE_VALUE) {
        log_severe(env, L"Couldn't get file handle for '%ls': %d", path, GetLastError());
        // TODO Error handling
        return;
    }

    WatchPoint* watchPoint = new WatchPoint(this, path, directoryHandle);

    HANDLE threadHandle = watcherThread.native_handle();
    int ret = watchPoint->awaitListeningStarted(threadHandle);
    switch (ret) {
        case WATCH_LISTENING:
            watchPoints.push_back(watchPoint);
            break;
        default:
            log_severe(env, L"Couldn't start listening to '%ls': %d", path, ret);
            delete watchPoint;
            // TODO Error handling
            break;
    }
}

void Server::reportFinished(WatchPoint* watchPoint) {
    watchPoints.remove(watchPoint);
    delete watchPoint;
}

static JNIEnv* lookupThreadEnv(JavaVM* jvm) {
    JNIEnv* env;
    // TODO Verify that JNI 1.6 is the right version
    jint ret = jvm->GetEnv((void**) &(env), JNI_VERSION_1_6);
    if (ret != JNI_OK) {
        fwprintf(stderr, L"Failed to get JNI env for current thread %d: %d\n", GetCurrentThreadId(), ret);
        return NULL;
    }
    return env;
}

JNIEnv* Server::getThreadEnv() {
    return lookupThreadEnv(jvm);
}

void Server::reportEvent(jint type, const wstring changedPath) {
    JNIEnv* env = getThreadEnv();
    jstring changedPathJava = wchar_to_java_path(env, changedPath.c_str());
    jclass callback_class = env->GetObjectClass(watcherCallback);
    jmethodID methodCallback = env->GetMethodID(callback_class, "pathChanged", "(ILjava/lang/String;)V");
    env->CallVoidMethod(watcherCallback, methodCallback, type, changedPathJava);
    env->DeleteLocalRef(changedPathJava);
}

static void CALLBACK requestTerminationCallback(_In_ ULONG_PTR arg) {
    Server* server = (Server*) arg;
    server->requestTermination();
}

void Server::requestTermination() {
    terminate = true;
    // Make copy so terminated entries can be removed
    list<WatchPoint*> copyWatchPoints(watchPoints);
    for (auto& watchPoint : copyWatchPoints) {
        watchPoint->close();
    }
}

void Server::close(JNIEnv* env) {
    HANDLE threadHandle = watcherThread.native_handle();
    log_fine(env, L"Requesting termination of server thread %p", threadHandle);
    int ret = QueueUserAPC(requestTerminationCallback, threadHandle, (ULONG_PTR) this);
    if (ret == 0) {
        log_severe(env, L"Couldn't send termination request to thread %p: %d", threadHandle, GetLastError());
    } else {
        watcherThread.join();
    }
    env->DeleteGlobalRef(this->watcherCallback);
}

//
// JNI calls
//

JNIEXPORT jobject JNICALL
Java_net_rubygrapefruit_platform_internal_jni_WindowsFileEventFunctions_startWatching(JNIEnv* env, jclass target, jobjectArray paths, jobject javaCallback, jobject result) {
    JavaVM* jvm;
    int jvmStatus = env->GetJavaVM(&jvm);
    if (jvmStatus != JNI_OK) {
        mark_failed_with_errno(env, "Could not store JVM instance.", result);
        return NULL;
    }

    int watchPointCount = env->GetArrayLength(paths);
    if (watchPointCount == 0) {
        mark_failed_with_errno(env, "No paths given to watch.", result);
        return NULL;
    }

    Server* server = new Server(jvm, env, javaCallback);

    for (int i = 0; i < watchPointCount; i++) {
        jstring path = (jstring) env->GetObjectArrayElement(paths, i);
        wchar_t* watchPoint = java_to_wchar_path(env, path);
        int watchPointLen = wcslen(watchPoint);
        server->startWatching(env, watchPoint);
        free(watchPoint);
    }

    jclass clsWatch = env->FindClass("net/rubygrapefruit/platform/internal/jni/WindowsFileEventFunctions$WatcherImpl");
    jmethodID constructor = env->GetMethodID(clsWatch, "<init>", "(Ljava/lang/Object;)V");
    return env->NewObject(clsWatch, constructor, env->NewDirectByteBuffer(server, sizeof(server)));
}

JNIEXPORT void JNICALL
Java_net_rubygrapefruit_platform_internal_jni_WindowsFileEventFunctions_stopWatching(JNIEnv* env, jclass target, jobject detailsObj, jobject result) {
    Server* server = (Server*) env->GetDirectBufferAddress(detailsObj);
    server->close(env);
    delete server;
}

#endif
