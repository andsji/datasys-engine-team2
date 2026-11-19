

<!-- This design adds lightweight `DELETE` and repeatable `COPY` operations while
keeping partitions immutable. It builds on the existing single JSON catalog,
per-partition min/max statistics, and table data files. -->

## Versioning

Each table has an increasing `version`, which is initially `0`. A data-changing `COPY` or `DELETE` increments the version number. A `COPY` that adds no rows and a `DELETE` that matches no rows are no-ops and do not increment the version.

The version number and table's current partition list are stored in `catalog/catalog.json`. The catalog contains only the latest committed version of each table. On restart, the engine loads the latest catalog from disk.

## Commit

Mutations are serialized by the `StorageEngine` instance that owns the data directory. A mutation reads the current table version, writes any new immutable partition bytes, and builds replacement metadata without changing the catalog visible to readers. It writes the candidate catalog to a temporary file in the catalog directory and atomically renames that file over `catalog.json`. Only after the durable catalog replacement succeeds does it publish the new immutable catalog reference in memory.

Statement snapshot acquisition and catalog publication use the same synchronization boundary. A reader therefore pins either the complete old table metadata or the complete new metadata, never a partially updated partition list. The catalog rename is the on-disk visibility point; the in-memory reference publication is the in-JVM visibility point. If an atomic rename is unavailable, the commit fails rather than falling back to a non-atomic catalog overwrite.

Partition data is written before the catalog points to it. A failure before catalog replacement leaves the old version visible; it can leave unreferenced bytes in the append-only data file, which are harmless and are not reclaimed in this design. This provides atomic visibility and catalog consistency, not a guarantee against every power-loss or filesystem-cache failure.

## Snapshot

At statement execution start, `SELECT` and `DELETE` pin one immutable snapshot of the table metadata: its version, schema, data-file path, and partition definitions (including offsets, row counts, and min/max summaries). Planning, scanning, and row rewriting all use that snapshot and do not look up the current catalog again during the statement.

The snapshot is a reference to immutable metadata rather than a mutable catalog object or a later copy assembled when the plan opens. This keeps partition selection and the corresponding offsets/counts from different versions from being mixed.

## Retention

Only the latest version is addressable through the catalog, including after a restart. 
A running statement keeps its pinned metadata alive through its
ordinary object reference; old metadata can be garbage-collected after no statement refers to it. There is no fixed version-count limit and no explicit reference-count table.

Data files and old partition bytes are not reclaimed, so a pinned snapshot's partition offsets remain readable even after a newer catalog version is published. If a future API requests a version that is no longer available, the engine must report that the version is unavailable rather than silently substituting the current version.

## The rewrite set

The planner and `DELETE` share one partition-pruning helper in the storage layer. Given a predicate, the helper returns exactly the partitions that may contain a match: the complement of the existing `cannotMatch` pruning decision. A `DELETE` without a predicate considers every partition.

Partitions outside that set are carried forward unchanged. Each candidate partition is scanned and filtered. If it contains no matching rows, its original partition definition is carried forward unchanged. If some rows are deleted and rows remain, the retained rows are written as a new immutable partition with freshly computed statistics. If no rows remain, that partition is omitted from the new list.

## New version

A `DELETE` that removes no rows is a no-op: it does not write replacement partitions, publish a catalog, or increment the table version.

## Partition placement

Each table continues to use one data file. `COPY` appends new partitions to that file, and `DELETE` appends rewritten partitions for changed candidate partitions to the same file. Existing partition bytes are never edited or compacted; the new catalog's partition list points to the retained old partitions and the newly appended replacements.
