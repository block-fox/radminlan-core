#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <winsock2.h>
#include <windows.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>
#include <wintun.h>

#define MTU 1500

struct wintun_api {
    WINTUN_CREATE_ADAPTER_FUNC *create;
    WINTUN_OPEN_ADAPTER_FUNC *open;
    WINTUN_CLOSE_ADAPTER_FUNC *close;
    WINTUN_START_SESSION_FUNC *start;
    WINTUN_END_SESSION_FUNC *end;
    WINTUN_GET_READ_WAIT_EVENT_FUNC *read_event;
    WINTUN_RECEIVE_PACKET_FUNC *receive;
    WINTUN_RELEASE_RECEIVE_PACKET_FUNC *release;
    WINTUN_ALLOCATE_SEND_PACKET_FUNC *allocate;
    WINTUN_SEND_PACKET_FUNC *send;
};

struct pump {
    struct wintun_api *api;
    WINTUN_SESSION_HANDLE session;
    HANDLE stop;
    HANDLE input;
    HANDLE output;
};

static int read_exact(HANDLE input, void *data, DWORD length) {
    BYTE *bytes = data;
    while (length) {
        DWORD received = 0;
        if (!ReadFile(input, bytes, length, &received, NULL) || !received) return 0;
        bytes += received;
        length -= received;
    }
    return 1;
}

static int write_exact(HANDLE output, const void *data, DWORD length) {
    const BYTE *bytes = data;
    while (length) {
        DWORD written = 0;
        if (!WriteFile(output, bytes, length, &written, NULL) || !written) return 0;
        bytes += written;
        length -= written;
    }
    return 1;
}

static DWORD WINAPI inject_packets(void *arguments) {
    struct pump *stream = arguments;
    BYTE packet[MTU];
    BYTE size[2];
    DWORD status = 0;
    while (WaitForSingleObject(stream->stop, 0) == WAIT_TIMEOUT && read_exact(stream->input, size, 2)) {
        DWORD length = (DWORD)(size[0] * 256 + size[1]);
        if (length < 20 || length > MTU || !read_exact(stream->input, packet, length)) {
            status = 1;
            break;
        }
        BYTE *buffer = stream->api->allocate(stream->session, length);
        if (!buffer) {
            if (GetLastError() == ERROR_BUFFER_OVERFLOW) continue;
            status = 1;
            break;
        }
        memcpy(buffer, packet, length);
        stream->api->send(stream->session, buffer);
    }
    SetEvent(stream->stop);
    return status;
}

static int forward_packets(struct pump *stream) {
    HANDLE ready[] = {stream->stop, stream->api->read_event(stream->session)};
    while (WaitForSingleObject(stream->stop, 0) == WAIT_TIMEOUT) {
        DWORD length = 0;
        BYTE *packet = stream->api->receive(stream->session, &length);
        if (!packet) {
            if (GetLastError() != ERROR_NO_MORE_ITEMS) return 1;
            DWORD event = WaitForMultipleObjects(2, ready, FALSE, INFINITE);
            if (event == WAIT_OBJECT_0) return 0;
            if (event != WAIT_OBJECT_0 + 1) return 1;
            continue;
        }
        int ok = 1;
        if (length >= 20 && length <= MTU && (packet[0] >> 4) == 4) {
            BYTE size[] = {(BYTE)(length >> 8), (BYTE)length};
            ok = write_exact(stream->output, size, 2) && write_exact(stream->output, packet, length);
        }
        stream->api->release(stream->session, packet);
        if (!ok) return 1;
    }
    return 0;
}

static HMODULE load_sdk(struct wintun_api *api) {
    WCHAR path[32768];
    DWORD size = GetModuleFileNameW(NULL, path, (DWORD)(sizeof(path) / sizeof(*path)));
    if (!size || size >= sizeof(path) / sizeof(*path)) return NULL;
    WCHAR *filename = wcsrchr(path, L'\\');
    if (!filename) return NULL;
    const WCHAR dll[] = L"wintun.dll";
    size_t directory = (size_t)(filename + 1 - path);
    if (directory + sizeof(dll) / sizeof(*dll) > sizeof(path) / sizeof(*path)) return NULL;
    memcpy(filename + 1, dll, sizeof(dll));
    HMODULE library = LoadLibraryExW(path, NULL, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!library) return NULL;
#define LOAD(name, type, symbol) do { \
    api->name = (type *)GetProcAddress(library, symbol); \
    if (!api->name) { FreeLibrary(library); return NULL; } \
} while (0)
    LOAD(create, WINTUN_CREATE_ADAPTER_FUNC, "WintunCreateAdapter");
    LOAD(open, WINTUN_OPEN_ADAPTER_FUNC, "WintunOpenAdapter");
    LOAD(close, WINTUN_CLOSE_ADAPTER_FUNC, "WintunCloseAdapter");
    LOAD(start, WINTUN_START_SESSION_FUNC, "WintunStartSession");
    LOAD(end, WINTUN_END_SESSION_FUNC, "WintunEndSession");
    LOAD(read_event, WINTUN_GET_READ_WAIT_EVENT_FUNC, "WintunGetReadWaitEvent");
    LOAD(receive, WINTUN_RECEIVE_PACKET_FUNC, "WintunReceivePacket");
    LOAD(release, WINTUN_RELEASE_RECEIVE_PACKET_FUNC, "WintunReleaseReceivePacket");
    LOAD(allocate, WINTUN_ALLOCATE_SEND_PACKET_FUNC, "WintunAllocateSendPacket");
    LOAD(send, WINTUN_SEND_PACKET_FUNC, "WintunSendPacket");
#undef LOAD
    return library;
}

int wmain(int argc, WCHAR **argv) {
    if (argc != 2 || !argv[1][0] || wcslen(argv[1]) > 63 ||
        wcsspn(argv[1], L"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-") != wcslen(argv[1])) {
        fputs("Usage: radminlan-wintun ADAPTER_NAME\n", stderr);
        return 2;
    }
    struct wintun_api api = {0};
    HMODULE library = load_sdk(&api);
    if (!library) { fputs("Unable to load wintun.dll from the helper directory\n", stderr); return 1; }
    WINTUN_ADAPTER_HANDLE adapter = api.open(argv[1]);
    if (!adapter) adapter = api.create(argv[1], L"RadminLAN", NULL);
    if (!adapter) {
        fprintf(stderr, "Wintun adapter open/create failed (%lu)\n", (unsigned long)GetLastError());
        FreeLibrary(library);
        return 1;
    }
    struct pump stream = {&api, api.start(adapter, 0x400000), CreateEventW(NULL, TRUE, FALSE, NULL),
                          GetStdHandle(STD_INPUT_HANDLE), GetStdHandle(STD_OUTPUT_HANDLE)};
    int result = 1;
    if (stream.session && stream.stop && write_exact(stream.output, "RLT1", 4)) {
        HANDLE writer = CreateThread(NULL, 0, inject_packets, &stream, 0, NULL);
        if (writer) {
            result = forward_packets(&stream);
            SetEvent(stream.stop);
            CancelSynchronousIo(writer);
            WaitForSingleObject(writer, INFINITE);
            DWORD exit_code = 0;
            if (GetExitCodeThread(writer, &exit_code) && exit_code != 0) result = 1;
            CloseHandle(writer);
        }
    } else {
        fprintf(stderr, "Wintun session startup failed (%lu)\n", (unsigned long)GetLastError());
    }
    if (stream.session) api.end(stream.session);
    if (stream.stop) CloseHandle(stream.stop);
    api.close(adapter);
    FreeLibrary(library);
    return result;
}
