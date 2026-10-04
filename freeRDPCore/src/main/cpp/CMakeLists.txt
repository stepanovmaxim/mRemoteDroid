# FreeRDP: A Remote Desktop Protocol Implementation
# Android Client
#
# Copyright 2012 Marc-Andre Moreau <marcandre.moreau@gmail.com>
# Copyright 2013 Bernhard Miklautz <bernhard.miklautz@thincast.com>
# Copyright 2022 Ely Ronnen <elyronnen@gmail.com>
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 3.13)

if(NOT FREERDP_DEFAULT_PROJECT_VERSION)
  set(FREERDP_DEFAULT_PROJECT_VERSION "1.0.0.0")
endif()

project("freerdp-android" LANGUAGES C VERSION ${FREERDP_DEFAULT_PROJECT_VERSION})

message("project ${PROJECT_NAME} is using version ${PROJECT_VERSION}")

set(MODULE_NAME "freerdp-android")
set(MODULE_PREFIX "FREERDP_CLIENT_ANDROID")

set(FREERDP_IMPORT_DIR_RELATIVE ../jniLibs/${CMAKE_ANDROID_ARCH_ABI})
get_filename_component(FREERDP_IMPORT_DIR ${FREERDP_IMPORT_DIR_RELATIVE} ABSOLUTE)

include_directories(
  SYSTEM ${FREERDP_IMPORT_DIR}/include/freerdp3 ${FREERDP_IMPORT_DIR}/include/winpr3
  ${FREERDP_IMPORT_DIR}/include/openssl
)

set(${MODULE_PREFIX}_SRCS
    android_event.c
    android_event.h
    android_freerdp.c
    android_freerdp.h
    android_jni_utils.c
    android_jni_utils.h
    android_jni_callback.c
    android_jni_callback.h
)

if(WITH_CLIENT_CHANNELS)
  set(${MODULE_PREFIX}_SRCS ${${MODULE_PREFIX}_SRCS} android_cliprdr.c android_cliprdr.h)
endif()

add_library(${MODULE_NAME} SHARED ${${MODULE_PREFIX}_SRCS})

find_library(log-lib log)
find_library(dl-lib dl)
find_library(jnigraphics-lib jnigraphics)

# Link against the prebuilt FreeRDP core libs via linker flags (-L + -l:exact).
# Using IMPORTED targets or full .so paths makes CMake symlink the prebuilt .so
# into the build obj dir, which on Windows yields "not a regular file" when
# Gradle snapshots the task output. The -l: form resolves symbols and records
# each SONAME as a NEEDED dependency without creating any symlink; the actual
# .so are packaged from src/main/jniLibs. --allow-shlib-undefined tolerates the
# transitive symbols the core libs resolve among themselves at runtime.
target_link_libraries(
  ${MODULE_NAME}
  "-L${FREERDP_IMPORT_DIR}"
  -lfreerdp-client3
  -lfreerdp3
  -lwinpr3
  -lssl
  -lcrypto
  -lcjson
  -Wl,--allow-shlib-undefined
  ${log-lib}
  ${dl-lib}
  ${jnigraphics-lib}
)
