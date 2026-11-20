/* SPDX-License-Identifier: MPL-2.0 */
#ifndef MGBA_ANDROID_NET_H
#define MGBA_ANDROID_NET_H
#include <mgba/core/thread.h>
#include <mgba-util/image.h>
void mAndroidNetInit(struct mCoreThread*, const char* rom);
bool mAndroidNetStart(bool host, const char* address, const char* code);
void mAndroidNetStop(void);
void mAndroidNetShutdown(void);
bool mAndroidNetBusy(void);
bool mAndroidNetClient(void);
bool mAndroidNetHosting(void);
int mAndroidNetSpeed(void);
void mAndroidNetSetSpeed(int milli);
void mAndroidNetRequestSpeed(int milli);
int mAndroidNetTakeSpeedRequest(void);
unsigned mAndroidNetFrameNumber(void);
bool mAndroidNetTakeResume(void);
bool mAndroidNetCopyFrame(mColor* output, size_t stride);
void mAndroidNetKeys(uint32_t keys);
void mAndroidNetStatus(char* output, size_t capacity);
#endif
