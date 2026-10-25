#!/usr/bin/env python3
"""Restore PlaybackSession seek-listener hooks required by SeekPreviewStore.

Run this file from the mpvRx 2.7.2 repository root. Idempotent; backs up
PlaybackSession.kt before applying. It does not replace entire source files.
"""
from pathlib import Path
import re
import shutil
import sys
from datetime import datetime

source = Path('app/src/main/java/app/gyrolet/mpvrx/ui/player/PlaybackSession.kt')
store = Path('app/src/main/java/app/gyrolet/mpvrx/domain/seekpreview/SeekPreviewStore.kt')
if not source.is_file() or not store.is_file():
    sys.exit('ERROR: Run this from the root of the mpvRx Android source directory (app/src/... expected).')

original = source.read_text(encoding='utf-8')
store_text = store.read_text(encoding='utf-8')
if 'PlaybackSession.addPlaybackSeekListener(' not in store_text:
    print('NOTE: The current SeekPreviewStore does not require this listener API. No changes made.')
    sys.exit(0)

required = ['addPlaybackSeekListener(', 'removePlaybackSeekListener(', 'signalPlaybackSeek()']
if all(token in original for token in required):
    print('PlaybackSession already contains seek listeners and notifications. Nothing to change.')
    sys.exit(0)

anchor = '  val lastPlaybackSeekAtElapsedMs: Long get() = playbackSeekSignal.get()'
if original.count(anchor) != 1:
    sys.exit('ERROR: Expected playback seek timestamp anchor is missing or ambiguous. File unchanged.')

# Detect full or half-applied integration rather than silently introducing duplicates.
if any(token in original for token in ('addPlaybackSeekListener(', 'removePlaybackSeekListener(', 'private fun signalPlaybackSeek(')):
    sys.exit('ERROR: PlaybackSession has a partial/different seek-listener implementation. File unchanged.')

raw_signal = 'playbackSeekSignal.set(android.os.SystemClock.elapsedRealtime())'
if original.count(raw_signal) != 3:
    sys.exit(f'ERROR: Expected 3 native seek timestamp sites, found {original.count(raw_signal)}. File unchanged.')

api = '''\n  // Notify thumbnail extraction immediately before a real mpv playback seek.\n  // Listeners must not acquire the playback-core lock or perform network I/O here.\n  private val playbackSeekListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()\n\n  internal fun addPlaybackSeekListener(listener: () -> Unit) { playbackSeekListeners += listener }\n  internal fun removePlaybackSeekListener(listener: () -> Unit) { playbackSeekListeners -= listener }\n\n  private fun signalPlaybackSeek() {\n    playbackSeekSignal.set(android.os.SystemClock.elapsedRealtime())\n    playbackSeekListeners.forEach { listener -> runCatching { listener() } }\n  }\n'''

updated = original.replace(anchor, anchor + '\n' + api, 1)
# Only replace existing seek notification sites; injected signalPlaybackSeek's timestamp
# must NOT be rewritten or it would recurse indefinitely.
parts = updated.split(api)
if len(parts) != 2:
    sys.exit('ERROR: Unable to locate inserted listener API safely. File unchanged.')
# Since the injected API occurs before all three seek sites, replace only after it.
parts[1] = parts[1].replace(raw_signal, 'signalPlaybackSeek()')
updated = api.join(parts)

if updated.count('signalPlaybackSeek()') != 3:
    # Three calls + the declaration which uses `signalPlaybackSeek() {`.
    if updated.count('signalPlaybackSeek()') != 4:
        sys.exit('ERROR: Unexpected seek notification call count. File unchanged.')
if original.count(raw_signal) != 3 or updated.count(raw_signal) != 1:
    sys.exit('ERROR: Unexpected remaining direct seek timestamp assignments. File unchanged.')

stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
backup = source.with_name(f'PlaybackSession.kt.before-seek-listener-{stamp}.bak')
shutil.copy2(source, backup)
source.write_text(updated, encoding='utf-8')
print('Applied seek-listener compatibility fix to:', source)
print('Backup:', backup)
print('Verified: both listener functions and 3 playback-seek notifications are present.')
