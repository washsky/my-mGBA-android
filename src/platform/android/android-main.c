/* Copyright (c) 2026 Gitsnup
 *
 * Android frontend for mGBA, based on the SDL frontend.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
#include "main.h"

#include "android-ui.h"
#include "android-save.h"
#include "android-cheats.h"
#include "android-net.h"

#include <mgba/core/core.h>
#include <mgba/core/cheats.h>
#include <mgba/core/config.h>
#include <mgba/core/directories.h>
#include <mgba/core/input.h>
#include <mgba/core/serialize.h>
#include <mgba/core/thread.h>
#include <mgba/internal/gb/gb.h>
#include <mgba/internal/gb/input.h>
#include <mgba/internal/gba/input.h>
#include <mgba-util/audio-buffer.h>
#include <mgba/feature/commandline.h>
#include <mgba-util/vfs.h>

#include <SDL.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <sys/system_properties.h>
#include <time.h>
#include <unistd.h>

#define PORT "android"
#include <stdlib.h>
#include <string.h>

static struct mStandardLogger _logger;
static int mSDLRun(struct mSDLRenderer* renderer, struct mArguments* args);
static struct mCoreThread* _activeThread;
static struct mSDLRenderer* _activeRenderer;
static struct mSDLAudio* _activeAudio;
static char* _nextRomPath;
static bool _fastForward;
static bool _fastForwardHeld;
static bool _rewinding;
static bool _hostPaused;
static bool _menuPaused;
static bool _hostMuted;
static bool _focusPaused;
static bool _minimizePaused;
static bool _focusMuted;
static bool _minimizeMuted;
static SDL_atomic_t _displayFrameCounter;
static SDL_atomic_t _emulatedFrameCounter;
static unsigned _cheatButtonFrames;
static size_t _normalAudioCapacity;
static Uint64 _frameDeadline;
static float _framePacingFps;
static bool _framePacing = true;
static SDL_mutex* _videoSnapshotMutex;
static void* _videoSnapshot;
static void* _coreVideoBuffer;
static size_t _videoSnapshotBytes;
static char _romFilename[256];
static char _romTitle[256];
static int _touchControlsVisible = 1;
static int _touchSizePercent = 100;
static int _touchOpacityPercent = 48;

enum AndroidCommandType {
	ANDROID_COMMAND_SET_INT,
	ANDROID_COMMAND_SET_FLOAT,
	ANDROID_COMMAND_SET_STRING,
	ANDROID_COMMAND_CLEAR_OPTION,
	ANDROID_COMMAND_HOST_MUTE,
	ANDROID_COMMAND_UPDATE_SPEED,
	ANDROID_COMMAND_FILE,
	ANDROID_COMMAND_GAME_ACTION,
	ANDROID_COMMAND_CHEATS,
	ANDROID_COMMAND_SNAPSHOT,
};

struct AndroidCommand {
	enum AndroidCommandType type;
	int value;
	int action;
	int fileKind;
	float floatValue;
	char key[128];
	char path[PATH_MAX];
	char error[256];
	char* payload;
	char* result;
	struct VFile* snapshot;
};

static struct AndroidCommand _command;
static void _runAndroidCommand(struct mCoreThread* thread);

static float _speedRatio(struct mCoreThread* thread) {
	float ratio = -1.0f;
	if (mAndroidNetBusy()) return mAndroidNetSpeed() / 1000.0f;
	mCoreConfigGetFloatValue(&thread->core->config, _fastForwardHeld ? "fastForwardHeldRatio" : "fastForwardRatio", &ratio);
	return ratio;
}

/* RunFunction temporarily disables waits, then restores its saved values after
 * the callback. Apply the Android policy AFTER that restoration as well. */
static void _applySyncPolicy(struct mCoreThread* thread) {
	bool fast = _fastForward || _fastForwardHeld;
	bool audioWait = fast ? _speedRatio(thread) > 0 : thread->core->opts.audioSync;
	mCoreSyncLockAudio(&thread->impl->sync);
	thread->impl->sync.audioWait = audioWait;
	mCoreSyncConsumeAudio(&thread->impl->sync);
	mCoreSyncSetVideoSync(&thread->impl->sync, !fast && thread->core->opts.videoSync);
}

static void _androidFrameCallback(struct mCoreThread* thread) {
	if (_cheatButtonFrames && !--_cheatButtonFrames) mCheatPressButton(thread->core->cheatDevice(thread->core), false);
	/* Audio callbacks consume blocks, not individual video frames. Spread
	 * finite-speed emulation over time instead of emitting a burst after each
	 * callback and then waiting for the next audio block. Never repay a long
	 * stall with a burst of catch-up frames. */
	float fps = thread->impl->sync.fpsTarget;
	bool enabled = _framePacing && thread->impl->sync.audioWait && fps >= 5 && fps <= 10000;
	if (enabled) {
		Uint64 frequency = SDL_GetPerformanceFrequency();
		Uint64 now = SDL_GetPerformanceCounter();
		Uint64 period = (Uint64) (frequency / (double) fps);
		if (!_frameDeadline || _framePacingFps != fps || now > _frameDeadline + period) {
			_frameDeadline = now;
		} else while (now < _frameDeadline) {
			Uint64 ns = (Uint64) ((_frameDeadline - now) * (1000000000.0 / frequency));
			struct timespec delay = {ns / 1000000000, ns % 1000000000};
			nanosleep(&delay, NULL);
			now = SDL_GetPerformanceCounter();
		}
		_framePacingFps = fps;
		_frameDeadline += period;
	} else _frameDeadline = 0;
	/* With video sync disabled, the core may already be drawing the next
	 * frame when SDL wakes. Publish only a complete frame, independently of
	 * the live core buffer. The render thread never holds this lock at vsync. */
	SDL_LockMutex(_videoSnapshotMutex);
	memcpy(_videoSnapshot, _coreVideoBuffer, _videoSnapshotBytes);
	SDL_UnlockMutex(_videoSnapshotMutex);
	SDL_AtomicAdd(&_emulatedFrameCounter, 1);
}

static void _androidResetCallback(struct mCoreThread* thread) {
	SDL_AtomicSet(&_displayFrameCounter, 0);
	bool showResetInfo = false;
	mCoreConfigGetBoolValue(&thread->core->config, "showResetInfo", &showResetInfo);
	if (showResetInfo) mAndroidOverlaySetMessage("Game reset");
}

static void _initGBBindings(struct mInputMap* map) {
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_x, GB_KEY_A);
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_z, GB_KEY_B);
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_RETURN, GB_KEY_START);
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_BACKSPACE, GB_KEY_SELECT);
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_UP, GB_KEY_UP);
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_DOWN, GB_KEY_DOWN);
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_LEFT, GB_KEY_LEFT);
	mInputBindKey(map, SDL_BINDING_KEY, SDLK_RIGHT, GB_KEY_RIGHT);
#if SDL_VERSION_ATLEAST(2, 0, 0)
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_A, GB_KEY_A);
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_B, GB_KEY_B);
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_START, GB_KEY_START);
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_BACK, GB_KEY_SELECT);
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_DPAD_UP, GB_KEY_UP);
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_DPAD_DOWN, GB_KEY_DOWN);
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_DPAD_LEFT, GB_KEY_LEFT);
	mInputBindKey(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_BUTTON_DPAD_RIGHT, GB_KEY_RIGHT);
	struct mInputAxis axis = { GB_KEY_RIGHT, GB_KEY_LEFT, 0x4000, -0x4000 };
	mInputBindAxis(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_AXIS_LEFTX, &axis);
	axis = (struct mInputAxis) { GB_KEY_DOWN, GB_KEY_UP, 0x4000, -0x4000 };
	mInputBindAxis(map, SDL_BINDING_CONTROLLER, SDL_CONTROLLER_AXIS_LEFTY, &axis);
#endif
}

static void _saveConfig(struct mCore* core) {
#ifdef ENABLE_VFS
	mCoreConfigSave(&core->config);
#else
	UNUSED(core);
#endif
}

static int _stateFlags(struct mCore* core, bool saving) {
	int flags = saving ? (SAVESTATE_RTC | SAVESTATE_METADATA | SAVESTATE_SCREENSHOT | SAVESTATE_SAVEDATA | SAVESTATE_CHEATS) : (SAVESTATE_RTC | SAVESTATE_SCREENSHOT);
	if (mCoreConfigGetIntValue(&core->config, saving ? "saveStateExtdata" : "loadStateExtdata", &flags)) {
		return flags;
	}
	return flags;
}

static void _updateFastForward(struct mCoreThread* thread, bool flushAudio) {
	struct mCore* core = thread->core;
	bool fast = _fastForward || _fastForwardHeld;
	bool rewind = true;
	mCoreConfigGetBoolValue(&core->config, "rewindEnable", &rewind);
	core->opts.rewindEnable = rewind && !fast && !mAndroidNetBusy();
	/* A 4096-frame output callback at 10x needs roughly ten callbacks' worth
	 * of source samples. The normal fixed-capacity source queue cannot hold
	 * that much, causing repeated partial callbacks and an unintended cap. */
	size_t capacity = _normalAudioCapacity ? _normalAudioCapacity : 16384;
	float ratio = fast ? _speedRatio(thread) : 1;
	if (fast && ratio > 1 && _activeAudio && _activeAudio->obtainedSpec.freq > 0) {
		size_t block = _activeAudio->samples < 4096 ? 4096 : _activeAudio->samples;
		size_t needed = (size_t) ((double) block * core->audioSampleRate(core) * ratio / _activeAudio->obtainedSpec.freq * 1.25) + 1024;
		while (capacity < needed && capacity < (1U << 20)) capacity *= 2;
	}
	struct mAudioBuffer* source = core->getAudioBuffer(core);
	if (capacity && capacity != mAudioBufferCapacity(source)) {
		mCoreSyncLockAudio(&thread->impl->sync);
		unsigned channels = source->channels;
		mAudioBufferDeinit(source); mAudioBufferInit(source, capacity, channels);
		mCoreSyncConsumeAudio(&thread->impl->sync);
		SDL_Log("mGBA speed source queue: %zu frames, target %.1fx", capacity, ratio);
	}
	if (_fastForward || _fastForwardHeld) {
		int volume = -1;
		bool mute = false;
		mCoreConfigGetIntValue(&core->config, "fastForwardVolume", &volume);
		mCoreConfigGetBoolValue(&core->config, "fastForwardMute", &mute);
		if (volume >= 0) core->opts.volume = volume;
		else mCoreConfigGetIntValue(&core->config, "volume", &core->opts.volume);
		bool normalMute = false;
		mCoreConfigGetBoolValue(&core->config, "mute", &normalMute);
		core->opts.mute = mute || normalMute || _hostMuted;
		float ratio = _speedRatio(thread);
		if (ratio > 0.0f) {
			float base = core->opts.fpsTarget > 0.0f ? core->opts.fpsTarget : 60.0f;
			thread->impl->sync.fpsTarget = base * ratio;
			thread->impl->sync.audioWait = true;
		} else {
			thread->impl->sync.audioWait = false;
		}
		mCoreSyncSetVideoSync(&thread->impl->sync, false);
	} else {
		mCoreConfigGetIntValue(&core->config, "volume", &core->opts.volume);
		mCoreConfigGetBoolValue(&core->config, "mute", &core->opts.mute);
		core->opts.mute = core->opts.mute || _hostMuted;
		thread->impl->sync.fpsTarget = core->opts.fpsTarget > 0.0f ? core->opts.fpsTarget : 60.0f;
		thread->impl->sync.audioWait = core->opts.audioSync;
			mCoreSyncSetVideoSync(&thread->impl->sync, core->opts.videoSync);
	}
	core->reloadConfigOption(core, NULL, NULL);
	if (!_fastForward && !_fastForwardHeld && flushAudio) {
		mCoreSyncLockAudio(&thread->impl->sync);
		mAudioBufferClear(core->getAudioBuffer(core));
		mCoreSyncUnlockAudio(&thread->impl->sync);
	}
}

static void _updateHostMute(struct mCoreThread* thread) {
	bool shouldMute = _focusMuted || _minimizeMuted;
	if (shouldMute == _hostMuted) return;
	_hostMuted = shouldMute;
	_command.type = ANDROID_COMMAND_HOST_MUTE;
	_command.value = shouldMute;
	mCoreThreadRunFunction(thread, _runAndroidCommand);
}

static void _runAndroidCommand(struct mCoreThread* thread) {
	struct mCore* core = thread->core;
	switch (_command.type) {
	case ANDROID_COMMAND_SNAPSHOT:
		if (_command.action) {
			_command.value = mAndroidWriteSnapshot(core, _command.snapshot, _stateFlags(core, true));
			if (!_command.value) snprintf(_command.error, sizeof(_command.error), "无法保存带截图的快照，请检查存储空间");
		} else {
			_command.value = mAndroidReadSnapshot(core, _command.snapshot, _stateFlags(core, false), _command.error, sizeof(_command.error));
		}
		break;
	case ANDROID_COMMAND_CHEATS:
		if (_command.action == 0) { _command.result=mAndroidCheatList(core);if(!_command.result)snprintf(_command.error,sizeof(_command.error),"无法读取金手指列表"); }
		else if (_command.action == 6) {
			if(!mAndroidCheatExport(core,_command.path,sizeof(_command.path)))snprintf(_command.error,sizeof(_command.error),"导出金手指失败");
		} else if (_command.action == 8) { mCheatPressButton(core->cheatDevice(core),true);_cheatButtonFrames=2; }
		else if(!mAndroidCheatChange(core,_command.action,_command.value,_command.fileKind,_command.floatValue!=0,_command.path,_command.payload,_command.error,sizeof(_command.error)) && !_command.error[0])snprintf(_command.error,sizeof(_command.error),"保存金手指失败，原列表已保留");
		break;
	case ANDROID_COMMAND_SET_INT:
		mCoreConfigSetIntValue(&core->config, _command.key, _command.value);
		mCoreLoadForeignConfig(core, &core->config);
		if (!strcmp(_command.key, "suspendScreensaver")) {
			mSDLSetScreensaverSuspendable(&_activeRenderer->events, core->opts.suspendScreensaver);
		}
		if (!strcmp(_command.key, "rewindEnable") || !strcmp(_command.key, "rewindBufferCapacity") || !strcmp(_command.key, "rewindBufferInterval")) {
			mCoreThreadRewindParamsChanged(thread);
		}
		thread->impl->sync.fpsTarget = core->opts.fpsTarget;
		if (_fastForward || _fastForwardHeld) {
			_updateFastForward(thread, false);
		} else {
			mCoreSyncSetVideoSync(&thread->impl->sync, core->opts.videoSync);
			thread->impl->sync.audioWait = core->opts.audioSync;
		}
		_saveConfig(core);
		break;
	case ANDROID_COMMAND_SET_FLOAT:
		mCoreConfigSetFloatValue(&core->config, _command.key, _command.floatValue);
		mCoreLoadForeignConfig(core, &core->config);
		thread->impl->sync.fpsTarget = core->opts.fpsTarget;
		if (_fastForward || _fastForwardHeld) {
			_updateFastForward(thread, false);
		}
		_saveConfig(core);
		break;
	case ANDROID_COMMAND_SET_STRING:
		mCoreConfigSetValue(&core->config, _command.key, _command.path);
		mCoreLoadForeignConfig(core, &core->config);
		mDirectorySetMapOptions(&core->dirs, &core->opts);
		_saveConfig(core);
		break;
	case ANDROID_COMMAND_CLEAR_OPTION: {
		ConfigurationClearValue(&core->config.configTable, core->config.port, _command.key);
		struct VDir** directory = NULL;
		char** option = NULL;
		if (!strcmp(_command.key, "savegamePath")) {
			directory = &core->dirs.save;
			option = &core->opts.savegamePath;
		} else if (!strcmp(_command.key, "savestatePath")) {
			directory = &core->dirs.state;
			option = &core->opts.savestatePath;
		} else if (!strcmp(_command.key, "screenshotPath")) {
			directory = &core->dirs.screenshot;
			option = &core->opts.screenshotPath;
		} else if (!strcmp(_command.key, "patchPath")) {
			directory = &core->dirs.patch;
			option = &core->opts.patchPath;
		} else if (!strcmp(_command.key, "cheatsPath")) {
			directory = &core->dirs.cheats;
			option = &core->opts.cheatsPath;
		}
		if (directory && option) {
			if (*directory && *directory != core->dirs.base) {
				(*directory)->close(*directory);
			}
			*directory = core->dirs.base;
			free(*option);
			*option = NULL;
		}
		mCoreLoadForeignConfig(core, &core->config);
		mDirectorySetMapOptions(&core->dirs, &core->opts);
		_saveConfig(core);
		break;
	}
	case ANDROID_COMMAND_HOST_MUTE:
		if (_command.value) {
			core->opts.mute = true;
		} else {
			mCoreConfigGetBoolValue(&core->config, "mute", &core->opts.mute);
		}
		if (_fastForward) {
			_updateFastForward(thread, false);
		} else {
			core->reloadConfigOption(core, NULL, NULL);
		}
		break;
	case ANDROID_COMMAND_UPDATE_SPEED:
		_updateFastForward(thread, _command.value != 0);
		break;
	case ANDROID_COMMAND_FILE: {
			if (_command.fileKind == 8 || _command.fileKind == 9) {
				_command.value = mAndroidImportSave(core, _command.path, _command.fileKind == 8, _command.error, sizeof(_command.error));
				break;
			}
		struct VFile* vf = VFileOpen(_command.path, O_RDONLY);
		if (!vf) {
			break;
		}
		if (_command.fileKind >= 1 && _command.fileKind <= 4) {
			const char* key = "gba.bios";
			if (_command.fileKind == 2) key = "gb.bios";
			if (_command.fileKind == 3) key = "gbc.bios";
			if (_command.fileKind == 4) key = "sgb.bios";
			bool valid = false;
			if (core->platform(core) == mPLATFORM_GB) {
				enum GBModel model = GB_MODEL_DMG;
				if (_command.fileKind == 3) model = GB_MODEL_CGB;
				if (_command.fileKind == 4) model = GB_MODEL_SGB;
				valid = GBIsBIOS(vf) && GBIsCompatibleBIOS(vf, model);
				vf->seek(vf, 0, SEEK_SET);
				if (valid) {
					struct GB* gb = core->board;
					if (gb->biosVf) {
						gb->biosVf->close(gb->biosVf);
						gb->biosVf = NULL;
					}
					valid = core->loadBIOS(core, vf, 0);
				}
			} else {
				valid = core->loadBIOS(core, vf, 0);
			}
			if (valid) {
				mCoreConfigSetValue(&core->config, key, _command.path);
				mCoreConfigSetIntValue(&core->config, "useBios", 1);
				mCoreConfigSetIntValue(&core->config, "skipBios", 0);
				mCoreLoadForeignConfig(core, &core->config);
				_saveConfig(core);
				mAndroidOverlaySetMessage("BIOS loaded");
			} else {
				vf->close(vf);
				mAndroidOverlaySetMessage("Invalid or incompatible BIOS");
			}
		} else if (_command.fileKind == 5) {
			if (!core->loadPatch(core, vf)) {
				vf->close(vf);
				mAndroidOverlaySetMessage("Patch could not be loaded");
			} else {
				mAndroidOverlaySetMessage("Patch loaded");
			}
		} else if (_command.fileKind == 6) {
			struct mCheatDevice* device = core->cheatDevice(core);
			if (device) {
				mCheatParseFile(device, vf);
				int autosave = 1;
				mCoreConfigGetIntValue(&core->config, "cheatAutosave", &autosave);
				device->autosave = autosave;
#if defined(ENABLE_VFS) && defined(ENABLE_DIRECTORIES)
				if (device->autosave) mCheatAutosave(device);
#endif
				mAndroidOverlaySetMessage("Cheat file imported");
			}
			vf->close(vf);
		} else {
			vf->close(vf);
		}
		break;
	}
	case ANDROID_COMMAND_GAME_ACTION:
		if (_command.action == 2) {
			bool saved = mCoreSaveState(core, _command.value, _stateFlags(core, true));
				if (!saved) snprintf(_command.error, sizeof(_command.error), "即时存档保存失败，请检查存储空间和路径");
			mAndroidOverlaySetMessage(saved ? "State saved" : "State save failed");
		} else if (_command.action == 3) {
				char backup[4096];
				if (!mAndroidBackupState(core, "before-slot", backup, sizeof(backup))) {
					snprintf(_command.error, sizeof(_command.error), "自动备份失败，未读取存档");
					break;
				}
				bool loaded = mCoreLoadState(core, _command.value, _stateFlags(core, false));
				if (!loaded) {
					struct VFile* vf = VFileOpen(backup, O_RDONLY);
					bool restored = vf && mCoreLoadStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
					if (vf) vf->close(vf);
					snprintf(_command.error, sizeof(_command.error), restored ? "此存档位不可用，已恢复原进度" : "存档加载失败，请从自动备份恢复进度");
				}
			mAndroidOverlaySetMessage(loaded ? "State loaded" : "State load failed");
			} else if (_command.action == 9) {
				struct VFile* vf = mCoreGetState(core, _command.value, false);
				_command.value = vf && vf->size(vf) > 0;
				if (vf) vf->close(vf);
			} else if (_command.action == 10) {
				char backup[4096];
				if (!mAndroidBackupState(core, "before-rom", backup, sizeof(backup)))
					snprintf(_command.error, sizeof(_command.error), "当前进度备份失败，未切换游戏");
			} else if (_command.action == 11 || _command.action == 12) {
				if (!mAndroidExportSave(core, _command.action == 12, _command.path, sizeof(_command.path)))
					snprintf(_command.error, sizeof(_command.error), "无法生成导出副本：请确认游戏支持存档且存储空间充足");
		} else if (_command.action == 4) {
			mCoreTakeScreenshot(core);
			mAndroidOverlaySetMessage("Screenshot captured");
		} else if (_command.action == 5) {
			_fastForward = !_fastForward;
			_updateFastForward(thread, true);
			mAndroidOverlaySetMessage(_fastForward ? "Fast-forward ON" : "Fast-forward OFF");
		} else if (_command.action == 8 && _command.value == 1) {
			struct mCheatDevice* device = core->cheatDevice(core);
			if (device) {
				mCheatDeviceClear(device);
#if defined(ENABLE_VFS) && defined(ENABLE_DIRECTORIES)
				if (device->autosave) mCheatAutosave(device);
#endif
				mAndroidOverlaySetMessage("Cheats cleared");
			}
		} else if (_command.action == 8 && _command.value == 0) {
			mInputMapDeinit(&core->inputMap);
			if (core->platform(core) == mPLATFORM_GB) {
				mInputMapInit(&core->inputMap, &GBInputInfo);
				_initGBBindings(&core->inputMap);
			} else {
				mInputMapInit(&core->inputMap, &GBAInputInfo);
				mSDLInitBindingsGBA(&core->inputMap);
			}
			mInputMapSave(&core->inputMap, SDL_BINDING_KEY, mCoreConfigGetInput(&core->config));
			mInputMapSave(&core->inputMap, SDL_BINDING_CONTROLLER, mCoreConfigGetInput(&core->config));
			_saveConfig(core);
		}
		break;
	}
}

static void _submitCommand(void) {
	if (_activeThread && _activeThread->impl && mCoreThreadIsActive(_activeThread)) {
		mCoreThreadRunFunction(_activeThread, _runAndroidCommand);
		_applySyncPolicy(_activeThread);
	}
}

static void _updateRendererOptions(void) {
	if (!_activeRenderer || !_activeThread) {
		return;
	}
	struct mCoreOptions* opts = &_activeThread->core->opts;
	_activeRenderer->lockAspectRatio = opts->lockAspectRatio;
	_activeRenderer->lockIntegerScaling = opts->lockIntegerScaling;
	_activeRenderer->interframeBlending = opts->interframeBlending;
	_activeRenderer->filter = opts->resampleVideo;
#if SDL_VERSION_ATLEAST(2, 0, 12)
	if (_activeRenderer->sdlTex) {
		SDL_SetTextureScaleMode(_activeRenderer->sdlTex, opts->resampleVideo ? SDL_ScaleModeLinear : SDL_ScaleModeNearest);
	}
#endif
}

static void _reopenAudio(void) {
	if (!_activeAudio || !_activeThread) {
		return;
	}
	mSDLPauseAudio(_activeAudio);
	mSDLDeinitAudio(_activeAudio);
	_activeAudio->samples = _activeThread->core->opts.audioBuffers;
	_activeAudio->sampleRate = _activeThread->core->opts.sampleRate ? _activeThread->core->opts.sampleRate : 44100;
	if (!mSDLInitAudio(_activeAudio, _activeThread)) {
		_activeAudio = NULL;
	} else if (mCoreThreadIsPaused(_activeThread)) {
		mSDLPauseAudio(_activeAudio);
	}
}

static bool _copyJavaString(JNIEnv* env, jstring input, char* output, size_t outputSize) {
	if (!input || !output || outputSize < 2) {
		return false;
	}
	const char* utf = (*env)->GetStringUTFChars(env, input, NULL);
	if (!utf) {
		return false;
	}
	snprintf(output, outputSize, "%s", utf);
	(*env)->ReleaseStringUTFChars(env, input, utf);
	return true;
}

JNIEXPORT jboolean JNICALL Java_org_libsdl_app_MGBAMenu_nativeIsGameLoaded(JNIEnv* env, jclass clazz) {
	UNUSED(env);
	UNUSED(clazz);
	return _activeThread && _activeThread->impl && mCoreThreadIsActive(_activeThread);
}

JNIEXPORT jint JNICALL Java_org_libsdl_app_MGBAMenu_nativeGetPlatform(JNIEnv* env, jclass clazz) {
	UNUSED(env);
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->core) {
		return -1;
	}
	return _activeThread->core->platform(_activeThread->core);
}

JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeGetOption(JNIEnv* env, jclass clazz, jstring keyString) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->core) {
		return (*env)->NewStringUTF(env, "");
	}
	char key[128];
	if (!_copyJavaString(env, keyString, key, sizeof(key))) {
		return (*env)->NewStringUTF(env, "");
	}
	const char* value = mCoreConfigGetValue(&_activeThread->core->config, key);
	return (*env)->NewStringUTF(env, value ? value : "");
}

JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeGetGameInfo(JNIEnv* env, jclass clazz) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->core || !_activeThread->core->getGameInfo) {
		return (*env)->NewStringUTF(env, "未加载游戏");
	}
	struct mGameInfo info = {0};
	_activeThread->core->getGameInfo(_activeThread->core, &info);
	info.title[sizeof(info.title) - 1] = '\0';
	info.system[sizeof(info.system) - 1] = '\0';
	info.code[sizeof(info.code) - 1] = '\0';
	info.maker[sizeof(info.maker) - 1] = '\0';
	char details[256];
	snprintf(details, sizeof(details), "游戏名称：%s\n游戏平台：%s\n游戏代码：%s\n发行商：%s\n版本：%u",
		info.title, info.system, info.code, info.maker, info.version);
	return (*env)->NewStringUTF(env, details);
}

static void pauseGameInternal(bool pauseAudioDevice) {
	if (!_activeThread || !_activeThread->impl || !mCoreThreadIsActive(_activeThread)) {
		return;
	}
	if (_rewinding) {
		mCoreThreadSetRewinding(_activeThread, false);
		_rewinding = false;
	}
	if (_fastForwardHeld) {
		_fastForwardHeld = false;
		_command.type = ANDROID_COMMAND_UPDATE_SPEED;
		_command.value = 1;
		_submitCommand();
	}
	if (!mCoreThreadIsPaused(_activeThread)) {
		if (pauseAudioDevice && _activeAudio) {
			mSDLPauseAudio(_activeAudio);
		}
		mCoreThreadPause(_activeThread);
	}
}

static void resumeGameInternal(bool resumeAudioDevice) {
	if (mAndroidNetClient()) return;
	if (!_activeThread || !_activeThread->impl || !mCoreThreadIsActive(_activeThread)) {
		return;
	}
	mCoreSyncSetVideoSync(&_activeThread->impl->sync, !(_fastForward || _fastForwardHeld) && _activeThread->core->opts.videoSync);
	if (mCoreThreadIsPaused(_activeThread)) {
		mCoreThreadUnpause(_activeThread);
	}
	_applySyncPolicy(_activeThread);
	if (resumeAudioDevice && _activeAudio) {
		mSDLResumeAudio(_activeAudio);
	}
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativePauseGame(JNIEnv* env, jclass clazz) {
	UNUSED(env);
	UNUSED(clazz);
	_menuPaused = true;
	pauseGameInternal(true);
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeResumeGame(JNIEnv* env, jclass clazz) {
	UNUSED(env);
	UNUSED(clazz);
	_menuPaused = false;
	resumeGameInternal(true);
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeHostPause(JNIEnv* env, jclass clazz, jboolean minimized, jboolean menuOpen) {
	UNUSED(env);
	UNUSED(clazz);
	UNUSED(menuOpen);
	if (!_activeThread || !_activeThread->impl || !mCoreThreadIsActive(_activeThread)) {
		return;
	}
	bool pause = false;
	bool mute = false;
	if (minimized) {
		mCoreConfigGetBoolValue(&_activeThread->core->config, "pauseOnMinimize", &pause);
		mCoreConfigGetBoolValue(&_activeThread->core->config, "muteOnMinimize", &mute);
		_minimizePaused = pause;
		_minimizeMuted = mute;
	} else {
		mCoreConfigGetBoolValue(&_activeThread->core->config, "pauseOnFocusLost", &pause);
		mCoreConfigGetBoolValue(&_activeThread->core->config, "muteOnFocusLost", &mute);
		_focusPaused = pause;
		_focusMuted = mute;
	}
	if ((_focusPaused || _minimizePaused) && !_hostPaused) {
		/*
		 * Keep host transitions out of SDL_PauseAudioDevice. SDL's Android
		 * pause path holds the device mixer lock until nativeResume; on a
		 * focus-only pause, the paused core lets the callback drain its buffer.
		 */
		pauseGameInternal(false);
		_hostPaused = true;
	}
	_updateHostMute(_activeThread);
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeHostResume(JNIEnv* env, jclass clazz, jboolean minimized, jboolean menuOpen) {
	UNUSED(env);
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !mCoreThreadIsActive(_activeThread)) {
		return;
	}
	if (minimized) {
		_minimizePaused = false;
		_minimizeMuted = false;
	} else {
		_focusPaused = false;
		_focusMuted = false;
	}
	_updateHostMute(_activeThread);
	if (_hostPaused && !_focusPaused && !_minimizePaused) {
		_hostPaused = false;
		if (!menuOpen) {
				/* Leave device-level resume to SDL's Android nativeResume path. */
				resumeGameInternal(false);
		}
	}
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeSetOptionInt(JNIEnv* env, jclass clazz, jstring keyString, jint value) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !_copyJavaString(env, keyString, _command.key, sizeof(_command.key))) {
		return;
	}
	_command.type = ANDROID_COMMAND_SET_INT;
	_command.value = value;
	_submitCommand();
	_updateRendererOptions();
	if (!strcmp(_command.key, "useBios") || !strcmp(_command.key, "skipBios") || !strcmp(_command.key, "gba.forceGbp") || !strcmp(_command.key, "vbaBugCompat")) {
		mCoreThreadReset(_activeThread);
	}
	if (!strcmp(_command.key, "audioBuffers") || !strcmp(_command.key, "sampleRate")) {
		_reopenAudio();
	}
	if (!strcmp(_command.key, "android.touchControls") || !strcmp(_command.key, "android.touchSize") || !strcmp(_command.key, "android.touchOpacity")) {
		mCoreConfigGetIntValue(&_activeThread->core->config, "android.touchControls", &_touchControlsVisible);
		mCoreConfigGetIntValue(&_activeThread->core->config, "android.touchSize", &_touchSizePercent);
		mCoreConfigGetIntValue(&_activeThread->core->config, "android.touchOpacity", &_touchOpacityPercent);
		mAndroidOverlaySetOptions(_touchControlsVisible, _touchSizePercent, _touchOpacityPercent);
	}
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeSetOverlayOptions(JNIEnv* env, jclass clazz, jint visible, jint size, jint opacity) {
	UNUSED(env);
	UNUSED(clazz);
	_touchControlsVisible = visible != 0;
	_touchSizePercent = size;
	_touchOpacityPercent = opacity;
	mAndroidOverlaySetOptions(_touchControlsVisible, _touchSizePercent, _touchOpacityPercent);
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeSetOptionFloat(JNIEnv* env, jclass clazz, jstring keyString, jfloat value) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !_copyJavaString(env, keyString, _command.key, sizeof(_command.key))) {
		return;
	}
	_command.type = ANDROID_COMMAND_SET_FLOAT;
	_command.floatValue = value;
	_submitCommand();
	_updateRendererOptions();
	if (!strcmp(_command.key, "fastForwardRatio") && mAndroidNetBusy() && mAndroidNetSpeed() > 1000)
		mAndroidNetRequestSpeed(value > 1 && value < 10 ? (int) (value * 1000) : 10000);
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeSetOptionString(JNIEnv* env, jclass clazz, jstring keyString, jstring valueString) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !_copyJavaString(env, keyString, _command.key, sizeof(_command.key)) ||
	    !_copyJavaString(env, valueString, _command.path, sizeof(_command.path))) {
		return;
	}
	_command.type = ANDROID_COMMAND_SET_STRING;
	_submitCommand();
	_updateRendererOptions();
	if (!strcmp(_command.key, "gb.model") || !strcmp(_command.key, "sgb.model") || !strcmp(_command.key, "cgb.model") ||
	    !strcmp(_command.key, "cgb.hybridModel") || !strcmp(_command.key, "cgb.sgbModel")) {
		mCoreThreadReset(_activeThread);
	}
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeClearOption(JNIEnv* env, jclass clazz, jstring keyString) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !_copyJavaString(env, keyString, _command.key, sizeof(_command.key))) {
		return;
	}
	_command.type = ANDROID_COMMAND_CLEAR_OPTION;
	_submitCommand();
	_updateRendererOptions();
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeGameAction(JNIEnv* env, jclass clazz, jint action, jint value) {
	UNUSED(env);
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !mCoreThreadIsActive(_activeThread)) {
		return;
	}
	if (action == 1) {
		mCoreThreadReset(_activeThread);
	} else if (action == 5) {
		SDL_Event event = {0}; event.type = SDL_KEYDOWN;
		event.key.keysym.sym = SDLK_F9;
		SDL_PushEvent(&event);
	} else if (action == 6) {
		_rewinding = !_rewinding;
		mCoreThreadSetRewinding(_activeThread, _rewinding);
		mAndroidOverlaySetMessage(_rewinding ? "Rewind ON" : "Rewind OFF");
	} else if (action == 7) {
		mAndroidNetShutdown();
		mCoreThreadEnd(_activeThread);
	} else {
		_command.type = ANDROID_COMMAND_GAME_ACTION;
		_command.action = action;
		_command.value = value;
		_submitCommand();
	}
}

JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeRomPath(JNIEnv* env,jclass clazz) {
	UNUSED(clazz);return _activeThread?(*env)->NewStringUTF(env,mAndroidGamePath()):NULL;
}
JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeGameFolder(JNIEnv* env,jclass clazz) {
	UNUSED(clazz);char folder[4096];return _activeThread && mAndroidGameFolder(_activeThread->core,folder,sizeof(folder))?(*env)->NewStringUTF(env,folder):NULL;
}
JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBACheats_nativeAction(JNIEnv* env,jclass clazz,jint action,jint index,jint type,jboolean enabled,jstring name,jstring text) {
	UNUSED(clazz);
	if(!_activeThread || !_activeThread->impl)return (*env)->NewStringUTF(env,"请先打开游戏");
	if(mAndroidNetBusy())return (*env)->NewStringUTF(env,"请先正常断开联机，再修改金手指");
	if(action<0 || action>8)return (*env)->NewStringUTF(env,"操作无效");
	if(text && (*env)->GetStringUTFLength(env,text)>32768)return (*env)->NewStringUTF(env,"代码过长，最多 32 KiB");
	_command.type=ANDROID_COMMAND_CHEATS;_command.action=action;_command.value=index;_command.fileKind=type;_command.floatValue=enabled?1:0;
	_command.path[0]=_command.error[0]=0;_command.result=NULL;_command.payload=NULL;
	if(name && !_copyJavaString(env,name,_command.path,sizeof(_command.path)))return (*env)->NewStringUTF(env,"名称过长");
	if(text){const char* p=(*env)->GetStringUTFChars(env,text,NULL);if(!p)return NULL;_command.payload=strdup(p);(*env)->ReleaseStringUTFChars(env,text,p);if(!_command.payload)return (*env)->NewStringUTF(env,"内存不足");}
	_submitCommand();free(_command.payload);_command.payload=NULL;
	jstring result=NULL;
	if(_command.error[0])result=(*env)->NewStringUTF(env,_command.error);
	else if(action==0)result=(*env)->NewStringUTF(env,_command.result?_command.result:"[]");
	else if(action==6)result=(*env)->NewStringUTF(env,_command.path);
	free(_command.result);_command.result=NULL;return result;
}
JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeFileSelected(JNIEnv* env, jclass clazz, jint kind, jstring pathString) {
	UNUSED(clazz);
	if (!_copyJavaString(env, pathString, _command.path, sizeof(_command.path))) {
		return;
	}
	if (kind == 7) {
		if (!_activeThread || !_activeThread->impl) {
			SDL_Event event = {0};
			event.type = SDL_USEREVENT;
			event.user.code = ANDROID_ROM_PICKED_EVENT;
			event.user.data1 = strdup(_command.path);
			if (SDL_PushEvent(&event) < 0) free(event.user.data1);
			return;
		}
		free(_nextRomPath);
		_nextRomPath = strdup(_command.path);
		SDL_Event event = {0};
		event.type = SDL_QUIT;
		SDL_PushEvent(&event);
		return;
	}
	if (!_activeThread || !_activeThread->impl) return;
	_command.type = ANDROID_COMMAND_FILE;
	_command.fileKind = kind;
	_submitCommand();
	if (kind >= 1 && kind <= 4) {
		mCoreThreadReset(_activeThread);
	}
}

JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeCancelRomPicker(JNIEnv* env, jclass clazz) {
	UNUSED(env);
	UNUSED(clazz);
	if (_activeThread) {
		return;
	}
	SDL_Event event = {0};
	event.type = SDL_QUIT;
	SDL_PushEvent(&event);
}

JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeImportSave(JNIEnv* env, jclass clazz, jint kind, jstring pathString) {
	UNUSED(clazz);
	if (mAndroidNetBusy()) return (*env)->NewStringUTF(env, "请先正常断开联机，再加载存档");
	if (!_activeThread || !_activeThread->impl) return (*env)->NewStringUTF(env, "请先加载对应的 ROM");
	if ((kind != 8 && kind != 9) || !_copyJavaString(env, pathString, _command.path, sizeof(_command.path)))
		return (*env)->NewStringUTF(env, "无效的存档路径");
	_command.type = ANDROID_COMMAND_FILE;
	_command.fileKind = kind;
	_command.value = 0;
	_command.error[0] = 0;
	_submitCommand();
	return _command.value ? NULL : (*env)->NewStringUTF(env, _command.error);
}

JNIEXPORT jboolean JNICALL Java_org_libsdl_app_MGBAMenu_nativeNetBusy(JNIEnv* env, jclass clazz) {
	UNUSED(env); UNUSED(clazz); return mAndroidNetBusy();
}

JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBASnapshots_nativeAction(JNIEnv* env, jclass clazz, jint fd, jboolean save) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !mCoreThreadIsActive(_activeThread))
		return (*env)->NewStringUTF(env, "请先打开游戏");
	if (mAndroidNetBusy()) return (*env)->NewStringUTF(env, "请先正常断开联机");
	struct VFile* vf = VFileFromFD(dup(fd));
	if (!vf) return (*env)->NewStringUTF(env, "无法打开快照文件");
	_command.type = ANDROID_COMMAND_SNAPSHOT;
	_command.action = save;
	_command.value = 0;
	_command.error[0] = 0;
	_command.snapshot = vf;
	_submitCommand();
	_command.snapshot = NULL;
	vf->close(vf);
	return _command.value ? NULL : (*env)->NewStringUTF(env, _command.error[0] ? _command.error : "游戏已关闭");
}
JNIEXPORT jboolean JNICALL Java_org_libsdl_app_MGBAMenu_nativeNetHosting(JNIEnv* env, jclass clazz) {
	UNUSED(env); UNUSED(clazz); return mAndroidNetHosting();
}
JNIEXPORT jboolean JNICALL Java_org_libsdl_app_MGBAMenu_nativeSpeedActive(JNIEnv* env, jclass clazz) {
	UNUSED(env); UNUSED(clazz); return mAndroidNetBusy() ? mAndroidNetSpeed() > 1000 : _fastForward || _fastForwardHeld;
}

JNIEXPORT jboolean JNICALL Java_org_libsdl_app_MGBAMenu_nativeSlotOccupied(JNIEnv* env, jclass clazz, jint slot) {
	UNUSED(env); UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || slot < 1 || slot > 9 || mAndroidNetBusy()) return false;
	_command.type = ANDROID_COMMAND_GAME_ACTION; _command.action = 9; _command.value = slot;
	_submitCommand(); return _command.value != 0;
}
JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeSlotAction(JNIEnv* env, jclass clazz, jint slot, jboolean save) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || slot < 1 || slot > 9) return (*env)->NewStringUTF(env, "请先打开游戏");
	if (mAndroidNetBusy()) return (*env)->NewStringUTF(env, "请先正常断开联机");
	_command.type = ANDROID_COMMAND_GAME_ACTION; _command.action = save ? 2 : 3; _command.value = slot; _command.error[0] = 0;
	_submitCommand();
	return _command.error[0] ? (*env)->NewStringUTF(env, _command.error) : NULL;
}
JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeBackupCurrent(JNIEnv* env, jclass clazz) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl) return NULL;
	if (mAndroidNetBusy()) return (*env)->NewStringUTF(env, "请先正常断开联机");
	_command.type = ANDROID_COMMAND_GAME_ACTION; _command.action = 10; _command.error[0] = 0;
	_submitCommand();
	return _command.error[0] ? (*env)->NewStringUTF(env, _command.error) : NULL;
}
JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeExportCurrent(JNIEnv* env, jclass clazz, jboolean battery) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || !mCoreThreadIsActive(_activeThread)) return (*env)->NewStringUTF(env, "请先打开游戏");
	if (mAndroidNetBusy()) return (*env)->NewStringUTF(env, "请先正常断开联机，再导出自己的最新进度");
	_command.type = ANDROID_COMMAND_GAME_ACTION; _command.action = battery ? 12 : 11;
	_command.path[0] = _command.error[0] = 0; _submitCommand();
	return (*env)->NewStringUTF(env, _command.error[0] ? _command.error : _command.path);
}
JNIEXPORT jboolean JNICALL Java_org_libsdl_app_MGBAMenu_nativeIsRomFile(JNIEnv* env, jclass clazz, jstring pathString) {
	UNUSED(clazz); char path[4096];
	if (!_copyJavaString(env, pathString, path, sizeof(path))) return false;
	struct mCore* core = mCoreFind(path);
	if (!core) return false;
	free(core); return true;
}
JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeNetStatus(JNIEnv* env, jclass clazz) {
	UNUSED(clazz); char text[1024]; mAndroidNetStatus(text, sizeof(text)); return (*env)->NewStringUTF(env, text);
}
JNIEXPORT void JNICALL Java_org_libsdl_app_MGBAMenu_nativeNetStop(JNIEnv* env, jclass clazz) {
	UNUSED(env); UNUSED(clazz); mAndroidNetStop();
}
JNIEXPORT jstring JNICALL Java_org_libsdl_app_MGBAMenu_nativeNetStart(JNIEnv* env, jclass clazz, jboolean host, jstring addressString, jstring codeString) {
	UNUSED(clazz);
	if (!_activeThread || !_activeThread->impl || _activeThread->core->platform(_activeThread->core) != mPLATFORM_GBA)
		return (*env)->NewStringUTF(env, "请先加载 GBA ROM 和自己的存档；本原型暂不支持 GB/GBC");
	char address[64], code[16];
	if (!_copyJavaString(env, addressString, address, sizeof(address)) || !_copyJavaString(env, codeString, code, sizeof(code)))
		return (*env)->NewStringUTF(env, "地址或房间码无效");
	if (!host) _fastForward = _fastForwardHeld = false;
	_rewinding = false;
	mCoreThreadSetRewinding(_activeThread, false);
	_command.type = ANDROID_COMMAND_UPDATE_SPEED; _command.value = 1; _submitCommand();
	if (!mAndroidNetStart(host, address, code)) return (*env)->NewStringUTF(env, "启动失败：请检查 IPv4 地址、六位房间码或已有会话");
	if (host && (_fastForward || _fastForwardHeld)) {
		float ratio = -1; mCoreConfigGetFloatValue(&_activeThread->core->config, "fastForwardRatio", &ratio);
		mAndroidNetSetSpeed(ratio > 1 && ratio < 10 ? (int) (ratio * 1000) : 10000);
	}
	_command.type = ANDROID_COMMAND_UPDATE_SPEED; _command.value = 1; _submitCommand();
	return NULL;
}

static void _publishHud(const char* title, float fps, float emu, float target, bool showFps, bool fast, bool remote, bool showOsd) {
	JNIEnv* env = SDL_AndroidGetJNIEnv();
	if (!env) return;
	jclass hud = (*env)->FindClass(env, "org/libsdl/app/MGBAHud");
	if (hud) {
		jmethodID update = (*env)->GetStaticMethodID(env, hud, "update", "(Ljava/lang/String;FFFZZZZII)V");
		if (update) {
			int right, bottom; mAndroidOverlayHudInsets(&right, &bottom);
			jstring game = (*env)->NewStringUTF(env, title);
			(*env)->CallStaticVoidMethod(env, hud, update, game, fps, emu, target, showFps, fast, remote, showOsd, right, bottom);
			(*env)->DeleteLocalRef(env, game);
		}
		(*env)->DeleteLocalRef(env, hud);
	}
	if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}

static void mSDLAndroidRunloop(struct mSDLRenderer* renderer, void* user) {
	struct mCoreThread* context = user;
	SDL_Event event;
	Uint32 fpsWindowStart = SDL_GetTicks();
	unsigned fpsWindowFrames = 0;
	unsigned emulatedLast = SDL_AtomicGet(&_emulatedFrameCounter);
	float emulatedFps = 0.0f;
	uint32_t remoteKeys = 0;
	float displayedFps = 0.0f;
	Uint64 timingFrequency = SDL_GetPerformanceFrequency();
	Uint64 coreWaitTicks = 0, drawTicks = 0, presentTicks = 0, textureLockTicks = 0;
	unsigned timingFrames = 0;
	Uint32 textureFormat = SDL_PIXELFORMAT_UNKNOWN;
	int textureAccess = 0, textureWidth = 0, textureHeight = 0;
	SDL_QueryTexture(renderer->sdlTex, &textureFormat, &textureAccess, &textureWidth, &textureHeight);
	SDL_Texture* previousTexture = SDL_CreateTexture(renderer->sdlRenderer, textureFormat, SDL_TEXTUREACCESS_STATIC, textureWidth, textureHeight);
	if (previousTexture) {
		SDL_SetTextureBlendMode(previousTexture, SDL_BLENDMODE_BLEND);
		SDL_SetTextureAlphaMod(previousTexture, 128);
	}
	size_t framePitch = renderer->outputBufferStride * BYTES_PER_PIXEL;
	size_t frameBytes = framePitch * renderer->height;
	Uint8* previousFrame = malloc(frameBytes);
	Uint8* currentFrame = malloc(frameBytes);
	if (!previousFrame || !currentFrame) {
		free(previousFrame); free(currentFrame);
		if (previousTexture) SDL_DestroyTexture(previousTexture);
		SDL_LogError(SDL_LOG_CATEGORY_APPLICATION, "Cannot allocate Android video snapshots");
		mCoreThreadEnd(context);
		return;
	}
	bool hasPreviousFrame = false;
	Uint32 lastDraw = 0;
	int rendererVsync = 1;
	bool previousRemote = false;
	bool previousPaused = false;
	Uint32 lastHudUpdate = 0;
	char pacingProperty[PROP_VALUE_MAX] = {0};
	__system_property_get("debug.mgba.frame_timing", pacingProperty);
	bool tracePacing = !strcmp(pacingProperty, "1");
	Uint64 previousPresent = 0;
	if (tracePacing) {
		SDL_RendererInfo info = {0};
		SDL_GetRendererInfo(renderer->sdlRenderer, &info);
		SDL_Log("mGBA pacing renderer=%s flags=%u audio=%zu", info.name, info.flags, renderer->audio.samples);
	}

	while (mCoreThreadIsActive(context)) {
		bool paused = _menuPaused || _hostPaused;
		if (paused != previousPaused) {
			previousPaused = paused; fpsWindowStart = SDL_GetTicks(); fpsWindowFrames = 0;
			emulatedLast = mAndroidNetClient() ? mAndroidNetFrameNumber() : SDL_AtomicGet(&_emulatedFrameCounter);
			displayedFps = emulatedFps = 0;
		}
		if (!mAndroidNetClient()) remoteKeys = 0;
		if (mAndroidNetClient() && (_menuPaused || _hostPaused)) {
			remoteKeys = 0;
			mAndroidNetKeys(0);
		}
		if (mAndroidNetTakeResume()) {
			_fastForward = _fastForwardHeld = false;
			_command.type = ANDROID_COMMAND_UPDATE_SPEED; _command.value = 1; _submitCommand();
			if (!_menuPaused && !_hostPaused) resumeGameInternal(true);
		}
		if (mAndroidNetBusy() && !mAndroidNetClient()) {
			int speed = mAndroidNetTakeSpeedRequest();
			if (speed >= 1000) {
				mAndroidNetSetSpeed(speed); _fastForward = speed > 1000; _fastForwardHeld = false;
				_command.type = ANDROID_COMMAND_UPDATE_SPEED; _command.value = speed == 1000; _submitCommand();
			}
		}
		while (SDL_PollEvent(&event)) {
			if (event.type == SDL_QUIT) {
				mAndroidNetShutdown();
				mCoreThreadEnd(context);
				continue;
			}
			mAndroidOverlayHandleEvent(&event);
			if ((event.type == SDL_KEYDOWN || event.type == SDL_KEYUP) &&
			    (event.key.keysym.sym == SDLK_F9 || event.key.keysym.sym == SDLK_TAB)) {
				bool toggle = event.key.keysym.sym == SDLK_F9;
				if (toggle && (event.type != SDL_KEYDOWN || event.key.repeat)) continue;
				if (mAndroidNetBusy()) {
					float ratio = -1; mCoreConfigGetFloatValue(&context->core->config, toggle ? "fastForwardRatio" : "fastForwardHeldRatio", &ratio);
					int speed = ratio > 1 && ratio < 10 ? (int) (ratio * 1000) : 10000;
					bool on = toggle ? mAndroidNetSpeed() <= 1000 : event.type == SDL_KEYDOWN;
					mAndroidNetRequestSpeed(on ? speed : 1000);
				} else {
					if (toggle) _fastForward = !_fastForward;
					else _fastForwardHeld = event.type == SDL_KEYDOWN;
					_command.type = ANDROID_COMMAND_UPDATE_SPEED; _command.value = !(_fastForward || _fastForwardHeld); _submitCommand();
				}
				continue;
			}
			if (mAndroidNetClient() && (event.type == SDL_KEYDOWN || event.type == SDL_KEYUP || event.type == SDL_CONTROLLERBUTTONDOWN || event.type == SDL_CONTROLLERBUTTONUP)) {
				bool keyboard = event.type == SDL_KEYDOWN || event.type == SDL_KEYUP;
				int key = mInputMapKey(&context->core->inputMap, keyboard ? SDL_BINDING_KEY : SDL_BINDING_CONTROLLER, keyboard ? event.key.keysym.sym : event.cbutton.button);
				if (key >= 0 && key < 10) {
					bool pressed = event.type == SDL_KEYDOWN || event.type == SDL_CONTROLLERBUTTONDOWN;
					if (pressed) remoteKeys |= 1U << key; else remoteKeys &= ~(1U << key);
					mAndroidNetKeys(remoteKeys);
				}
				continue;
			}
			if (mAndroidNetBusy() && (event.type == SDL_KEYDOWN || event.type == SDL_KEYUP) && (event.key.keysym.sym == SDLK_TAB || event.key.keysym.sym == SDLK_BACKQUOTE)) continue;
			mSDLHandleEvent(context, &renderer->player, &event);
			if ((event.type == SDL_KEYDOWN || event.type == SDL_KEYUP) && event.key.keysym.sym == SDLK_TAB) {
				_fastForwardHeld = event.type == SDL_KEYDOWN;
				_command.type = ANDROID_COMMAND_UPDATE_SPEED;
				_command.value = event.type == SDL_KEYUP;
				_submitCommand();
			}
			if ((event.type == SDL_KEYDOWN || event.type == SDL_KEYUP) && event.key.keysym.sym == SDLK_BACKQUOTE) {
				_rewinding = event.type == SDL_KEYDOWN;
			}
		}

			Uint64 stageStart = SDL_GetPerformanceCounter();
				bool remote = mAndroidNetClient();
				if (remote != previousRemote) {
					previousRemote = remote; fpsWindowStart = SDL_GetTicks(); fpsWindowFrames = 0;
					emulatedLast = remote ? mAndroidNetFrameNumber() : SDL_AtomicGet(&_emulatedFrameCounter);
				}
				bool accelerating = remote ? mAndroidNetSpeed() > 1000 : (_fastForward || _fastForwardHeld);
				int wantVsync = !accelerating && context->core->opts.videoSync;
#if SDL_VERSION_ATLEAST(2, 0, 18)
				if (wantVsync != rendererVsync) {
					SDL_RenderSetVSync(renderer->sdlRenderer, wantVsync); rendererVsync = wantVsync;
				}
#endif
				mAndroidOverlaySetSpeed(accelerating, remote ? mAndroidNetSpeed() / 1000.0f : _speedRatio(context), mAndroidNetBusy());
				if (remote ? mAndroidNetCopyFrame(renderer->outputBuffer, renderer->outputBufferStride) : mCoreSyncWaitFrameStart(&context->impl->sync)) {
				coreWaitTicks += SDL_GetPerformanceCounter() - stageStart;
				if (!remote && accelerating && SDL_GetTicks() - lastDraw < 16) {
					mCoreSyncWaitFrameEnd(&context->impl->sync); SDL_Delay(1); continue;
				}
				lastDraw = SDL_GetTicks();
			int frameNumber = SDL_AtomicAdd(&_displayFrameCounter, 1) + 1;
			++fpsWindowFrames;
			Uint32 now = SDL_GetTicks();
				if (now - fpsWindowStart >= 500) {
				displayedFps = fpsWindowFrames * 1000.0f / (now - fpsWindowStart);
					unsigned emulatedNow = remote ? mAndroidNetFrameNumber() : SDL_AtomicGet(&_emulatedFrameCounter);
					emulatedFps = remote && (!emulatedLast || emulatedNow < emulatedLast) ? 0 : (emulatedNow - emulatedLast) * 1000.0f / (now - fpsWindowStart);
					emulatedLast = emulatedNow;
				fpsWindowFrames = 0;
				fpsWindowStart = now;
			}
			if (frameBytes && previousFrame && currentFrame) {
					if (!remote) SDL_LockMutex(_videoSnapshotMutex);
					memcpy(currentFrame, remote ? renderer->outputBuffer : _videoSnapshot, frameBytes);
					if (!remote) SDL_UnlockMutex(_videoSnapshotMutex);
			}
				/* Snapshot while the core's frame lock is held, then let it build
				 * the next frame while SDL uploads and presents this one. */
				if (!remote) mCoreSyncWaitFrameEnd(&context->impl->sync);
				Uint64 uploadStart = SDL_GetPerformanceCounter();
				SDL_UpdateTexture(renderer->sdlTex, NULL, currentFrame, (int) framePitch);
				textureLockTicks += SDL_GetPerformanceCounter() - uploadStart;

			SDL_Rect dest;
			if (!renderer->lockAspectRatio) {
				dest.x = 0;
				dest.y = 0;
				dest.w = renderer->viewportWidth;
				dest.h = renderer->viewportHeight;
			} else if (renderer->lockIntegerScaling) {
				int scaleX = renderer->viewportWidth / renderer->width;
				int scaleY = renderer->viewportHeight / renderer->height;
				int scale = scaleX < scaleY ? scaleX : scaleY;
				if (scale > 0) {
					dest.w = renderer->width * scale;
					dest.h = renderer->height * scale;
				} else {
					dest.w = renderer->viewportHeight * renderer->width / renderer->height;
					dest.h = renderer->viewportHeight;
					if (dest.w > renderer->viewportWidth) {
						dest.w = renderer->viewportWidth;
						dest.h = renderer->viewportWidth * renderer->height / renderer->width;
					}
				}
				dest.x = (renderer->viewportWidth - dest.w) / 2;
				dest.y = (renderer->viewportHeight - dest.h) / 2;
			} else {
				dest.w = renderer->viewportHeight * renderer->width / renderer->height;
				dest.h = renderer->viewportHeight;
				if (dest.w > renderer->viewportWidth) {
					dest.w = renderer->viewportWidth;
					dest.h = renderer->viewportWidth * renderer->height / renderer->width;
				}
				dest.x = (renderer->viewportWidth - dest.w) / 2;
				dest.y = (renderer->viewportHeight - dest.h) / 2;
			}

				stageStart = SDL_GetPerformanceCounter();
				SDL_SetRenderDrawColor(renderer->sdlRenderer, 0, 0, 0, 255);
			SDL_RenderClear(renderer->sdlRenderer);
			SDL_RenderCopy(renderer->sdlRenderer, renderer->sdlTex, 0, &dest);
			if (renderer->interframeBlending && previousTexture && hasPreviousFrame && previousFrame) {
				SDL_UpdateTexture(previousTexture, NULL, previousFrame, (int) framePitch);
				SDL_RenderCopy(renderer->sdlRenderer, previousTexture, NULL, &dest);
			}
			mAndroidOverlayDraw(renderer->sdlRenderer, &dest);
			char textStorage[5][160];
			const char* textLines[5];
			size_t textCount = 0;
			bool showFilename = false, showFps = true, showFrameCounter = false, showOSD = true;
			mCoreConfigGetBoolValue(&renderer->core->config, "showFilename", &showFilename);
			mCoreConfigGetBoolValue(&renderer->core->config, "showFps", &showFps);
			mCoreConfigGetBoolValue(&renderer->core->config, "showFrameCounter", &showFrameCounter);
			mCoreConfigGetBoolValue(&renderer->core->config, "showOSD", &showOSD);
				if (now - lastHudUpdate >= 500) {
					_publishHud(showFilename ? _romFilename : _romTitle, displayedFps, emulatedFps,
						remote ? mAndroidNetSpeed() / 1000.0f : _speedRatio(context), showFps, accelerating, remote, showOSD);
					lastHudUpdate = now;
				}
			if (showFrameCounter) {
				snprintf(textStorage[textCount], sizeof(textStorage[textCount]), "Frame: %d", frameNumber);
				textLines[textCount] = textStorage[textCount];
				++textCount;
			}
			if (showOSD && textCount < 5) {
				char message[160];
				if (mAndroidOverlayGetMessage(message, sizeof(message))) {
					snprintf(textStorage[textCount], sizeof(textStorage[textCount]), "%s", message);
					textLines[textCount] = textStorage[textCount];
					++textCount;
				}
			}
			mAndroidOverlayDrawStatus(renderer->sdlRenderer, textLines, textCount);
				drawTicks += SDL_GetPerformanceCounter() - stageStart;
				stageStart = SDL_GetPerformanceCounter();
			SDL_RenderPresent(renderer->sdlRenderer);
				if (tracePacing) {
					Uint64 stamp = SDL_GetPerformanceCounter();
					if (previousPresent && !paused) SDL_Log("mGBA pacing dt=%.3f present=%.3f emu=%u fast=%d remote=%d",
						(stamp - previousPresent) * 1000.0 / timingFrequency,
						(stamp - stageStart) * 1000.0 / timingFrequency,
						(unsigned) SDL_AtomicGet(&_emulatedFrameCounter), accelerating, remote);
					previousPresent = paused ? 0 : stamp;
				}
				presentTicks += SDL_GetPerformanceCounter() - stageStart;
				Uint8* swapFrame = previousFrame;
				previousFrame = currentFrame;
				currentFrame = swapFrame;
				hasPreviousFrame = true;

				if (++timingFrames >= 30 && timingFrequency) {
					double scale = 1000.0 / (double) timingFrequency / timingFrames;
								if (tracePacing) SDL_Log("mGBA frame timing avg: core-wait %.2f ms, draw %.2f ms, present %.2f ms, upload %.2f ms (fps %.1f, emu %.1f)",
							coreWaitTicks * scale, drawTicks * scale, presentTicks * scale, textureLockTicks * scale, displayedFps, emulatedFps);
					coreWaitTicks = drawTicks = presentTicks = textureLockTicks = 0;
					timingFrames = 0;
				}
			} else if (!remote) mCoreSyncWaitFrameEnd(&context->impl->sync);
		else SDL_Delay(4);
	}
	free(currentFrame);
	free(previousFrame);
	if (previousTexture) SDL_DestroyTexture(previousTexture);
}

static void mSDLDeinit(struct mSDLRenderer* renderer) {
	mSDLDeinitEvents(&renderer->events);
	mSDLDeinitAudio(&renderer->audio);
	SDL_DestroyWindow(renderer->window);
	renderer->deinit(renderer);
}

static struct mCoreOptions _androidDefaultOptions(void) {
	return (struct mCoreOptions) {
		.useBios = true,
		.skipBios = false,
		.rewindEnable = true,
		.rewindBufferCapacity = 600,
		.rewindBufferInterval = 1,
		// AAudio's small callback blocks can trap the core in a low-rate
		// audio-sync feedback loop on some Android devices. A 4096-frame
		// queue keeps audio sync enabled while sustaining full-speed emulation.
		.audioBuffers = 4096,
		.videoSync = true,
		.audioSync = true,
		.sampleRate = 44100,
		.volume = 0x100,
		.logLevel = mLOG_WARN | mLOG_ERROR | mLOG_FATAL,
		.fullscreen = 1,
		.lockAspectRatio = true,
	};
}

int main(int argc, char** argv) {

	struct mArguments args;
	struct mGraphicsOpts graphicsOpts;
	memset(&args, 0, sizeof(args));

	struct mSubParser subparser;

	mSubParserGraphicsInit(&subparser, &graphicsOpts);
	bool parsed = mArgumentsParse(&args, argc, argv, &subparser, 1);
	UNUSED(parsed);
	if (args.showVersion) {
		version(argv[0]);
		mArgumentsDeinit(&args);
		return 0;
	}

	if (!SDL_OK(SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_EVENTS))) {
		printf("Could not initialize SDL: %s\n", SDL_GetError());
		mArgumentsDeinit(&args);
		return 1;
	}

	setenv("XDG_CONFIG_HOME", SDL_AndroidGetInternalStoragePath(), 1);

	if (!args.fname) {
		args.fname = mAndroidPickRom();
		if (!args.fname) {
			SDL_Quit();
			mArgumentsDeinit(&args);
			return 0;
		}
	}

	int ret = 0;
	while (args.fname) {
		struct mSDLRenderer renderer = {0};
		struct mCoreOptions opts = _androidDefaultOptions();
	renderer.core = mCoreFind(args.fname);
	if (!renderer.core) {
		printf("Could not run game. Are you sure the file exists and is a compatible game?\n");
			ret = 1;
			mCoreConfigFreeOpts(&opts);
			break;
	}

	if (!renderer.core->init(renderer.core)) {
			ret = 1;
			mCoreConfigFreeOpts(&opts);
			break;
	}

	renderer.core->baseVideoSize(renderer.core, &renderer.width, &renderer.height);
	renderer.ratio = 0;
	if (renderer.ratio == 0) {
		renderer.ratio = 1;
	}
	opts.width = renderer.width * renderer.ratio;
	opts.height = renderer.height * renderer.ratio;

		if (renderer.core->platform(renderer.core) == mPLATFORM_GB) {
			mInputMapInit(&renderer.core->inputMap, &GBInputInfo);
		} else {
			mInputMapInit(&renderer.core->inputMap, &GBAInputInfo);
		}
	mCoreInitConfig(renderer.core, PORT);
	mArgumentsApply(&args, &subparser, 1, &renderer.core->config);

	mCoreConfigSetDefaultIntValue(&renderer.core->config, "logToStdout", true);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "android.touchControls", _touchControlsVisible);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "android.touchSize", _touchSizePercent);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "android.touchOpacity", _touchOpacityPercent);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "autoload", 1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "autosave", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "cheatAutoload", 1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "cheatAutosave", 1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "saveStateExtdata", SAVESTATE_ALL);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "loadStateExtdata", SAVESTATE_RTC | SAVESTATE_SCREENSHOT);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "fastForwardVolume", -1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "fastForwardMute", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "pauseOnFocusLost", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "pauseOnMinimize", 1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "muteOnFocusLost", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "muteOnMinimize", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "showFrameCounter", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "showFps", 1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "showFilename", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "showOSD", 1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "showResetInfo", 0);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "preload", 1);
		mCoreConfigSetDefaultIntValue(&renderer.core->config, "sgb.borders", 1);
		mCoreConfigSetDefaultFloatValue(&renderer.core->config, "fastForwardRatio", -1.0f);
		mCoreConfigSetDefaultFloatValue(&renderer.core->config, "fastForwardHeldRatio", -1.0f);
	mCoreConfigLoadDefaults(&renderer.core->config, &opts);
	mCoreLoadConfig(renderer.core);
		{
			int touchVisible = 1, touchSize = 100, touchOpacity = 48;
			mCoreConfigGetIntValue(&renderer.core->config, "android.touchControls", &touchVisible);
			mCoreConfigGetIntValue(&renderer.core->config, "android.touchSize", &touchSize);
			mCoreConfigGetIntValue(&renderer.core->config, "android.touchOpacity", &touchOpacity);
			mAndroidOverlaySetOptions(touchVisible, touchSize, touchOpacity);
		}
	mStandardLoggerInit(&_logger);
	mStandardLoggerConfig(&_logger, &renderer.core->config);
	mLogSetDefaultLogger(&_logger.d);

	renderer.viewportWidth = renderer.core->opts.width;
	renderer.viewportHeight = renderer.core->opts.height;
	renderer.player.fullscreen = 1;
	renderer.player.windowUpdated = 0;

	renderer.lockAspectRatio = renderer.core->opts.lockAspectRatio;
	renderer.lockIntegerScaling = renderer.core->opts.lockIntegerScaling;
	renderer.interframeBlending = renderer.core->opts.interframeBlending;
	renderer.filter = renderer.core->opts.resampleVideo;

	mSDLSWCreate(&renderer);
	renderer.runloop = mSDLAndroidRunloop;

	if (!renderer.init(&renderer)) {
		ret = 1;
		mCoreConfigDeinit(&renderer.core->config);
		renderer.core->deinit(renderer.core);
		mCoreConfigFreeOpts(&opts);
		break;
	}
	#if SDL_VERSION_ATLEAST(2, 0, 12)
		SDL_SetTextureScaleMode(renderer.sdlTex, renderer.filter ? SDL_ScaleModeLinear : SDL_ScaleModeNearest);
	#endif
	mAndroidOverlaySetWindow(renderer.window);

	renderer.player.bindings = &renderer.core->inputMap;
		if (renderer.core->platform(renderer.core) == mPLATFORM_GB) {
			_initGBBindings(&renderer.core->inputMap);
		} else {
			mSDLInitBindingsGBA(&renderer.core->inputMap);
		}
	mSDLInitEvents(&renderer.events);
	mSDLEventsLoadConfig(&renderer.events, mCoreConfigGetInput(&renderer.core->config));
	mSDLAttachPlayer(&renderer.events, &renderer.player, -1);
		if (renderer.core->platform(renderer.core) == mPLATFORM_GB) {
			mInputMapLoad(&renderer.core->inputMap, SDL_BINDING_KEY, mCoreConfigGetInput(&renderer.core->config));
			mInputMapLoad(&renderer.core->inputMap, SDL_BINDING_CONTROLLER, mCoreConfigGetInput(&renderer.core->config));
		} else {
			mSDLPlayerLoadConfig(&renderer.player, mCoreConfigGetInput(&renderer.core->config));
		}

#if SDL_VERSION_ATLEAST(2, 0, 0)
	renderer.core->setPeripheral(renderer.core, mPERIPH_RUMBLE, &renderer.player.rumble.d.d);
#endif

		ret = mSDLRun(&renderer, &args);
	mSDLDetachPlayer(&renderer.events, &renderer.player);
		mSDLPlayerSaveConfig(&renderer.player, mCoreConfigGetInput(&renderer.core->config));
		mInputMapSave(&renderer.core->inputMap, SDL_BINDING_KEY, mCoreConfigGetInput(&renderer.core->config));
		mInputMapSave(&renderer.core->inputMap, SDL_BINDING_CONTROLLER, mCoreConfigGetInput(&renderer.core->config));
		_saveConfig(renderer.core);
	mInputMapDeinit(&renderer.core->inputMap);

	mSDLDeinit(&renderer);
	mStandardLoggerDeinit(&_logger);

	mCoreConfigFreeOpts(&opts);
	mCoreConfigDeinit(&renderer.core->config);
	renderer.core->deinit(renderer.core);

		if (!_nextRomPath) {
			break;
		}
		free(args.fname);
		args.fname = _nextRomPath;
		_nextRomPath = NULL;
}

	mArgumentsDeinit(&args);
	SDL_Quit();
	return ret;
}

int mSDLRun(struct mSDLRenderer* renderer, struct mArguments* args) {
	struct mCoreThread thread = {
		.core = renderer->core,
		.resetCallback = _androidResetCallback
	};
	if (!mCoreLoadFile(renderer->core, args->fname)) {
		return 1;
	}
	mAndroidSetGamePath(renderer->core, args->fname);
	const char* basename = strrchr(args->fname, '/');
	snprintf(_romFilename, sizeof(_romFilename), "%s", basename ? basename + 1 : args->fname);
	struct mGameInfo gameInfo = {0};
	if (renderer->core->getGameInfo) renderer->core->getGameInfo(renderer->core, &gameInfo);
	gameInfo.title[sizeof(gameInfo.title) - 1] = '\0';
	snprintf(_romTitle, sizeof(_romTitle), "%s", gameInfo.title[0] ? gameInfo.title : _romFilename);
	SDL_AtomicSet(&_displayFrameCounter, 0);
	mCoreAutoloadSave(renderer->core);
	mCoreAutoloadPatch(renderer->core);
	mCoreAutoloadCheats(renderer->core);
	int autoload = 1;
	if (mCoreConfigGetIntValue(&renderer->core->config, "autoload", &autoload) && autoload) {
		mCoreLoadState(renderer->core, 0, _stateFlags(renderer->core, false));
	}
	mArgumentsApplyFileLoads(args, renderer->core);

	renderer->audio.samples = renderer->core->opts.audioBuffers;
	renderer->audio.sampleRate = renderer->core->opts.sampleRate ? renderer->core->opts.sampleRate : 44100;
	thread.logger.logger = &_logger.d;
	_normalAudioCapacity = mAudioBufferCapacity(renderer->core->getAudioBuffer(renderer->core));
	thread.frameCallback = _androidFrameCallback;
	SDL_AtomicSet(&_emulatedFrameCounter, 0);
		_frameDeadline = 0;
		_cheatButtonFrames = 0;
		char pacingMode[PROP_VALUE_MAX] = {0};
		__system_property_get("debug.mgba.frame_pacer", pacingMode);
		_framePacing = strcmp(pacingMode, "0") != 0;
		/* Keep core pixels in owned CPU memory; a locked SDL texture can be
		 * invalidated or stalled by the graphics driver during presentation. */
		size_t videoBytes = renderer->outputBufferStride * renderer->height * BYTES_PER_PIXEL;
		void* videoBuffer = calloc(1, videoBytes);
		_videoSnapshot = calloc(1, videoBytes);
		_videoSnapshotMutex = SDL_CreateMutex();
		if (!videoBuffer || !_videoSnapshot || !_videoSnapshotMutex) {
			free(videoBuffer); free(_videoSnapshot); _videoSnapshot = NULL;
			if (_videoSnapshotMutex) SDL_DestroyMutex(_videoSnapshotMutex);
			_videoSnapshotMutex = NULL;
			renderer->core->unloadROM(renderer->core); return 1;
		}
		_coreVideoBuffer = videoBuffer;
		_videoSnapshotBytes = videoBytes;
		SDL_UnlockTexture(renderer->sdlTex);
		renderer->outputBuffer = videoBuffer;
		renderer->core->setVideoBuffer(renderer->core, videoBuffer, renderer->outputBufferStride);

	bool didFail = !mCoreThreadStart(&thread);
		mCoreSyncSetVideoSync(&thread.impl->sync, renderer->core->opts.videoSync);

	if (!didFail) {
		mSDLSetScreensaverSuspendable(&renderer->events, renderer->core->opts.suspendScreensaver);
		mSDLSuspendScreensaver(&renderer->events);
		if (mSDLInitAudio(&renderer->audio, &thread)) {
			_activeThread = &thread;
			_activeRenderer = renderer;
			_activeAudio = &renderer->audio;
				_menuPaused = false;
				mAndroidNetInit(&thread, args->fname);
				{
					JNIEnv* env = SDL_AndroidGetJNIEnv();
					jclass menu = env ? (*env)->FindClass(env, "org/libsdl/app/MGBAMenu") : NULL;
					if (menu) {
						jmethodID ready = (*env)->GetStaticMethodID(env, menu, "onGameReady", "()V");
						if (ready) (*env)->CallStaticVoidMethod(env, menu, ready);
						(*env)->DeleteLocalRef(env, menu);
					}
					if (env && (*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
				}
			_fastForward = false;
				_fastForwardHeld = false;
			_rewinding = false;
			renderer->runloop(renderer, &thread);
				mAndroidNetShutdown();
			mSDLPauseAudio(&renderer->audio);
			_activeThread = NULL;
			_activeRenderer = NULL;
			_activeAudio = NULL;
			if (mCoreThreadHasCrashed(&thread)) {
				didFail = true;
				printf("The game crashed!\n");
				mCoreThreadEnd(&thread);
			}
		} else {
			didFail = true;
			printf("Could not initialize audio.\n");
		}
		mSDLResumeScreensaver(&renderer->events);
		mSDLSetScreensaverSuspendable(&renderer->events, false);

		mCoreThreadJoin(&thread);
			int autosave = 0;
			if (mCoreConfigGetIntValue(&renderer->core->config, "autosave", &autosave) && autosave) {
				mCoreSaveState(renderer->core, 0, _stateFlags(renderer->core, true));
			}
	} else {
		printf("Could not run game. Are you sure the file exists and is a compatible game?\n");
	}
	renderer->core->unloadROM(renderer->core);
		free(videoBuffer);
		free(_videoSnapshot); _videoSnapshot = NULL; _coreVideoBuffer = NULL;
		SDL_DestroyMutex(_videoSnapshotMutex); _videoSnapshotMutex = NULL;
		renderer->outputBuffer = NULL;

	return didFail;
}
