/* SPDX-License-Identifier: MPL-2.0 */
#ifndef MGBA_ANDROID_SAVE_H
#define MGBA_ANDROID_SAVE_H
#include <mgba/core/core.h>
struct VFile;
void mAndroidSetGamePath(struct mCore*, const char* path);
const char* mAndroidGamePath(void);
bool mAndroidGameFolder(struct mCore*, char* output, size_t capacity);
bool mAndroidGameDirectory(struct mCore*, const char* folder, const char* legacy, char* output, size_t capacity);
bool mAndroidBackupState(struct mCore*, const char* tag, char* path, size_t capacity);
bool mAndroidExportSave(struct mCore*, bool battery, char* path, size_t capacity);
bool mAndroidWriteSnapshot(struct mCore*, struct VFile*, int flags);
bool mAndroidReadSnapshot(struct mCore*, struct VFile*, int flags, char* error, size_t capacity);
bool mAndroidImportSave(struct mCore*, const char* path, bool state, char* error, size_t capacity);
#endif
