# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# Lua's Android fseeko guard comes from patches/cyime-lua-android.patch (Gradle applyCyimeSourcePatches).
include("${CMAKE_CURRENT_LIST_DIR}/CyimePatches.cmake")
# Nine-key whole-word grammar scoring and host replay counters; the plugin copy below picks it up.
cyime_apply_patch("${CMAKE_SOURCE_DIR}/librime-octagram" "${CMAKE_CURRENT_LIST_DIR}/OctagramT9.patch")

set(RIME_PLUGINS librime-octagram librime-predict librime-t9)

foreach(plugin ${RIME_PLUGINS})
  file(COPY "${CMAKE_SOURCE_DIR}/${plugin}/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
endforeach()

file(COPY "${CMAKE_SOURCE_DIR}/librime-lua/"
     DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua")

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
  file(COPY "${CMAKE_SOURCE_DIR}/librime-lua-deps/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
option(CYIME_RIME_VERBOSE_TRACE "Trace every native dictionary lookup (slow)" OFF)
if(CYIME_RIME_VERBOSE_TRACE)
  add_compile_definitions(CYIME_RIME_VERBOSE_TRACE=1)
endif()
include("${CMAKE_CURRENT_LIST_DIR}/T9WorkCounters.cmake")
# The base patch is also applied by Gradle. Keep the new Context patch separate
# so checkouts with the older base patch already applied can upgrade in place.
cyime_apply_patch("${CMAKE_SOURCE_DIR}/librime" "${CMAKE_CURRENT_LIST_DIR}/RimeDictionary.patch")
cyime_apply_patch("${CMAKE_SOURCE_DIR}/librime" "${CMAKE_CURRENT_LIST_DIR}/RimeTableValidation.patch")
cyime_apply_patch("${CMAKE_SOURCE_DIR}/librime" "${CMAKE_CURRENT_LIST_DIR}/RimeTableBuildRelocation.patch")
# Upgrade a checkout with the previous Context patch before checking the full patch.
set(_context_upgrade "${CMAKE_CURRENT_LIST_DIR}/RimeContextEditUpgrade.patch")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${_context_upgrade}")
execute_process(COMMAND "${GIT_EXECUTABLE}" apply --check "${_context_upgrade}"
    WORKING_DIRECTORY "${CMAKE_SOURCE_DIR}/librime" RESULT_VARIABLE _context_old
    OUTPUT_QUIET ERROR_QUIET)
if(_context_old EQUAL 0)
  cyime_apply_patch("${CMAKE_SOURCE_DIR}/librime" "${_context_upgrade}")
endif()
cyime_apply_patch("${CMAKE_SOURCE_DIR}/librime" "${CMAKE_CURRENT_LIST_DIR}/RimeContextEdit.patch")
add_subdirectory(librime)
include("${CMAKE_CURRENT_LIST_DIR}/CyimeRimeSources.cmake")
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
