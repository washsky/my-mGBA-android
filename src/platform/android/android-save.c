/* SPDX-License-Identifier: MPL-2.0 */
#include "android-save.h"
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>
#include <SDL.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static char gamePath[4096];
static struct mCore* gameCore;
void mAndroidSetGamePath(struct mCore* core, const char* path) {
	gameCore = core; snprintf(gamePath, sizeof(gamePath), "%s", path ? path : "");
}
const char* mAndroidGamePath(void) { return gamePath; }
bool mAndroidGameFolder(struct mCore* core, char* output, size_t capacity) {
	if (core != gameCore || !gamePath[0]) return false;
	char directory[4096]; snprintf(directory, sizeof(directory), "%s", gamePath);
	char* slash = strrchr(directory, '/'); if (!slash) return false; *slash = 0;
	char marker[4096]; snprintf(marker, sizeof(marker), "%s/game.properties", directory);
	if (access(marker, R_OK)) return false;
	return snprintf(output, capacity, "%s", directory) < (int) capacity;
}
bool mAndroidGameDirectory(struct mCore* core, const char* folder, const char* legacy, char* output, size_t capacity) {
	char directory[4096];
	if (mAndroidGameFolder(core, directory, sizeof(directory))) {
		if (snprintf(output, capacity, "%s/%s", directory, folder) >= (int) capacity) return false;
	} else if (snprintf(output, capacity, "%s/%s", SDL_AndroidGetInternalStoragePath(), legacy) >= (int) capacity) return false;
	return mkdir(output, 0700) == 0 || errno == EEXIST;
}

bool mAndroidBackupState(struct mCore* core, const char* tag, char* path, size_t capacity) {
	char directory[4096];
	if (!mAndroidGameDirectory(core, "Backups", "mgba-backups", directory, sizeof(directory))) return false;
	static unsigned sequence;
	snprintf(path, capacity, "%s/%s-%lld-%llu-%u.ss", directory, tag,
		(long long) time(NULL), (unsigned long long) SDL_GetTicks64(), ++sequence);
	struct VFile* vf = VFileOpen(path, O_RDWR | O_CREAT | O_EXCL);
	if (!vf) return false;
	bool ok = mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA)
		&& vf->size(vf) >= (ssize_t) core->stateSize(core) && vf->sync(vf, NULL, 0);
	bool closed = vf->close(vf);
	if (!ok || !closed) unlink(path);
	return ok && closed;
}

bool mAndroidExportSave(struct mCore* core, bool battery, char* path, size_t capacity) {
	char directory[4096];
	if (!mAndroidGameDirectory(core, "Exports", "mgba-exports", directory, sizeof(directory))) return false;
	static unsigned sequence;
	snprintf(path, capacity, "%s/current-%lld-%llu-%u.%s", directory, (long long) time(NULL),
		(unsigned long long) SDL_GetTicks64(), ++sequence, battery ? "sav" : "ss");
	void* data = NULL;
	size_t size = battery ? core->savedataClone(core, &data) : 0;
	if (battery && (!data || !size || size > 1024 * 1024)) { free(data); return false; }
	struct VFile* vf = VFileOpen(path, O_RDWR | O_CREAT | O_EXCL);
	if (!vf) { free(data); return false; }
	bool ok = battery ? vf->write(vf, data, size) == (ssize_t) size
		: mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
	vf->close(vf); free(data);
	if (!ok) unlink(path); // Only this newly created export copy.
	return ok;
}

bool mAndroidWriteSnapshot(struct mCore* core, struct VFile* output, int flags) {
	/* Encode in memory first so we can check the PNG and detect disk write
	 * failures before the frontend publishes the snapshot. */
	struct VFile* image = VFileMemChunk(NULL, 0);
	if (!image) return false;
	bool ok = false;
	if (mCoreSaveStateNamed(core, image, flags | SAVESTATE_SCREENSHOT | SAVESTATE_METADATA)) {
		static const uint8_t pngMagic[] = {0x89, 'P', 'N', 'G', 13, 10, 26, 10};
		static const uint8_t pngEnd[] = {0, 0, 0, 0, 'I', 'E', 'N', 'D', 0xae, 0x42, 0x60, 0x82};
		ssize_t length = image->size(image);
		if (length > (ssize_t) (sizeof(pngMagic) + sizeof(pngEnd)) && length <= 16 * 1024 * 1024) {
			const uint8_t* bytes = image->map(image, length, MAP_READ);
			if (bytes) {
				ok = !memcmp(bytes, pngMagic, sizeof(pngMagic))
					&& !memcmp(bytes + length - sizeof(pngEnd), pngEnd, sizeof(pngEnd))
					&& output->write(output, bytes, length) == length
					&& output->sync(output, NULL, 0);
				image->unmap(image, (void*) bytes, length);
			}
		}
	}
	image->close(image);
	return ok;
}

static bool _importSave(struct mCore* core, struct VFile* vf, bool state, int flags, char* error, size_t capacity) {
	ssize_t size = vf->size(vf);
	if (size <= 0 || size > (state ? 16 * 1024 * 1024 : 1024 * 1024)) {
		snprintf(error, capacity, "存档为空或超过大小限制"); return false;
	}
	if (!state && size != 512 && size != 2048 && size != 8192 && size != 16384 &&
		size != 32768 && size != 65536 && size != 131072) {
		/* Some GB battery files append RTC data. Accept this core's exact
		 * expected size as well, so a freshly exported .sav round-trips. */
		void* existing = NULL;
		size_t expected = core->platform(core) == mPLATFORM_GB ? core->savedataClone(core, &existing) : 0;
		free(existing);
		if (!expected || expected != (size_t) size) {
			snprintf(error, capacity, "这不是受支持的原始游戏存档；即时存档请使用另一入口"); return false;
		}
	}
	char backup[4096];
	if (!mAndroidBackupState(core, "before-import", backup, sizeof(backup))) {
		snprintf(error, capacity, "自动备份失败，未加载存档"); return false;
	}
	bool ok = false;
	if (state) {
		ok = mCoreLoadStateNamed(core, vf, flags);
	} else {
		void* data = malloc(size);
		if (data && vf->read(vf, data, size) == size) {
			ok = core->savedataRestore(core, data, size, true);
			if (ok) core->reset(core);
		}
		free(data);
	}
	if (!ok) {
		struct VFile* previous = VFileOpen(backup, O_RDONLY);
		bool restored = previous && mCoreLoadStateNamed(core, previous, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
		if (previous) previous->close(previous);
		snprintf(error, capacity, restored ? "格式不兼容、文件损坏或 ROM 不匹配；已恢复加载前进度" : "加载失败，请从自动备份恢复加载前的进度");
	} else {
		SDL_Log("mGBA external save imported; rollback state: %s", backup);
	}
	return ok;
}

bool mAndroidReadSnapshot(struct mCore* core, struct VFile* vf, int flags, char* error, size_t capacity) {
	return _importSave(core, vf, true, flags, error, capacity);
}

bool mAndroidImportSave(struct mCore* core, const char* path, bool state, char* error, size_t capacity) {
	struct VFile* vf = VFileOpen(path, O_RDONLY);
	if (!vf) { snprintf(error, capacity, "无法打开存档文件"); return false; }
	bool ok = _importSave(core, vf, state, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_SCREENSHOT, error, capacity);
	vf->close(vf);
	return ok;
}
