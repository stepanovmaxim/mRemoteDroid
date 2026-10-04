#ifndef WINPR_BUILD_FLAGS_H
#define WINPR_BUILD_FLAGS_H

#define WINPR_CFLAGS "-DANDROID -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -D_FORTIFY_SOURCE=2 -Wformat -Werror=format-security  -fno-omit-frame-pointer -fno-limit-debug-info  -g"
#define WINPR_COMPILER_ID "Clang"
#define WINPR_COMPILER_VERSION "18.0.3"
#define WINPR_TARGET_ARCH "x64"
#define WINPR_BUILD_CONFIG ""
#define WINPR_BUILD_TYPE "Debug"

#endif /* WINPR_BUILD_FLAGS_H */
