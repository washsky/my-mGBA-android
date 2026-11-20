/* Copyright (c) 2026 Gitsnup
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
#include "android-ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jni.h>
#include "font8x8_basic.h"
#include <SDL_system.h>

// --- Font blitting -----------------------------------------------------------

#define TEXT_POINT_CAPACITY (160 * 8 * 8 * 4)
static SDL_Point _textPoints[TEXT_POINT_CAPACITY];

static size_t _appendTextPoints(const char* text, int x, int y, int scale, size_t count) {
	const char* p;
	for (p = text; *p; ++p, x += 8 * scale) {
		const char* glyph = font8x8_basic[(unsigned char) *p & 0x7F];
		int row, col, sx, sy;
		for (row = 0; row < 8; ++row) {
			for (col = 0; col < 8; ++col) {
				if (!((glyph[row] >> col) & 1)) {
					continue;
				}
				for (sy = 0; sy < scale; ++sy) {
					for (sx = 0; sx < scale; ++sx) {
						if (count >= TEXT_POINT_CAPACITY) {
							return count;
						}
						_textPoints[count].x = x + col * scale + sx;
						_textPoints[count].y = y + row * scale + sy;
						++count;
					}
				}
			}
		}
	}
	return count;
}

static void _drawText(SDL_Renderer* renderer, const char* text, int x, int y, int scale) {
	size_t count = _appendTextPoints(text, x, y, scale, 0);
	if (count) {
		SDL_RenderDrawPoints(renderer, _textPoints, (int) count);
	}
}

// --- ROM picker --------------------------------------------------------------

static bool _showLauncherMenu(void) {
	JNIEnv* env = SDL_AndroidGetJNIEnv();
	if (!env) {
		return false;
	}
	jclass menuClass = (*env)->FindClass(env, "org/libsdl/app/MGBAMenu");
	if (!menuClass) {
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
		}
		return false;
	}
	jmethodID showMenu = (*env)->GetStaticMethodID(env, menuClass, "showLauncherMenuFromNative", "()V");
	if (!showMenu) {
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
		}
		(*env)->DeleteLocalRef(env, menuClass);
		return false;
	}
	(*env)->CallStaticVoidMethod(env, menuClass, showMenu);
	bool success = !(*env)->ExceptionCheck(env);
	if (!success) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	(*env)->DeleteLocalRef(env, menuClass);
	return success;
}

char* mAndroidPickRom(void) {
	if (!_showLauncherMenu()) {
		return NULL;
	}
	for (;;) {
		SDL_Event event;
		if (!SDL_WaitEventTimeout(&event, 250)) {
			continue;
		}
		if (event.type == SDL_QUIT) {
			return NULL;
		}
		if (event.type == SDL_USEREVENT && event.user.code == ANDROID_ROM_PICKED_EVENT && event.user.data1) {
			char* result = strdup((const char*) event.user.data1);
			free(event.user.data1);
			return result;
		}
	}
}

// --- Touch overlay -----------------------------------------------------------

#define MAX_TOUCHES 8
#define TOGGLE_SIZE 40

struct OverlayButton {
	SDL_Rect rect;
	SDL_Keycode key;
	const char* label;
};

static struct {
	bool hidden;
	int sizePercent;
	int opacityPercent;
	bool fast, linked;
	char speedLabel[16];
	SDL_Window* window;
	SDL_Rect view;
	bool hasView;
	struct {
		Sint64 id;
		SDL_Keycode key;
		bool active;
	} touches[MAX_TOUCHES];
} overlay;

static SDL_mutex* osdMutex;
static char osdMessage[160];
static Uint32 osdMessageUntil;

void mAndroidOverlaySetMessage(const char* message) {
	if (!osdMutex) {
		osdMutex = SDL_CreateMutex();
	}
	if (osdMutex) SDL_LockMutex(osdMutex);
	snprintf(osdMessage, sizeof(osdMessage), "%s", message ? message : "");
	osdMessageUntil = SDL_GetTicks() + 2500;
	if (osdMutex) SDL_UnlockMutex(osdMutex);
}

bool mAndroidOverlayGetMessage(char* output, size_t outputSize) {
	if (!output || outputSize == 0) return false;
	output[0] = '\0';
	if (!osdMutex) return false;
	SDL_LockMutex(osdMutex);
	if (osdMessage[0] && !SDL_TICKS_PASSED(SDL_GetTicks(), osdMessageUntil)) {
		snprintf(output, outputSize, "%s", osdMessage);
	} else {
		osdMessage[0] = '\0';
	}
	SDL_UnlockMutex(osdMutex);
	return output[0] != '\0';
}

void mAndroidOverlayDrawStatus(SDL_Renderer* renderer, const char* const* lines, size_t lineCount) {
	if (!renderer || !lines || !lineCount) return;
	if (lineCount > 6) lineCount = 6;
	int maxChars = 0;
	size_t i;
	for (i = 0; i < lineCount; ++i) {
		int length = lines[i] ? (int) strlen(lines[i]) : 0;
		if (length > maxChars) maxChars = length;
	}
	if (!maxChars) return;
	const int scale = 2;
	int width = maxChars * 8 * scale + 16;
	int height = (int) lineCount * (8 * scale + 6) + 12;
	if (width > 900) width = 900;
	int rendererWidth, rendererHeight;
	SDL_GetRendererOutputSize(renderer, &rendererWidth, &rendererHeight);
	if (width > rendererWidth - 16) width = rendererWidth - 16;
	if (height > rendererHeight - 16) height = rendererHeight - 16;
	if (width <= 0 || height <= 0) return;
	SDL_Rect background = { 8, 68, width, height };
	SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
	SDL_SetRenderDrawColor(renderer, 0, 0, 0, 160);
	SDL_RenderFillRect(renderer, &background);
	SDL_RenderSetClipRect(renderer, &background);
	SDL_SetRenderDrawColor(renderer, 0xFF, 0xFF, 0xFF, 0xF0);
	for (i = 0; i < lineCount; ++i) {
		if (lines[i]) _drawText(renderer, lines[i], 16, 74 + (int) i * (8 * scale + 6), scale);
	}
	SDL_RenderSetClipRect(renderer, NULL);
}

static void _pushKey(SDL_Keycode key, bool down) {
	SDL_Event event;
	SDL_memset(&event, 0, sizeof(event));
	event.type = down ? SDL_KEYDOWN : SDL_KEYUP;
	event.key.keysym.sym = key;
	event.key.keysym.scancode = SDL_GetScancodeFromKey(key);
	event.key.state = down ? SDL_PRESSED : SDL_RELEASED;
	event.key.repeat = 0;
	SDL_PushEvent(&event);
}

static int _layout(const SDL_Rect* view, struct OverlayButton* out) {
	int w, h;
	if (overlay.window) {
		SDL_GetWindowSize(overlay.window, &w, &h);
	} else {
		return 0;
	}
	bool landscape = w >= h;
	int shortSide = landscape ? h : w;
	int cell = shortSide / 9;
	if (overlay.sizePercent > 0) {
		cell = cell * overlay.sizePercent / 100;
	}
	if (cell < shortSide / 18) cell = shortSide / 18;
	int maxCell = (shortSide - 16) / 5;
	if (maxCell > w / 8 - 2) maxCell = w / 8 - 2;
	if (cell > maxCell) cell = maxCell;
	if (cell < 32) cell = 32;
	cell &= ~1; // Keep adjacent D-pad cells from overlapping by one pixel.
	int n = 0;

	int dpx = landscape ? cell * 2 : w / 4;
	int dpy = h - cell * 5 / 2;
	// D-pad cross (up, down, left, right)
	out[n].rect.x = dpx - cell / 2;
	out[n].rect.y = dpy - cell - cell / 2;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_UP;
	out[n].label = "^";
	++n;
	out[n].rect.x = dpx - cell / 2;
	out[n].rect.y = dpy + cell / 2;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_DOWN;
	out[n].label = "v";
	++n;
	out[n].rect.x = dpx - cell - cell / 2;
	out[n].rect.y = dpy - cell / 2;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_LEFT;
	out[n].label = "<";
	++n;
	out[n].rect.x = dpx + cell / 2;
	out[n].rect.y = dpy - cell / 2;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_RIGHT;
	out[n].label = ">";
	++n;

	// A / B
	int abx = landscape ? w - cell * 2 : w * 3 / 4;
	int aby = dpy;
	out[n].rect.x = abx + cell / 2;
	out[n].rect.y = aby - cell - cell / 2;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_x;
	out[n].label = "A";
	++n;
	out[n].rect.x = abx - cell - cell / 2;
	out[n].rect.y = aby + cell / 2;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_z;
	out[n].label = "B";
	++n;

	// L / R
	out[n].rect.x = cell / 2;
	out[n].rect.y = 8;
	out[n].rect.w = cell * 2;
	out[n].rect.h = cell;
	out[n].key = SDLK_a;
	out[n].label = "L";
	++n;
	out[n].rect.x = w - cell * 2 - cell / 2;
	out[n].rect.y = 8;
	out[n].rect.w = cell * 2;
	out[n].rect.h = cell;
	out[n].key = SDLK_s;
	out[n].label = "R";
	++n;

	// Start / Select
	int sy = dpy + cell * 2;
	if (sy + cell > h) {
		sy = h - cell;
	}
	out[n].rect.x = w / 2 - cell * 2;
	out[n].rect.y = sy;
	out[n].rect.w = cell * 2;
	out[n].rect.h = cell * 3 / 4;
	out[n].key = SDLK_RETURN;
	out[n].label = "START";
	++n;
	out[n].rect.x = w / 2 + cell * 0.5f;
	out[n].rect.y = sy;
	out[n].rect.w = cell * 2;
	out[n].rect.h = cell * 3 / 4;
	out[n].key = SDLK_BACKSPACE;
	out[n].label = "SELECT";
	++n;

	// Tap to toggle fast-forward. Keyboard Tab remains hold-to-fast-forward.
	int speedY = dpy - cell * 3 / 2;
	out[n].rect.x = w / 2 - cell * 3 / 2;
	out[n].rect.y = speedY;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_F9;
	out[n].label = overlay.fast ? overlay.speedLabel : "FF";
	++n;
	out[n].rect.x = w / 2 + cell / 2;
	out[n].rect.y = speedY;
	out[n].rect.w = cell;
	out[n].rect.h = cell;
	out[n].key = SDLK_BACKQUOTE;
	out[n].label = "RW";
	++n;

	return n;
}

void mAndroidOverlaySetSpeed(bool active, float ratio, bool linked) {
	overlay.fast = active; overlay.linked = linked;
	if (ratio > 0) snprintf(overlay.speedLabel, sizeof(overlay.speedLabel), "%.0fx", ratio);
	else snprintf(overlay.speedLabel, sizeof(overlay.speedLabel), "MAX");
}

void mAndroidOverlayHudInsets(int* right, int* bottom) {
	*right = *bottom = 0;
	if (overlay.hidden || !overlay.window) return;
	struct OverlayButton buttons[12];
	if (_layout(NULL, buttons) < 7) return;
	*right = buttons[6].rect.x + buttons[6].rect.w;
	*bottom = buttons[6].rect.y + buttons[6].rect.h;
}

static const SDL_Rect* _toggleRect(void) {
	static SDL_Rect rect;
	int w, h;
	if (overlay.window) {
		SDL_GetWindowSize(overlay.window, &w, &h);
	} else {
		rect.x = rect.y = rect.w = rect.h = 0;
		return &rect;
	}
	rect.x = w - TOGGLE_SIZE - 8;
	rect.y = 8;
	rect.w = TOGGLE_SIZE;
	rect.h = TOGGLE_SIZE;
	return &rect;
}

void mAndroidOverlaySetWindow(SDL_Window* window) {
	overlay.window = window;
	overlay.hasView = false;
	if (!osdMutex) osdMutex = SDL_CreateMutex();
	if (overlay.sizePercent <= 0) {
		overlay.sizePercent = 100;
	}
	if (overlay.opacityPercent <= 0) {
		overlay.opacityPercent = 48;
	}
	mAndroidOverlayReset();
}

void mAndroidOverlayReset(void) {
	int i;
	for (i = 0; i < MAX_TOUCHES; ++i) {
		overlay.touches[i].id = -1;
		overlay.touches[i].active = false;
	}
}

void mAndroidOverlaySetOptions(int visible, int sizePercent, int opacityPercent) {
	bool hide = !visible;
	if (hide != overlay.hidden) {
		int i;
		for (i = 0; i < MAX_TOUCHES; ++i) {
			if (overlay.touches[i].active) {
				_pushKey(overlay.touches[i].key, false);
			}
		}
		mAndroidOverlayReset();
	}
	overlay.hidden = hide;
	if (sizePercent < 60) sizePercent = 60;
	if (sizePercent > 200) sizePercent = 200;
	if (opacityPercent < 10) opacityPercent = 10;
	if (opacityPercent > 100) opacityPercent = 100;
	overlay.sizePercent = sizePercent;
	overlay.opacityPercent = opacityPercent;
}

void mAndroidOverlayHandleEvent(const SDL_Event* event) {
	if (event->type != SDL_FINGERDOWN && event->type != SDL_FINGERUP && event->type != SDL_FINGERMOTION) {
		return;
	}
	int w, h;
	if (overlay.window) {
		SDL_GetWindowSize(overlay.window, &w, &h);
	} else {
		return;
	}
	Sint64 id = event->tfinger.fingerId;
	int x = (int) (event->tfinger.x * w);
	int y = (int) (event->tfinger.y * h);

	if (event->type == SDL_FINGERDOWN) {
		const SDL_Rect* toggle = _toggleRect();
		if (x >= toggle->x && x < toggle->x + toggle->w && y >= toggle->y && y < toggle->y + toggle->h) {
			overlay.hidden = !overlay.hidden;
			return;
		}
		if (overlay.hidden) {
			return;
		}
		struct OverlayButton buttons[12];
		int n = _layout(overlay.hasView ? &overlay.view : NULL, buttons);
		int i;
		for (i = 0; i < n; ++i) {
			if (x >= buttons[i].rect.x && x < buttons[i].rect.x + buttons[i].rect.w &&
			    y >= buttons[i].rect.y && y < buttons[i].rect.y + buttons[i].rect.h) {
				int slot;
				for (slot = 0; slot < MAX_TOUCHES; ++slot) {
					if (!overlay.touches[slot].active) {
						break;
					}
				}
				if (slot < MAX_TOUCHES) {
					overlay.touches[slot].id = id;
					overlay.touches[slot].key = buttons[i].key;
					overlay.touches[slot].active = true;
					_pushKey(buttons[i].key, true);
				}
				return;
			}
		}
		return;
	}

	// FINGERUP / FINGERMOTION: release or slide between buttons
	struct OverlayButton buttons[12];
	int n = overlay.hidden ? 0 : _layout(overlay.hasView ? &overlay.view : NULL, buttons);
	int i;
	for (i = 0; i < MAX_TOUCHES; ++i) {
		if (!overlay.touches[i].active || overlay.touches[i].id != id) {
			continue;
		}
		const struct OverlayButton* target = NULL;
		int j;
		for (j = 0; j < n; ++j) {
			if (x >= buttons[j].rect.x && x < buttons[j].rect.x + buttons[j].rect.w &&
			    y >= buttons[j].rect.y && y < buttons[j].rect.y + buttons[j].rect.h) {
				target = &buttons[j];
				break;
			}
		}
		if (event->type == SDL_FINGERUP) {
			_pushKey(overlay.touches[i].key, false);
			overlay.touches[i].id = -1;
			overlay.touches[i].active = false;
		} else if (!target || target->key != overlay.touches[i].key) {
			_pushKey(overlay.touches[i].key, false);
			overlay.touches[i].id = -1;
			overlay.touches[i].active = false;
			if (target) {
				overlay.touches[i].id = id;
				overlay.touches[i].key = target->key;
				overlay.touches[i].active = true;
				_pushKey(target->key, true);
			}
		}
	}
}

void mAndroidOverlayDraw(SDL_Renderer* renderer, const SDL_Rect* view) {
	if (!overlay.window) {
		return;
	}
	if (view) {
		overlay.view = *view;
		overlay.hasView = true;
	}
	const SDL_Rect* toggle = _toggleRect();
	SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
	SDL_SetRenderDrawColor(renderer, 200, 200, 200, (Uint8) (overlay.opacityPercent * 0.5f));
	SDL_RenderFillRect(renderer, toggle);

	if (overlay.hidden) {
		return;
	}
	struct OverlayButton buttons[12];
	int n = _layout(view, buttons);
	int i;
	for (i = 0; i < n; ++i) {
		bool pressed = buttons[i].key == SDLK_F9 && overlay.fast;
		int j; for (j = 0; j < MAX_TOUCHES; ++j)
			if (overlay.touches[j].active && overlay.touches[j].key == buttons[i].key) pressed = true;
		bool disabled = buttons[i].key == SDLK_BACKQUOTE && overlay.linked;
		SDL_SetRenderDrawColor(renderer, pressed ? 70 : 235, pressed ? 200 : 235, pressed ? 235 : 235,
			(Uint8) (overlay.opacityPercent * (disabled ? 0.7f : 2.0f)));
		SDL_RenderFillRect(renderer, &buttons[i].rect);
		SDL_SetRenderDrawColor(renderer, 255, 255, 255, (Uint8) (overlay.opacityPercent * 2.5f));
		SDL_RenderDrawRect(renderer, &buttons[i].rect);
	}

	// Draw labels directly. A full-window surface and texture here would
	// allocate several megabytes on every frame, even though the labels are tiny.
	SDL_SetRenderDrawColor(renderer, 0xFF, 0xFF, 0xFF, 0xC0);
	size_t pointCount = 0;
	for (i = 0; i < n; ++i) {
		int scale = buttons[i].rect.h / 28;
		if (scale < 2) scale = 2; if (scale > 6) scale = 6;
		int tx = buttons[i].rect.x + (buttons[i].rect.w - (int) strlen(buttons[i].label) * 8 * scale) / 2;
		int ty = buttons[i].rect.y + (buttons[i].rect.h - 8 * scale) / 2;
		pointCount = _appendTextPoints(buttons[i].label, tx, ty, scale, pointCount);
	}
	if (pointCount) {
		SDL_RenderDrawPoints(renderer, _textPoints, (int) pointCount);
	}
}
