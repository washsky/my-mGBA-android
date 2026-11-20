/* SPDX-License-Identifier: MPL-2.0
 * Experimental LAN link: the host runs two cycle-synchronized GBA cores.
 * The client uploads a private ROM/state copy and receives player-two video.
 * No emulated serial timing is tied to Wi-Fi round-trip time. */
#include "android-net.h"
#include "android-save.h"
#include <mgba/core/config.h>
#include <mgba/core/lockstep.h>
#include <mgba/core/serialize.h>
#include <mgba/internal/gba/sio/lockstep.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/vfs.h>
#include <SDL.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdarg.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>

#define NET_PORT 8765
#define NET_MAGIC 0x4D47424E
#define NET_VERSION 3
#define FRAME_PIXELS (240 * 160)
#define FRAME_BYTES (FRAME_PIXELS * sizeof(mColor))
#define MAX_ROM (32 * 1024 * 1024)
#define MAX_STATE (8 * 1024 * 1024)
enum { HELLO = 1, ROM, STATE, FRAME, INPUT, BYE, ACK, ERROR_MESSAGE, SPEED, FRAME_ACK };

struct LinkUser { struct mLockstepThreadUser d; int id; };
struct LinkDriver {
	struct GBASIOLockstepDriver d;
	void (*multi)(struct GBASIODriver*, uint16_t[4]);
	uint32_t (*normal32)(struct GBASIODriver*);
};
static struct {
	SDL_mutex* mutex;
	SDL_Thread* worker;
	SDL_atomic_t busy, client, stop, force, resume, keys, fd, transfers, hosting, speed, speedRequest;
	struct mCoreThread* primary;
	char rom[4096], address[64], code[16], directory[4096], statePath[4096];
	char status[512];
	bool host, frameReady, coordinatorReady, primaryLinked, peerLinked, peerStarted, rewind;
	unsigned frameNumber;
	float streamFps, streamKbps, streamRtt;
	int streamTarget;
	uint32_t inputQueue[128], appliedKeys;
	unsigned inputRead, inputWrite;
	mColor frame[FRAME_PIXELS];
	mColor* peerPixels;
	struct mCore* peer;
	struct mCoreThread peerThread;
	struct GBASIOLockstepCoordinator coordinator;
	struct LinkDriver drivers[2];
	struct LinkUser users[2];
	void* snapshot;
	size_t snapshotSize;
	bool snapshotOK;
	int snapshotAction;
} net;

static void status(const char* format, ...) {
	char message[sizeof(net.status)];
	va_list args;
	va_start(args, format);
	vsnprintf(message, sizeof(message), format, args);
	va_end(args);
	SDL_LockMutex(net.mutex);
	memcpy(net.status, message, strlen(message) + 1);
	SDL_UnlockMutex(net.mutex);
	SDL_Log("mGBA LAN: %s", message);
}

static bool transfer(int fd, void* data, size_t size, bool writing) {
	Uint64 deadline = SDL_GetTicks64() + 10000;
	while (size && !SDL_AtomicGet(&net.force)) {
		struct pollfd p = { .fd = fd, .events = writing ? POLLOUT : POLLIN };
		int ready = poll(&p, 1, 200);
		if (ready < 0 && errno == EINTR) continue;
		if (ready < 0 || SDL_GetTicks64() > deadline) return false;
		if (!ready) continue;
		ssize_t count = writing ? send(fd, data, size, MSG_NOSIGNAL) : recv(fd, data, size, 0);
		if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
		if (count <= 0) return false;
		data = (char*) data + count;
		size -= count;
		deadline = SDL_GetTicks64() + 10000;
	}
	return size == 0;
}

static bool sendPacket(int fd, unsigned type, const void* data, size_t size) {
	uint32_t header[4] = { htonl(NET_MAGIC), htonl(NET_VERSION), htonl(type), htonl(size) };
	return transfer(fd, header, sizeof(header), true) && (!size || transfer(fd, (void*) data, size, true));
}

static void* receivePacket(int fd, unsigned* type, size_t* size, size_t limit) {
	uint32_t header[4];
	if (!transfer(fd, header, sizeof(header), false) || ntohl(header[0]) != NET_MAGIC || ntohl(header[1]) != NET_VERSION) return NULL;
	*type = ntohl(header[2]); *size = ntohl(header[3]);
	if (*size > limit) return NULL;
	void* data = malloc(*size ? *size : 1);
	if (!data || (*size && !transfer(fd, data, *size, false))) { free(data); return NULL; }
	return data;
}

static bool writeFile(const char* path, const void* data, size_t size) {
	struct VFile* vf = VFileOpen(path, O_WRONLY | O_CREAT | O_TRUNC);
	if (!vf) return false;
	bool ok = vf->write(vf, data, size) == (ssize_t) size;
	vf->close(vf); return ok;
}

static bool snapshot(struct mCore* core) {
	struct VFile* vf = VFileMemChunk(NULL, 0);
	if (!vf) return false;
	bool ok = mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
	ssize_t size = vf->size(vf);
	if (ok && size > 0 && size <= MAX_STATE) {
		net.snapshot = malloc(size); net.snapshotSize = size;
		vf->seek(vf, 0, SEEK_SET);
		ok = net.snapshot && vf->read(vf, net.snapshot, size) == size;
	} else ok = false;
	vf->close(vf); return ok;
}

static void snapshotCommand(struct mCoreThread* thread) {
	char backup[4096];
	if (net.snapshotAction == 1) {
		net.snapshotOK = mAndroidBackupState(thread->core, "before-net", backup, sizeof(backup)) && snapshot(thread->core);
	} else {
		char error[256];
		net.snapshotOK = mAndroidImportSave(thread->core, net.statePath, true, error, sizeof(error));
		if (!net.snapshotOK) status("客机进度已下载但加载失败：%s", error);
	}
}

static int requestedId(struct mLockstepUser* user) { return ((struct LinkUser*) user)->id; }
static void finishMulti(struct GBASIODriver* driver, uint16_t data[4]) {
	struct LinkDriver* d = (struct LinkDriver*) driver;
	d->multi(driver, data); SDL_AtomicAdd(&net.transfers, 1);
}
static uint32_t finishNormal32(struct GBASIODriver* driver) {
	struct LinkDriver* d = (struct LinkDriver*) driver;
	SDL_AtomicAdd(&net.transfers, 1); return d->normal32(driver);
}

static void peerReset(struct mCoreThread* thread) {
	struct VFile* vf = VFileOpen(net.statePath, O_RDONLY);
	net.snapshotOK = vf && mCoreLoadStateNamed(thread->core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
	if (vf) vf->close(vf);
	mCoreThreadPauseFromThread(thread);
}

static void peerFrame(struct mCoreThread* thread) {
	SDL_LockMutex(net.mutex);
	if (net.inputRead != net.inputWrite) net.appliedKeys = net.inputQueue[net.inputRead++ % 128];
	SDL_UnlockMutex(net.mutex);
	thread->core->setKeys(thread->core, net.appliedKeys & 0x3FF);
	mCoreSyncLockAudio(&thread->impl->sync);
	mAudioBufferClear(thread->core->getAudioBuffer(thread->core));
	mCoreSyncUnlockAudio(&thread->impl->sync);
	SDL_LockMutex(net.mutex);
	memcpy(net.frame, net.peerPixels, FRAME_BYTES);
	net.frameNumber = thread->core->frameCounter(thread->core);
	net.frameReady = true;
	SDL_UnlockMutex(net.mutex);
}

static bool startPeer(void) {
	char path[4096]; snprintf(path, sizeof(path), "%s/peer.gba", net.directory);
	net.peer = mCoreFind(path);
	if (!net.peer) return false;
	if (net.peer->platform(net.peer) != mPLATFORM_GBA || !net.peer->init(net.peer)) {
		free(net.peer); net.peer = NULL;
		return false;
	}
	mCoreInitConfig(net.peer, "android-network");
	struct mCoreOptions options = { .audioBuffers = 4096, .sampleRate = 44100, .fpsTarget = 60, .audioSync = false, .videoSync = false, .rewindEnable = false, .mute = true, .volume = 0 };
	mCoreConfigLoadDefaults(&net.peer->config, &options);
	mCoreLoadConfig(net.peer);
	if (!mCoreLoadFile(net.peer, path)) return false;
	mCoreAutoloadSave(net.peer);
	net.peerPixels = calloc(FRAME_PIXELS, sizeof(mColor));
	if (!net.peerPixels) return false;
	net.peer->setVideoBuffer(net.peer, net.peerPixels, 240);
	memset(&net.peerThread, 0, sizeof(net.peerThread));
	net.peerThread.core = net.peer;
	net.peerThread.resetCallback = peerReset;
	net.peerThread.frameCallback = peerFrame;
	net.peerThread.logger.logger = net.primary->logger.logger;
	if (!mCoreThreadStart(&net.peerThread)) return false;
	net.peerStarted = true;
	mCoreThreadPause(&net.peerThread);
	if (!net.snapshotOK) return false;
	mCoreThreadInterrupt(net.primary);
	char backup[4096];
	bool ok = mAndroidBackupState(net.primary->core, "before-net-host", backup, sizeof(backup));
	if (ok) {
		net.rewind = net.primary->core->opts.rewindEnable;
		net.primary->core->opts.rewindEnable = false;
		GBASIOLockstepCoordinatorInit(&net.coordinator); net.coordinatorReady = true;
		for (int i = 0; i < 2; ++i) {
			struct mCoreThread* thread = i ? &net.peerThread : net.primary;
			mLockstepThreadUserInit(&net.users[i].d, thread);
			net.users[i].id = i;
			net.users[i].d.d.requestedId = requestedId;
			GBASIOLockstepDriverCreate(&net.drivers[i].d, &net.users[i].d.d);
			net.drivers[i].multi = net.drivers[i].d.d.finishMultiplayer;
			net.drivers[i].normal32 = net.drivers[i].d.d.finishNormal32;
			net.drivers[i].d.d.finishMultiplayer = finishMulti;
			net.drivers[i].d.d.finishNormal32 = finishNormal32;
			GBASIOLockstepCoordinatorAttach(&net.coordinator, &net.drivers[i].d);
			thread->core->setPeripheral(thread->core, mPERIPH_GBA_LINK_PORT, &net.drivers[i].d.d);
			if (i) net.peerLinked = true; else net.primaryLinked = true;
		}
		SDL_Log("mGBA LAN: linked cores=%u", (unsigned) GBASIOLockstepCoordinatorAttached(&net.coordinator));
	}
	mCoreThreadContinue(net.primary);
	if (ok) mCoreThreadUnpause(&net.peerThread);
	return ok;
}

/* Bit 0: recovery copy written; bit 1: state sent to the client. */
static unsigned stopPeer(int fd, bool returnState) {
	unsigned recovery = 0;
	bool primaryRunning = net.primary && mCoreThreadIsActive(net.primary);
	if (primaryRunning) mCoreThreadInterrupt(net.primary);
	if (net.peerStarted) mCoreThreadInterrupt(&net.peerThread);
	if (net.peerStarted) {
		free(net.snapshot); net.snapshot = NULL; net.snapshotSize = 0;
		if (snapshot(net.peer)) {
			char path[4096]; snprintf(path, sizeof(path), "%s/peer-recovery.ss", net.directory);
			if (writeFile(path, net.snapshot, net.snapshotSize)) recovery |= 1;
			if (returnState && sendPacket(fd, STATE, net.snapshot, net.snapshotSize)) recovery |= 2;
		}
		free(net.snapshot); net.snapshot = NULL;
	}
	if (net.primaryLinked) {
		net.primary->core->setPeripheral(net.primary->core, mPERIPH_GBA_LINK_PORT, NULL);
		GBASIOLockstepCoordinatorDetach(&net.coordinator, &net.drivers[0].d);
		net.primary->core->opts.rewindEnable = net.rewind;
		net.primaryLinked = false;
	}
	if (net.peerLinked) {
		net.peer->setPeripheral(net.peer, mPERIPH_GBA_LINK_PORT, NULL);
		GBASIOLockstepCoordinatorDetach(&net.coordinator, &net.drivers[1].d);
		net.peerLinked = false;
	}
	if (net.coordinatorReady) { GBASIOLockstepCoordinatorDeinit(&net.coordinator); net.coordinatorReady = false; }
	if (net.peerStarted) {
		mCoreThreadContinue(&net.peerThread);
		mCoreThreadEnd(&net.peerThread); mCoreThreadJoin(&net.peerThread); net.peerStarted = false;
	}
	if (primaryRunning) mCoreThreadContinue(net.primary);
	if (net.peer) { mCoreConfigDeinit(&net.peer->config); net.peer->deinit(net.peer); net.peer = NULL; }
	free(net.peerPixels); net.peerPixels = NULL;
	return recovery;
}

static void hostConnection(int fd) {
	unsigned type; size_t size;
	void* data = receivePacket(fd, &type, &size, 16);
	bool authenticated = data && type == HELLO && size == strlen(net.code) && !memcmp(data, net.code, size);
	free(data);
	if (!authenticated) { sendPacket(fd, ERROR_MESSAGE, "房间码不正确", strlen("房间码不正确")); status("房间码验证失败，请重新创建房间"); return; }
	if (!sendPacket(fd, ACK, NULL, 0)) return;
	status("正在接收客机 ROM 和进度…");
	data = receivePacket(fd, &type, &size, MAX_ROM);
	char path[4096]; snprintf(path, sizeof(path), "%s/peer.gba", net.directory);
	bool ok = data && type == ROM && size >= 192 && writeFile(path, data, size);
	free(data); if (!ok) return;
	data = receivePacket(fd, &type, &size, MAX_STATE);
	ok = data && type == STATE && size > 0 && writeFile(net.statePath, data, size);
	free(data); if (!ok) return;
	if (!startPeer()) {
		status("无法启动第二个 GBA；请检查 ROM 和进度格式");
		sendPacket(fd, ERROR_MESSAGE, net.status, strlen(net.status));
		stopPeer(fd, false); return;
	}
	if (!sendPacket(fd, ACK, NULL, 0)) { stopPeer(fd, false); return; }
	status("已连接：主机玩家 1，客机玩家 2");
	mColor* pixels = malloc(FRAME_BYTES);
	uLong bound = compressBound(FRAME_BYTES);
	uint8_t* packet = malloc(bound + 8);
	bool graceful = false;
	unsigned previous = 0;
	int previousSpeed = -1;
	/* Only two video packets may be outstanding. Older unsent frames are
	 * replaced in net.frame, so a slow link cannot accumulate seconds of video
	 * ahead of input or the final recovery state. */
	struct { unsigned frame; Uint64 sent; bool used; } flight[2] = {0};
	Uint64 frequency = SDL_GetPerformanceFrequency(), nextFrame = 0;
	unsigned inFlight = 0, goodAcks = 0, streamFrames = 0;
	size_t streamBytes = 0;
	int target = 60;
	float rtt = 0;
	Uint32 statsStart = SDL_GetTicks();
	Uint32 lastLog = SDL_GetTicks();
	while (pixels && packet && !SDL_AtomicGet(&net.stop) && !SDL_AtomicGet(&net.force)) {
		struct pollfd p = { .fd = fd, .events = POLLIN };
		int ready = poll(&p, 1, 2);
		if (ready < 0 || (p.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
		if (ready > 0 && p.revents & POLLIN) {
			data = receivePacket(fd, &type, &size, 16);
			if (!data) break;
			if (type == INPUT && size == 4) { uint32_t keys; memcpy(&keys, data, 4); mAndroidNetKeys(ntohl(keys)); }
			else if (type == FRAME_ACK && size == 4) {
				uint32_t value; memcpy(&value, data, 4); value = ntohl(value);
				for (int i = 0; i < 2; ++i) if (flight[i].used && flight[i].frame == value) {
					float sample = (SDL_GetPerformanceCounter() - flight[i].sent) * 1000.0 / frequency;
					rtt = rtt > 0 ? rtt * .8f + sample * .2f : sample;
					flight[i].used = false; --inFlight;
					int oldTarget = target;
					if (rtt > 120) { target = 20; goodAcks = 0; }
					else if (rtt > 55) { target = 30; goodAcks = 0; }
					else if (rtt < 35 && ++goodAcks >= 60) { target = 60; goodAcks = 0; }
					if (target != oldTarget) nextFrame = 0;
					break;
				}
			}
			else if (type == SPEED && size == 4) {
				uint32_t value; memcpy(&value, data, 4); value = ntohl(value);
				if (value < 1000 || value > 10000) { free(data); break; }
				mAndroidNetRequestSpeed(value);
			}
			else if (type == BYE && !size) graceful = true;
			else { free(data); break; }
			free(data); if (graceful) break;
		}
		int speed = mAndroidNetSpeed();
		if (speed != previousSpeed) {
			uint32_t value = htonl(speed);
			if (!sendPacket(fd, SPEED, &value, sizeof(value))) break;
			previousSpeed = speed;
		}
		unsigned current;
		SDL_LockMutex(net.mutex); current = net.frameNumber;
		Uint64 now = SDL_GetPerformanceCounter();
		bool expired=false;for(int i=0;i<2;++i)if(flight[i].used && now-flight[i].sent>frequency*5)expired=true;
		if(expired){SDL_UnlockMutex(net.mutex);status("客机画面确认超时，正在保留恢复进度…");break;}
		bool sendFrame = net.frameReady && current != previous && inFlight < 2 && now >= nextFrame;
		if (sendFrame) memcpy(pixels, net.frame, FRAME_BYTES);
		SDL_UnlockMutex(net.mutex);
		if (sendFrame) {
			int slot = flight[0].used ? 1 : 0;
			flight[slot].frame = current; flight[slot].sent = SDL_GetPerformanceCounter(); flight[slot].used = true; ++inFlight;
			uLongf length = bound; uint32_t frame = htonl(current), count = htonl(SDL_AtomicGet(&net.transfers)); memcpy(packet, &frame, 4); memcpy(packet + 4, &count, 4);
			if (compress2(packet + 8, &length, (const Bytef*) pixels, FRAME_BYTES, Z_BEST_SPEED) != Z_OK || !sendPacket(fd, FRAME, packet, length + 8)) break;
			previous = current;
			++streamFrames; streamBytes += length + 24;
			Uint64 period = frequency / target;
			if (!nextFrame || now > nextFrame + period) nextFrame = now;
			nextFrame += period;
		}
		Uint32 elapsed = SDL_GetTicks() - statsStart;
		if (elapsed >= 1000) {
			SDL_LockMutex(net.mutex);
			net.streamFps = streamFrames * 1000.0f / elapsed; net.streamKbps = streamBytes * 8.0f / elapsed;
			net.streamRtt = rtt; net.streamTarget = target;
			SDL_UnlockMutex(net.mutex);
			SDL_Log("mGBA LAN stream: %.1f fps target=%d rtt=%.1f ms %.1f kbit/s outstanding=%u", streamFrames * 1000.0f / elapsed, target, rtt, streamBytes * 8.0f / elapsed, inFlight);
			streamFrames = 0; streamBytes = 0; statsStart = SDL_GetTicks();
		}
		if (SDL_GetTicks() - lastLog >= 2000) {
			SDL_Log("mGBA LAN: peerFrame=%u transfers=%d keys=%x", current, SDL_AtomicGet(&net.transfers), SDL_AtomicGet(&net.keys)); lastLog = SDL_GetTicks();
		}
	}
	free(packet); free(pixels);
	unsigned recovery = stopPeer(fd, !SDL_AtomicGet(&net.force));
	if (recovery & 2) status(recovery & 1 ? "联机已结束；客机进度已发送，主机保留恢复副本" : "客机进度已发送，但主机恢复副本写入失败；请确认客机已取回");
	else status(recovery & 1 ? "连接已结束，进度未发送；客机恢复副本保留在主机 mgba-network 目录" : "连接已结束，最新进度恢复失败；请保留会话文件和联机前备份");
}

static bool uploadFile(int fd, const char* path) {
	struct VFile* vf = VFileOpen(path, O_RDONLY);
	if (!vf) return false;
	ssize_t size = vf->size(vf);
	void* data = size > 0 && size <= MAX_ROM ? malloc(size) : NULL;
	bool ok = data && vf->read(vf, data, size) == size && sendPacket(fd, ROM, data, size);
	free(data); vf->close(vf); return ok;
}

static bool applyReturnedState(void* data, size_t size) {
	if (!writeFile(net.statePath, data, size)) return false;
	net.snapshotAction = 2; net.snapshotOK = false;
	mCoreThreadRunFunction(net.primary, snapshotCommand);
	return net.snapshotOK;
}

static void clientConnection(int fd) {
	unsigned type; size_t size;
	if (!sendPacket(fd, HELLO, net.code, strlen(net.code))) return;
	void* data = receivePacket(fd, &type, &size, MAX_STATE);
	bool ok = data && type == ACK; free(data);
	if (!ok) { status("无法加入：房间码错误或主机拒绝连接"); return; }
	status("正在备份并发送客机 ROM 和进度…");
	net.snapshotAction = 1; net.snapshotOK = false;
	mCoreThreadRunFunction(net.primary, snapshotCommand);
	ok = net.snapshotOK && uploadFile(fd, net.rom) && sendPacket(fd, STATE, net.snapshot, net.snapshotSize);
	free(net.snapshot); net.snapshot = NULL;
	if (!ok) { status("客机备份或上传失败，原进度未改变"); return; }
	data = receivePacket(fd, &type, &size, MAX_STATE);
	ok = data && type == ACK; free(data);
	if (!ok) { status("主机无法启动第二个实例，原进度未改变"); return; }
	status("已连接：正在操作玩家 2；请从菜单正常断开以取回进度");
	Uint32 sent = SDL_GetTicks();
	Uint32 statsStart = sent; unsigned streamFrames = 0; size_t streamBytes = 0;
	bool restored = false;
	while (!SDL_AtomicGet(&net.force)) {
		if (SDL_AtomicGet(&net.stop)) {
			if (!sendPacket(fd, BYE, NULL, 0)) break;
			/* Frames already in flight may precede the final state. */
			while (!SDL_AtomicGet(&net.force)) {
				data = receivePacket(fd, &type, &size, MAX_STATE);
				if (!data) break;
				if (type == STATE) { restored = applyReturnedState(data, size); free(data); break; }
				free(data); if (type != FRAME && type != SPEED) break;
			}
			break;
		}
		int requestedSpeed = mAndroidNetTakeSpeedRequest();
		if (requestedSpeed >= 1000) {
			uint32_t value = htonl(requestedSpeed);
			if (!sendPacket(fd, SPEED, &value, sizeof(value))) break;
		}
		/* Preserve press/release edges even when a tap is shorter than one
		 * network polling interval. The host applies one edge per GBA frame. */
		for (;;) {
			SDL_LockMutex(net.mutex);
			bool pending = net.inputRead != net.inputWrite;
			uint32_t value = pending ? net.inputQueue[net.inputRead++ % 128] : 0;
			SDL_UnlockMutex(net.mutex);
			if (!pending) break;
			uint32_t keys = htonl(value);
			if (!sendPacket(fd, INPUT, &keys, 4)) goto clientDone;
		}
		if (SDL_GetTicks() - sent >= 250) {
			uint32_t keys = htonl(SDL_AtomicGet(&net.keys) & 0x3FF);
			if (!sendPacket(fd, INPUT, &keys, 4)) break;
			sent = SDL_GetTicks();
		}
		struct pollfd p = { .fd = fd, .events = POLLIN };
		int ready = poll(&p, 1, 5);
		if (ready < 0 || p.revents & (POLLERR | POLLHUP | POLLNVAL)) break;
		if (!ready) continue;
		data = receivePacket(fd, &type, &size, MAX_STATE);
		if (!data) break;
		if (type == FRAME && size > 8) {
			mColor pixels[FRAME_PIXELS]; uLongf length = FRAME_BYTES;
			if (uncompress((Bytef*) pixels, &length, (Bytef*) data + 8, size - 8) != Z_OK || length != FRAME_BYTES) { free(data); break; }
			uint32_t frame, count; memcpy(&frame, data, 4); memcpy(&count, (uint8_t*) data + 4, 4); SDL_AtomicSet(&net.transfers, ntohl(count));
			SDL_LockMutex(net.mutex);
			memcpy(net.frame, pixels, FRAME_BYTES); net.frameNumber = ntohl(frame); net.frameReady = true;
			SDL_UnlockMutex(net.mutex);
			if (!sendPacket(fd, FRAME_ACK, &frame, sizeof(frame))) { free(data); break; }
			++streamFrames; streamBytes += size + 16;
			Uint32 elapsed = SDL_GetTicks() - statsStart;
			if (elapsed >= 1000) {
				SDL_LockMutex(net.mutex); net.streamFps = streamFrames * 1000.0f / elapsed;
				net.streamKbps = streamBytes * 8.0f / elapsed; net.streamTarget = 60; SDL_UnlockMutex(net.mutex);
				SDL_Log("mGBA LAN received: %.1f fps %.1f kbit/s", streamFrames * 1000.0f / elapsed, streamBytes * 8.0f / elapsed);
				streamFrames = 0; streamBytes = 0; statsStart = SDL_GetTicks();
			}
		} else if (type == SPEED && size == 4) {
			uint32_t value; memcpy(&value, data, 4); value = ntohl(value);
			if (value < 1000 || value > 10000) { free(data); break; }
			mAndroidNetSetSpeed(value);
		} else if (type == STATE) { restored = applyReturnedState(data, size); free(data); break; }
		else { free(data); break; }
		free(data);
	}
clientDone:
	status(restored ? "已断开，玩家 2 最新进度已取回并加载；原进度备份仍保留" : "连接已断开，未取回最新进度；请保留主机 mgba-network 恢复副本");
}

static int worker(void* unused) {
	(void) unused;
	int fd = -1;
	if (net.host) {
		int server = socket(AF_INET, SOCK_STREAM, 0);
		int yes = 1; setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
		struct sockaddr_in address = { .sin_family = AF_INET, .sin_port = htons(NET_PORT), .sin_addr.s_addr = INADDR_ANY };
		if (server >= 0 && !bind(server, (struct sockaddr*) &address, sizeof(address)) && !listen(server, 1)) {
			SDL_AtomicSet(&net.hosting, 1);
			status("房间已创建，端口 8765，房间码 %s；等待同一 Wi-Fi 下的客机", net.code);
			while (!SDL_AtomicGet(&net.stop) && !SDL_AtomicGet(&net.force)) {
				struct pollfd p = { .fd = server, .events = POLLIN };
				if (poll(&p, 1, 200) > 0) { fd = accept(server, NULL, NULL); break; }
			}
			SDL_AtomicSet(&net.hosting, 0);
		} else status("创建房间失败：端口被占用或网络不可用");
		if (server >= 0) close(server);
		if (fd < 0 && SDL_AtomicGet(&net.stop)) status("房间已关闭");
	} else {
		fd = socket(AF_INET, SOCK_STREAM, 0);
		if (fd < 0) status("无法创建网络连接");
		SDL_AtomicSet(&net.fd, fd);
		struct sockaddr_in address = { .sin_family = AF_INET, .sin_port = htons(NET_PORT) };
		inet_pton(AF_INET, net.address, &address.sin_addr);
		if (fd >= 0) {
			fcntl(fd, F_SETFL, O_NONBLOCK);
			int connected = connect(fd, (struct sockaddr*) &address, sizeof(address));
			struct pollfd p = { .fd = fd, .events = POLLOUT };
			int error = 0; socklen_t length = sizeof(error);
			if ((connected < 0 && (errno != EINPROGRESS || poll(&p, 1, 5000) <= 0)) || getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) || error) {
				close(fd); fd = -1; status("连接主机失败，请确认 IP、房间和同一 Wi-Fi 网络");
			}
		}
	}
	if (fd >= 0) {
		fcntl(fd, F_SETFL, O_NONBLOCK);
		int yes = 1; setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
		SDL_AtomicSet(&net.fd, fd);
		if (net.host) hostConnection(fd); else clientConnection(fd);
		shutdown(fd, SHUT_RDWR); close(fd);
	}
	SDL_AtomicSet(&net.fd, -1);
	SDL_AtomicSet(&net.client, 0);
	SDL_AtomicSet(&net.busy, 0);
	SDL_AtomicSet(&net.hosting, 0);
	SDL_AtomicSet(&net.speed, 1000);
	SDL_AtomicSet(&net.speedRequest, -1);
	SDL_AtomicSet(&net.resume, 1);
	return 0;
}

void mAndroidNetInit(struct mCoreThread* thread, const char* rom) {
	if (!net.mutex) net.mutex = SDL_CreateMutex();
	net.primary = thread;
	snprintf(net.rom, sizeof(net.rom), "%s", rom);
}
bool mAndroidNetStart(bool host, const char* address, const char* code) {
	if (!net.mutex || !net.primary || net.primary->core->platform(net.primary->core) != mPLATFORM_GBA || SDL_AtomicGet(&net.busy)) return false;
	if (net.worker) { SDL_WaitThread(net.worker, NULL); net.worker = NULL; }
	struct in_addr ip;
	if ((!host && inet_pton(AF_INET, address, &ip) != 1) || strlen(code) != 6) return false;
	for (int i = 0; i < 6; ++i) if (code[i] < '0' || code[i] > '9') return false;
	net.host = host; net.frameReady = false; net.frameNumber = 0;
	net.streamFps = net.streamKbps = net.streamRtt = 0; net.streamTarget = 60;
	net.inputRead = net.inputWrite = 0; net.appliedKeys = 0;
	SDL_AtomicSet(&net.stop, 0); SDL_AtomicSet(&net.force, 0); SDL_AtomicSet(&net.keys, 0); SDL_AtomicSet(&net.transfers, 0); SDL_AtomicSet(&net.fd, -1);
	SDL_AtomicSet(&net.resume, 0); SDL_AtomicSet(&net.client, !host); SDL_AtomicSet(&net.busy, 1);
	SDL_AtomicSet(&net.hosting, 0); SDL_AtomicSet(&net.speed, 1000); SDL_AtomicSet(&net.speedRequest, -1);
	snprintf(net.address, sizeof(net.address), "%s", address); snprintf(net.code, sizeof(net.code), "%s", code);
	char base[4096];
	if (!mAndroidGameDirectory(net.primary->core, "Network", "mgba-network", base, sizeof(base))) {
		SDL_AtomicSet(&net.busy, 0); SDL_AtomicSet(&net.client, 0); return false;
	}
	snprintf(net.directory, sizeof(net.directory), "%s/session-%lld-%llu", base, (long long) time(NULL), (unsigned long long) SDL_GetTicks64());
	if (mkdir(net.directory, 0700)) { SDL_AtomicSet(&net.busy, 0); SDL_AtomicSet(&net.client, 0); return false; }
	snprintf(net.statePath, sizeof(net.statePath), "%s/state-in.ss", net.directory);
	status(host ? "正在创建 Wi-Fi 房间…" : "正在连接 Wi-Fi 主机…");
	net.worker = SDL_CreateThread(worker, "mGBA LAN", NULL);
	if (!net.worker) { SDL_AtomicSet(&net.busy, 0); SDL_AtomicSet(&net.client, 0); return false; }
	return true;
}
void mAndroidNetStop(void) { SDL_AtomicSet(&net.stop, 1); }
void mAndroidNetShutdown(void) {
	if (!net.worker) return;
	SDL_AtomicSet(&net.stop, 1); SDL_AtomicSet(&net.force, 1);
	int fd = SDL_AtomicGet(&net.fd); if (fd >= 0) shutdown(fd, SHUT_RDWR);
	if (net.worker) { SDL_WaitThread(net.worker, NULL); net.worker = NULL; }
}
bool mAndroidNetBusy(void) { return SDL_AtomicGet(&net.busy) != 0; }
bool mAndroidNetClient(void) { return SDL_AtomicGet(&net.client) != 0; }
bool mAndroidNetHosting(void) { return SDL_AtomicGet(&net.hosting) != 0 && mAndroidNetBusy(); }
int mAndroidNetSpeed(void) { int value = SDL_AtomicGet(&net.speed); return value >= 1000 ? value : 1000; }
void mAndroidNetSetSpeed(int milli) { if (milli >= 1000 && milli <= 10000) SDL_AtomicSet(&net.speed, milli); }
void mAndroidNetRequestSpeed(int milli) { if (milli >= 1000 && milli <= 10000 && mAndroidNetBusy()) SDL_AtomicSet(&net.speedRequest, milli); }
int mAndroidNetTakeSpeedRequest(void) { return SDL_AtomicSet(&net.speedRequest, -1); }
unsigned mAndroidNetFrameNumber(void) {
	if (!net.mutex) return 0;
	SDL_LockMutex(net.mutex); unsigned frame = net.frameNumber; SDL_UnlockMutex(net.mutex); return frame;
}
bool mAndroidNetTakeResume(void) { return SDL_AtomicCAS(&net.resume, 1, 0); }
void mAndroidNetKeys(uint32_t keys) {
	keys &= 0x3FF;
	if (!net.mutex || (uint32_t) SDL_AtomicSet(&net.keys, keys) == keys) return;
	SDL_LockMutex(net.mutex);
	if (net.inputWrite - net.inputRead >= 128) net.inputRead = net.inputWrite;
	net.inputQueue[net.inputWrite++ % 128] = keys;
	SDL_UnlockMutex(net.mutex);
}
bool mAndroidNetCopyFrame(mColor* output, size_t stride) {
	if (!net.mutex) return false;
	SDL_LockMutex(net.mutex);
	bool ready = net.frameReady;
	if (ready) { for (int y = 0; y < 160; ++y) memcpy(output + y * stride, net.frame + y * 240, 240 * sizeof(mColor)); net.frameReady = false; }
	SDL_UnlockMutex(net.mutex);
	return ready;
}
void mAndroidNetStatus(char* output, size_t capacity) {
	if (!net.mutex) { snprintf(output, capacity, "尚未创建联机会话"); return; }
	SDL_LockMutex(net.mutex);
	char metrics[160];if(net.host)snprintf(metrics,sizeof(metrics),"发送目标：%d FPS；往返 %.1f ms",net.streamTarget,net.streamRtt);else snprintf(metrics,sizeof(metrics),"最高目标 60 FPS，拥堵时自适应；客机暂不播放声音");
	snprintf(output, capacity, "%s\n共同倍速目标：%.1f 倍（任一设备可切换）\n客机画面：%.1f FPS，带宽 %.0f kbit/s\n%s\n已完成通信传输：%d\n会话文件：%s", net.status, mAndroidNetSpeed() / 1000.0, net.streamFps, net.streamKbps, metrics, SDL_AtomicGet(&net.transfers), net.directory);
	SDL_UnlockMutex(net.mutex);
}
