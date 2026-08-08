# Topic
Fixing usage data loading and display accuracy on low-end devices

# Summary
On low-end Android devices such as the OPPO A12 running Android 9, usage data loading can become slow because the app does too much work on the main thread and repeatedly resolves package details. The fix is to move usage loading off the UI thread, cache package metadata, reduce repeated refresh work, and only render the UI after the first lightweight pass is ready.

# Problem
The child-side usage screen can feel very slow on low-end devices because it likely:

- queries too many installed apps at once
- resolves app labels and icons repeatedly
- performs usage aggregation on the main thread
- refreshes the full dataset too often
- triggers extra binder work and garbage collection

This causes frame drops, delayed screen loading, and long waits before the usage list becomes visible.

# Solution
Use an implementation that is accurate, responsive, and realistic for low-end hardware.

## 1. Load usage data in a background worker
Do not query `UsageStatsManager`, installed apps, or database records on the main thread.

Implementation:
- use a single background `ExecutorService` or a dedicated worker thread
- load the raw usage list in the background
- post only the final sorted list back to the UI thread
- show a loading indicator immediately while the background task runs

## 2. Cache package metadata
Resolving app labels and icons for every refresh is expensive.

Implementation:
- keep an in-memory cache keyed by package name
- store app label, icon drawable/resource, and launch intent existence
- refresh cache only when package changes are detected
- avoid re-querying `PackageManager` for every screen open

## 3. Split loading into two phases
First load a lightweight summary, then load detailed rows.

Implementation:
- phase 1: load total usage, top apps, and counts
- phase 2: load detailed app rows and icons asynchronously
- render the summary as soon as phase 1 completes
- append the detailed list when phase 2 finishes

## 4. Reduce full refresh frequency
Do not rebuild the entire usage model every time the screen is opened or resumed.

Implementation:
- debounce refresh requests
- refresh only when the session changes, day changes, or package list changes
- reuse the last loaded dataset if it is still valid
- schedule periodic refreshes with a sensible interval such as 30 to 60 seconds for the visible screen

## 5. Keep the usage query narrow and ordered
Query only the time window you need and sort before display.

Implementation:
- query the exact range for the current day or selected interval
- exclude obvious non-user/system noise only where appropriate
- sort by usage descending before rendering
- use pagination or top-N display for the first screen on low-end devices

## 6. Persist a lightweight local snapshot
Store the last computed usage snapshot so the UI can open instantly.

Implementation:
- save the last successful usage summary in local storage
- show the cached data immediately on screen open
- refresh in the background and replace the cache when new data arrives
- keep the cache small and structured, not the full raw history

## 7. Avoid icon-heavy first render
Icons slow startup on older phones.

Implementation:
- render text rows first
- load icons lazily after the list is visible
- use placeholder icons until the real icon is ready
- skip icons entirely for items outside the visible viewport if needed

## 8. Add strict main-thread protection
Make sure future code does not regress.

Implementation:
- fail fast in debug builds if usage loading runs on the UI thread
- keep all expensive `PackageManager`, database, and usage stats work inside background tasks
- keep UI updates small and limited to adapter refreshes

## Recommended implementation pattern
A practical flow for the child usage screen:

1. Open the screen and show a loading state.
2. Load cached summary data immediately.
3. Start a background task to query usage stats for the selected day.
4. Build a minimal app model from package name and usage time.
5. Resolve labels and icons from cache only.
6. Return the sorted result to the UI thread.
7. Replace the cached snapshot with the fresh result.

## Result
This approach keeps the usage screen accurate while making it much faster on low-end devices like Android 9 phones. It reduces UI freezes, lowers binder overhead, and prevents the app from feeling stuck during usage loading.
