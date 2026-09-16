# 1. Catalog storage:

One catalog file in JSON format. Located in subdirectory /catalog, allowing for expanding to one catalog file per table in the future. 

# 2. Catalog contents

The catalog contains a schema per table, including the column names & types. It also includes the partitions.


# 3. Where the min/max summaries live
*The requirement is only that they exist per column per partition and that select can consult them without reading the column data they describe.
Three designs are defensible. 
A footer after the data is Parquet's choice and is natural for a single-pass writer. 
A header at the front is convenient for the reader, but the writer must buffer the partition or seek back to fill it in. 
In the catalog only means that pruning needs no data-file I/O at all, as in Snowflake and Iceberg, but a data file is then no longer self-describing. 
Pick one and justify it.*

A header at the front for each column in each partition (check with Martin/TA)


# 4. Restart
*What does a fresh StorageEngine on the same directory have to read before it can answer a select ?*

The min/max of the partitions. 


# 5. Layout inside a partition

Following NSM-tuple format (row-wise). We want to focus on OLTP queries. 

# 6. Partition size

Default: 100 rows per partition. Arbitrary number, since we don’t really know the potential size of the database.


# 7. Value encodings and framing

`STRING` stored as length-prefixed ASCII bytes, `LONG` & `DOUBLE` stored as 8 bytes.


# 8. Byte order

Big-endian byte order, since it is standard for `ByteBuffer` and it seems favorable to use the default.
