/* SPDX-License-Identifier: MPL-2.0 */
#ifndef MGBA_ANDROID_CHEATS_H
#define MGBA_ANDROID_CHEATS_H
#include <mgba/core/core.h>
char* mAndroidCheatList(struct mCore*);
bool mAndroidCheatChange(struct mCore*, int action, int index, int type, bool enabled,
    const char* name, const char* text, char* error, size_t capacity);
bool mAndroidCheatExport(struct mCore*, char* output, size_t capacity);
#endif
