#### Question and x axis
*define the dimension you sweep. Examples are* `maxRowsPerPartition` *from 2 to 1024, table size from 1K to 1M rows, sorted versus shuffled input, or predicate selectivity from 0% to 100%.*

We want to test how partition size (`maxRowsPerPartition`, default 1,000) affects the duration of `SELECT` statements. The table contains 235,785 data rows from `experiment/seed/aqi.csv` (excluding its header). The repeated query is `SELECT * FROM aqi WHERE aqi_value > 200`.
\
The experiment will be run with partition sizes of 2, 3, 10, 25, 100, 500, 1,000, 2,500, 5,000, and 10,000. Partition size is the x axis.
\
Run `experiment/script/run-experiment1.sh` to execute the experiment. It creates a fresh data directory for every partition size, loads the same seed data, performs one warm-up SELECT followed by five measured SELECTs, and writes the duration median and mean to `summary.csv`. Preprocessing retains `prominent_pollutants` as a string and converts commas between pollutant labels to semicolons; it excludes the unrelated `unit` column, whose value contains a quoted comma. Each run's experiment log and summary are saved under a uniquely named directory in `experiment/results/`, separate from the normal engine log. The script uses the Java runtime selected by Maven. For a quick smoke test, set `EXPERIMENT_PARTITION_SIZES` to a single value; otherwise it runs all documented sizes.

#### Metric and y axis 
*define what you measure from your own log. This could be the fraction of partitions read from the* `decision=` *lines, or the median* `durationMs` *from the summary lines. State the plot before you see it: what is on the x axis, what is on the y axis, and what one curve represents. Use a log scale on x if the values span multiple orders of magnitude.*

The median of a `SELECT` statement's `durationMs` will constitute the y axis. These are read from our logs.

#### Procedure
*describe data generation, the values you vary, the number of repetitions, whether you include or exclude the first cold run, the machine, JVM version, and heap size.*

The test data will be a fixed data set, so that all experiment runs will use the same data, just with varying partition sizes. A fresh table is created and loaded for each partition size. The first SELECT is a warm-up and is excluded; the following five runs are measured. The script calculates both median and mean from those five durations.
\
JVM Version is 26. Machine specs will be noted in the reports (we will try both machines and decide which is preferable).

#### Hypothesis
*state it before the first run, and quantify it where possible. "Sorted input halves the partitions read at every partition size" is informative. "Pruning improves" is less informative.*

Our expectation is, that the experiment results will form a bell curve. We expect that the experiments with smaller partition sizes will be slower due to a higher throughput, as we will have to check many tiny partitions. When the partition sizes grow larger, we expect the performance to improve, up until a certain point, where the partitions are so large that pruning stops. 