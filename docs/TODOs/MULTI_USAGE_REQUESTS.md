# TODO — Multiple USAGES requests for the same file

## Problem

When a response requests multiple `USAGES` views for different elements from the same source file, the IDE currently returns only one of those requested views .

Example: requesting usages for several nested variants of the same sealed hierarchy in one response returns usages for only the first or one selected variant instead of returning every requested result.This forces the model to request each `USAGES` element separately across multiple turns and makes large refactors unnecessarily slow.

## Expected behavior

        Every requested `USAGES` view should be returned independently, even when several requests point to the same physical file.

## Later

Investigate request deduplication / grouping in the requested -views pipeline . Deduplication should include the complete view identity, especially `granularity` and `elementPath`, rather than file path alone.
