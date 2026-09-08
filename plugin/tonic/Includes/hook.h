#pragma once
#include <3ds.h>
void hookInstall(void);
u32  hookStatus(char *out, u32 max);
u32  hookDrain(char *out, u32 max);
void hookSetAppData(const u8 *d, u32 n);
u32 udsAnswer(u32 *saved, u32 site);
void logLine(const char *text);
