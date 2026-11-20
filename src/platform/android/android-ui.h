/* Copyright (c) 2026 Gitsnup
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
#ifndef ANDROID_UI_H
#define ANDROID_UI_H

#include <SDL.h>
#include <stdbool.h>
#include <stddef.h>

#include <mgba-util/common.h>

CXX_GUARD_START

#define ANDROID_ROM_PICKED_EVENT 0x4D474241

// Opens the Android-native Chinese ROM picker and returns a malloc'd path,
// or NULL if the user quits.
char* mAndroidPickRom(void);

// Touch overlay during gameplay. Set the window right after creating it, call
// handleEvent for every polled event (it synthesizes keyboard events for
// touches), and draw after the frame blit. `view` is the letterboxed game rect.
void mAndroidOverlaySetWindow(SDL_Window* window);
void mAndroidOverlayReset(void);
void mAndroidOverlaySetOptions(int visible, int sizePercent, int opacityPercent);
void mAndroidOverlaySetSpeed(bool active, float ratio, bool linked);
void mAndroidOverlayHudInsets(int* right, int* bottom);
void mAndroidOverlaySetMessage(const char* message);
bool mAndroidOverlayGetMessage(char* output, size_t outputSize);
void mAndroidOverlayDrawStatus(SDL_Renderer* renderer, const char* const* lines, size_t lineCount);
void mAndroidOverlayHandleEvent(const SDL_Event* event);
void mAndroidOverlayDraw(SDL_Renderer* renderer, const SDL_Rect* view);

CXX_GUARD_END

#endif
