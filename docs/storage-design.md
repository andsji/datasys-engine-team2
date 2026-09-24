# 1. Catalog storage:

One catalog file in JSON format. Located in subdirectory /catalog, allowing for expanding to one catalog file per table in the future. 

# 2. Catalog contents

The catalog contains a schema per table, including the column names & types. It also includes the partitions.


# 3. Where the min/max summaries live

In the catalog file, per partition.

# 4. Restart
*What does a fresh StorageEngine on the same directory have to read before it can answer a select ?*

The catalog JSON file.

# 5. Layout inside a partition

Row-by-row NSM tuple storage, since it is the easiest.

# 6. Partition size

Default: 1000 rows per partition. 

# 7. Value encodings and framing

`STRING` stored as length-prefixed ASCII bytes, `LONG` & `DOUBLE` stored as 8 bytes.


# 8. Byte order

Big-endian byte order, since it is standard for `ByteBuffer` and it seems favorable to use the default.
